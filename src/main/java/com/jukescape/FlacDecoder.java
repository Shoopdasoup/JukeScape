package com.jukescape;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * A streaming FLAC decoder, written from the format specification (RFC 9639): reads a native .flac file a frame at a
 * time, giving each channel's samples at the file's own bit depth. {@link PcmSource.Flac} turns those into 16-bit
 * PCM.
 * <p>
 * Damaged frames are skipped by searching for the next frame's sync code.
 */
final class FlacDecoder implements Closeable
{
	private static final int MAX_BLOCK_SIZE = 65535;

	private final BitReader in;
	final int sampleRate;
	final int channels;
	final int bitsPerSample;
	final long totalSamples;
	/**
	 * The MD5 of the whole decoded audio from the file's STREAMINFO, all zeros when the encoder didn't set it.
	 */
	final byte[] md5 = new byte[16];

	/**
	 * The last decoded frame: {@link #blockSize} samples per channel, at {@link #bitsPerSample} bits.
	 */
	final long[][] samples;
	int blockSize;

	// Per-subframe scratch
	private final long[] coefficients = new long[32];
	/**
	 * The second byte of the last sync code found, which holds the block strategy bit.
	 */
	private int syncByte;

	FlacDecoder(InputStream stream) throws IOException
	{
		in = new BitReader(stream);
		skipId3();
		if (in.readBits(32) != 0x664C6143) // "fLaC"
		{
			throw new IOException("not a FLAC file");
		}

		// Metadata blocks; STREAMINFO is always first
		boolean last;
		int rate = 0;
		int channelCount = 0;
		int bits = 0;
		long total = 0;
		boolean sawInfo = false;
		do
		{
			last = in.readBits(1) == 1;
			int type = (int) in.readBits(7);
			int length = (int) in.readBits(24);
			if (type == 0 && !sawInfo)
			{
				if (length < 34)
				{
					throw new IOException("damaged FLAC header");
				}
				in.readBits(16); // Minimum block size
				in.readBits(16); // Maximum block size
				in.readBits(24); // Minimum frame size
				in.readBits(24); // Maximum frame size
				rate = (int) in.readBits(20);
				channelCount = (int) in.readBits(3) + 1;
				bits = (int) in.readBits(5) + 1;
				total = in.readBits(4) << 32 | in.readBits(32);
				for (int i = 0; i < 16; i++)
				{
					md5[i] = (byte) in.readBits(8);
				}
				in.skipBytes(length - 34);
				sawInfo = true;
			}
			else
			{
				in.skipBytes(length);
			}
		}
		while (!last);
		if (!sawInfo || rate == 0)
		{
			throw new IOException("damaged FLAC header");
		}
		sampleRate = rate;
		channels = channelCount;
		bitsPerSample = bits;
		totalSamples = total;
		samples = new long[channels][MAX_BLOCK_SIZE];
	}

	/**
	 * Some taggers put an ID3v2 tag in front of the FLAC data.
	 */
	private void skipId3() throws IOException
	{
		if (!in.peekId3())
		{
			return;
		}
		in.skipBytes(6); // "ID3", version, flags
		int size = 0;
		for (int i = 0; i < 4; i++)
		{
			size = size << 7 | (int) in.readBits(8) & 0x7F;
		}
		in.skipBytes(size);
	}

	/**
	 * Decodes the next frame into {@link #samples}.
	 *
	 * @return false at the end of the file
	 */
	boolean readFrame() throws IOException
	{
		while (true)
		{
			try
			{
				if (!findSync())
				{
					return false;
				}
				if (decodeFrame())
				{
					return true;
				}
			}
			catch (EOFException e)
			{
				return false;
			}
			catch (DamagedException | ArrayIndexOutOfBoundsException | NegativeArraySizeException e)
			{
				// Look for the next frame
			}
		}
	}

	/**
	 * Moves to just past the next frame sync code: 14 one bits, a zero, and the block strategy bit.
	 */
	private boolean findSync() throws IOException
	{
		in.crc8(); // Stop tracking, if a header was abandoned partway
		in.alignToByte();
		int previous = -1;
		while (true)
		{
			int b = in.readByteOrEof();
			if (b < 0)
			{
				return false;
			}
			if (previous == 0xFF && (b & 0xFE) == 0xF8)
			{
				syncByte = b;
				return true;
			}
			previous = b;
		}
	}

	private static final class DamagedException extends IOException
	{
		DamagedException()
		{
			super("damaged FLAC frame");
		}

		@Override
		public synchronized Throwable fillInStackTrace()
		{
			return this; // Thrown often on damaged files, and shared, so no stack trace
		}
	}

	private static final DamagedException DAMAGED = new DamagedException();

	/**
	 * @return false if the header turned out not to be a frame header after all
	 */
	private boolean decodeFrame() throws IOException
	{
		// The header, from just after the sync code; it's checked with its CRC-8 before trusting any of it
		in.startCrc8(0xFF, syncByte); // The CRC covers the sync code too
		int blockCode = (int) in.readBits(4);
		int rateCode = (int) in.readBits(4);
		int channelCode = (int) in.readBits(4);
		int sizeCode = (int) in.readBits(3);
		if (in.readBits(1) != 0 || blockCode == 0 || rateCode == 15 || channelCode > 10 || sizeCode == 3)
		{
			return false;
		}
		// Frame or sample number, UTF-8 style; only its length matters here
		int first = (int) in.readBits(8);
		int extra = first < 0x80 ? 0 : first >= 0xFE ? 6 : Integer.numberOfLeadingZeros(~first << 24) - 1;
		if (first >= 0x80 && first < 0xC0)
		{
			return false;
		}
		for (int i = 0; i < extra; i++)
		{
			if ((in.readBits(8) & 0xC0) != 0x80)
			{
				return false;
			}
		}

		int size;
		if (blockCode == 1)
		{
			size = 192;
		}
		else if (blockCode <= 5)
		{
			size = 576 << (blockCode - 2);
		}
		else if (blockCode == 6)
		{
			size = (int) in.readBits(8) + 1;
		}
		else if (blockCode == 7)
		{
			size = (int) in.readBits(16) + 1;
		}
		else
		{
			size = 256 << (blockCode - 8);
		}
		if (rateCode == 12)
		{
			in.readBits(8);
		}
		else if (rateCode == 13 || rateCode == 14)
		{
			in.readBits(16);
		}
		int crc = in.crc8();
		if (in.readBits(8) != crc)
		{
			return false;
		}

		int bits = sizeCode == 0 ? bitsPerSample : new int[]{0, 8, 12, 0, 16, 20, 24, 32}[sizeCode];
		int frameChannels = channelCode < 8 ? channelCode + 1 : 2;
		if (bits != bitsPerSample || frameChannels != channels || size > MAX_BLOCK_SIZE)
		{
			throw DAMAGED; // Not something a file changes partway through
		}

		for (int ch = 0; ch < frameChannels; ch++)
		{
			// The side channel of a stereo pair carries one more bit
			boolean side = channelCode == 8 && ch == 1 || channelCode == 9 && ch == 0 || channelCode == 10 && ch == 1;
			decodeSubframe(samples[ch], size, bits + (side ? 1 : 0));
		}

		long[] a = samples[0];
		long[] b = frameChannels > 1 ? samples[1] : null;
		switch (channelCode)
		{
			case 8: // Left and side
				for (int i = 0; i < size; i++)
				{
					b[i] = a[i] - b[i];
				}
				break;
			case 9: // Side and right
				for (int i = 0; i < size; i++)
				{
					a[i] += b[i];
				}
				break;
			case 10: // Mid and side
				for (int i = 0; i < size; i++)
				{
					long mid = a[i] << 1 | b[i] & 1;
					long sideSample = b[i];
					a[i] = mid + sideSample >> 1;
					b[i] = mid - sideSample >> 1;
				}
				break;
			default:
				break;
		}

		in.alignToByte();
		in.readBits(16); // CRC-16 of the frame
		blockSize = size;
		return true;
	}

	private void decodeSubframe(long[] out, int size, int bits) throws IOException
	{
		if (in.readBits(1) != 0)
		{
			throw DAMAGED;
		}
		int type = (int) in.readBits(6);
		int wasted = 0;
		if (in.readBits(1) == 1)
		{
			wasted = in.readUnary() + 1;
			bits -= wasted;
			if (bits <= 0)
			{
				throw DAMAGED;
			}
		}

		if (type == 0)
		{
			long value = in.readSigned(bits);
			for (int i = 0; i < size; i++)
			{
				out[i] = value;
			}
		}
		else if (type == 1)
		{
			for (int i = 0; i < size; i++)
			{
				out[i] = in.readSigned(bits);
			}
		}
		else if (type >= 8 && type <= 12)
		{
			int order = type - 8;
			readWarmUp(out, order, size, bits);
			readResidual(out, order, size);
			restoreFixed(out, order, size);
		}
		else if (type >= 32)
		{
			int order = type - 31;
			readWarmUp(out, order, size, bits);
			int precision = (int) in.readBits(4) + 1;
			if (precision == 16)
			{
				throw DAMAGED;
			}
			int shift = (int) in.readSigned(5);
			if (shift < 0)
			{
				throw DAMAGED;
			}
			for (int i = 0; i < order; i++)
			{
				coefficients[i] = in.readSigned(precision);
			}
			readResidual(out, order, size);
			restoreLpc(out, order, size, shift);
		}
		else
		{
			throw DAMAGED;
		}

		if (wasted > 0)
		{
			for (int i = 0; i < size; i++)
			{
				out[i] <<= wasted;
			}
		}
	}

	private void readWarmUp(long[] out, int order, int size, int bits) throws IOException
	{
		if (order > size)
		{
			throw DAMAGED;
		}
		for (int i = 0; i < order; i++)
		{
			out[i] = in.readSigned(bits);
		}
	}

	/**
	 * Reads the Rice-coded residual into {@code out} from {@code order} on, before the prediction is added.
	 */
	private void readResidual(long[] out, int order, int size) throws IOException
	{
		int method = (int) in.readBits(2);
		if (method > 1)
		{
			throw DAMAGED;
		}
		int paramBits = method == 0 ? 4 : 5;
		int escape = (1 << paramBits) - 1;
		int partitionOrder = (int) in.readBits(4);
		int partitions = 1 << partitionOrder;
		int perPartition = size >> partitionOrder;
		if (perPartition << partitionOrder != size || perPartition < order)
		{
			throw DAMAGED;
		}

		int i = order;
		for (int p = 0; p < partitions; p++)
		{
			int end = (p + 1) * perPartition;
			int param = (int) in.readBits(paramBits);
			if (param == escape)
			{
				int raw = (int) in.readBits(5);
				for (; i < end; i++)
				{
					out[i] = raw == 0 ? 0 : in.readSigned(raw);
				}
			}
			else
			{
				for (; i < end; i++)
				{
					long folded = (long) in.readUnary() << param | in.readBits(param);
					out[i] = folded >>> 1 ^ -(folded & 1);
				}
			}
		}
	}

	private static void restoreFixed(long[] s, int order, int size)
	{
		switch (order)
		{
			case 1:
				for (int i = 1; i < size; i++)
				{
					s[i] += s[i - 1];
				}
				break;
			case 2:
				for (int i = 2; i < size; i++)
				{
					s[i] += 2 * s[i - 1] - s[i - 2];
				}
				break;
			case 3:
				for (int i = 3; i < size; i++)
				{
					s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3];
				}
				break;
			case 4:
				for (int i = 4; i < size; i++)
				{
					s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4];
				}
				break;
			default:
				break;
		}
	}

	private void restoreLpc(long[] s, int order, int size, int shift)
	{
		long[] c = coefficients;
		for (int i = order; i < size; i++)
		{
			long sum = 0;
			for (int j = 0; j < order; j++)
			{
				sum += c[j] * s[i - 1 - j];
			}
			s[i] += sum >> shift;
		}
	}

	@Override
	public void close() throws IOException
	{
		in.close();
	}

	/**
	 * Reads bits most significant first, with a running CRC-8 for frame headers.
	 */
	private static final class BitReader implements Closeable
	{
		private static final int[] CRC8 = new int[256];

		static
		{
			for (int i = 0; i < 256; i++)
			{
				int crc = i;
				for (int b = 0; b < 8; b++)
				{
					crc = (crc & 0x80) != 0 ? (crc << 1 ^ 0x07) & 0xFF : crc << 1 & 0xFF;
				}
				CRC8[i] = crc;
			}
		}

		private final InputStream in;
		private final byte[] buffer = new byte[PcmSource.STREAM_BUFFER];
		private int bufferPos;
		private int bufferLen;
		/**
		 * Unread bits, left-aligned.
		 */
		private long cache;
		private int cached;
		/**
		 * The CRC-8 of the bytes taken from the cache since {@link #startCrc8}, or -1 when not tracking.
		 */
		private int crc = -1;
		private int crcBits;

		BitReader(InputStream in)
		{
			this.in = in;
		}

		private int nextByte() throws IOException
		{
			if (bufferPos == bufferLen)
			{
				bufferLen = in.read(buffer);
				bufferPos = 0;
				if (bufferLen <= 0)
				{
					bufferLen = 0;
					return -1;
				}
			}
			return buffer[bufferPos++] & 0xFF;
		}

		private void fill(int needed) throws IOException
		{
			while (cached <= 56)
			{
				int b = nextByte();
				if (b < 0)
				{
					if (cached < needed)
					{
						throw new EOFException();
					}
					return;
				}
				cache |= (long) b << (56 - cached);
				cached += 8;
			}
		}

		long readBits(int n) throws IOException
		{
			if (n == 0)
			{
				return 0;
			}
			if (cached < n)
			{
				fill(n);
			}
			long value = cache >>> (64 - n);
			consume(n);
			return value;
		}

		long readSigned(int n) throws IOException
		{
			if (n == 0)
			{
				return 0;
			}
			long value = readBits(n);
			return value << (64 - n) >> (64 - n);
		}

		/**
		 * Counts zero bits up to the next one bit, and skips that one too.
		 */
		int readUnary() throws IOException
		{
			int count = 0;
			while (true)
			{
				if (cached == 0)
				{
					fill(1);
				}
				int zeros = Long.numberOfLeadingZeros(cache);
				if (zeros < cached)
				{
					consume(zeros + 1);
					return count + zeros;
				}
				count += cached;
				consume(cached);
			}
		}

		private void consume(int n)
		{
			if (crc >= 0)
			{
				// Frame headers are whole bytes, so update per byte as each completes
				long taken = n == 64 ? cache : cache >>> (64 - n);
				for (int i = n - 1; i >= 0; i--)
				{
					crcBits = crcBits << 1 | (int) (taken >>> i & 1);
					if ((crcBits & 0x100) != 0)
					{
						crc = CRC8[crc ^ crcBits & 0xFF];
						crcBits = 1;
					}
				}
			}
			cache = n == 64 ? 0 : cache << n;
			cached -= n;
		}

		void startCrc8(int... bytesBefore)
		{
			int value = 0;
			for (int b : bytesBefore)
			{
				value = CRC8[value ^ b];
			}
			crc = value;
			crcBits = 1;
		}

		/**
		 * The CRC-8 so far, ending tracking.
		 */
		int crc8()
		{
			int value = crc;
			crc = -1;
			return value;
		}

		void alignToByte()
		{
			consume(cached & 7);
		}

		int readByteOrEof() throws IOException
		{
			if (cached >= 8)
			{
				return (int) readBits(8);
			}
			return nextByte();
		}

		void skipBytes(long n) throws IOException
		{
			alignToByte();
			while (n > 0 && cached >= 8)
			{
				consume(8);
				n--;
			}
			while (n > 0)
			{
				if (bufferPos < bufferLen)
				{
					int step = (int) Math.min(n, bufferLen - bufferPos);
					bufferPos += step;
					n -= step;
				}
				else if (nextByte() < 0)
				{
					throw new EOFException();
				}
				else
				{
					n--;
				}
			}
		}

		/**
		 * Whether the next three bytes are "ID3", without reading them.
		 */
		boolean peekId3() throws IOException
		{
			fill(24);
			return cache >>> 40 == 0x494433;
		}

		@Override
		public void close() throws IOException
		{
			in.close();
		}
	}
}
