package com.jukescape;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * A streaming decoder that yields 16-bit signed little-endian PCM, a frame at a time.
 * <p>
 * Files are opened through NIO, which on Windows allows them to be deleted or replaced while open.
 */
interface PcmSource extends Closeable
{
	int STREAM_BUFFER = 1 << 16;

	AudioFormat format();

	/**
	 * Fills {@code buf} with whole frames.
	 *
	 * @return the number of bytes read, or -1 at the end of the track
	 */
	int read(byte[] buf) throws IOException;

	static PcmSource open(Path path) throws IOException
	{
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".opus"))
		{
			return new Opus(path);
		}
		if (name.endsWith(".ogg"))
		{
			// Usually Vorbis, but Opus is sometimes saved as .ogg too
			return Ogg.isOpus(path) ? new Opus(path) : new Vorbis(path);
		}
		if (name.endsWith(".mp3"))
		{
			return new Mp3(path);
		}
		if (name.endsWith(".wav"))
		{
			return new Wav(path);
		}
		throw new IOException("Unsupported audio format: " + path.getFileName());
	}

	static void closeQuietly(Closeable closeable)
	{
		if (closeable == null)
		{
			return;
		}
		try
		{
			closeable.close();
		}
		catch (IOException ignored)
		{
		}
	}

	final class Mp3 implements PcmSource
	{
		private final InputStream in;
		private final Bitstream bitstream;
		private final Decoder decoder = new Decoder();
		private final AudioFormat format;

		private short[] samples;
		private int sampleCount;
		private int samplePos;

		Mp3(Path path) throws IOException
		{
			in = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER);
			bitstream = new Bitstream(in);
			try
			{
				// The output format is only known once the first frame is decoded
				if (!nextFrame())
				{
					throw new IOException("No MP3 audio in " + path.getFileName());
				}
			}
			catch (IOException e)
			{
				close();
				throw e;
			}
			format = new AudioFormat(decoder.getOutputFrequency(), 16, decoder.getOutputChannels(), true, false);
		}

		@Override
		public AudioFormat format()
		{
			return format;
		}

		@Override
		public int read(byte[] buf) throws IOException
		{
			int len = 0;
			while (len + 1 < buf.length)
			{
				if (samplePos >= sampleCount && !nextFrame())
				{
					break;
				}
				short sample = samples[samplePos++];
				buf[len++] = (byte) sample;
				buf[len++] = (byte) (sample >> 8);
			}
			return len == 0 ? -1 : len;
		}

		private boolean nextFrame() throws IOException
		{
			try
			{
				Header header;
				while ((header = bitstream.readFrame()) != null)
				{
					try
					{
						SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
						samples = out.getBuffer();
						sampleCount = out.getBufferLength();
						samplePos = 0;
						return true;
					}
					catch (Exception e)
					{
						// Corrupt frame, skip it
					}
					finally
					{
						bitstream.closeFrame();
					}
				}
				return false;
			}
			catch (BitstreamException e)
			{
				throw new IOException(e);
			}
		}

		@Override
		public void close() throws IOException
		{
			try
			{
				bitstream.close();
			}
			catch (BitstreamException ignored)
			{
			}
			in.close();
		}
	}

	/**
	 * Splits an Ogg file into the packets of its first logical stream, for the Opus and Vorbis decoders.
	 */
	final class Ogg implements Closeable
	{
		private static final int FLAG_CONTINUED = 0x01;
		private static final int FLAG_END_OF_STREAM = 0x04;

		private final InputStream in;

		// Current page
		private final byte[] pageHeader = new byte[27];
		private final byte[] segments = new byte[255];
		private final byte[] pageData = new byte[255 * 255];
		private int segmentCount;
		private int segmentIndex;
		private int pageDataPos;
		private int serial = -1;

		// Current packet
		byte[] packet = new byte[4096];
		int packetLen;
		/**
		 * Granule position of the stream's last sample, once its final page has been read.
		 */
		long endGranule = Long.MAX_VALUE;

		Ogg(Path path) throws IOException
		{
			in = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER);
		}

		static boolean isOpus(Path path) throws IOException
		{
			try (Ogg ogg = new Ogg(path))
			{
				return ogg.nextPacket() && ogg.startsWith("OpusHead");
			}
		}

		/**
		 * Reassembles the next packet, which may span several pages.
		 *
		 * @return false at the end of the file
		 */
		boolean nextPacket() throws IOException
		{
			packetLen = 0;
			while (true)
			{
				if (segmentIndex >= segmentCount)
				{
					if (!nextPage())
					{
						return false;
					}
					continue;
				}

				int len = segments[segmentIndex++] & 0xff;
				if (packetLen + len > packet.length)
				{
					packet = Arrays.copyOf(packet, Math.max(packet.length * 2, packetLen + len));
				}
				System.arraycopy(pageData, pageDataPos, packet, packetLen, len);
				packetLen += len;
				pageDataPos += len;
				// A segment shorter than 255 bytes ends the packet
				if (len < 255)
				{
					return true;
				}
			}
		}

		boolean startsWith(String magic)
		{
			return startsWith(0, magic);
		}

		boolean startsWith(int offset, String magic)
		{
			return packetLen >= offset + magic.length()
				&& new String(packet, offset, magic.length(), StandardCharsets.US_ASCII).equals(magic);
		}

		private boolean nextPage() throws IOException
		{
			while (true)
			{
				if (!findCapturePattern())
				{
					return false;
				}
				try
				{
					readFully(pageHeader, 4, 23);
					int flags = pageHeader[5] & 0xff;
					long granule = littleEndian(pageHeader, 6, 8);
					int pageSerial = (int) littleEndian(pageHeader, 14, 4);
					segmentCount = pageHeader[26] & 0xff;
					readFully(segments, 0, segmentCount);
					int dataLen = 0;
					for (int i = 0; i < segmentCount; i++)
					{
						dataLen += segments[i] & 0xff;
					}
					readFully(pageData, 0, dataLen);

					if (serial == -1)
					{
						serial = pageSerial;
					}
					if (pageSerial != serial)
					{
						segmentCount = 0; // Some other stream in the same file
						continue;
					}
					if ((flags & FLAG_CONTINUED) == 0)
					{
						packetLen = 0; // A packet was cut short by damage, drop it
					}
					if ((flags & FLAG_END_OF_STREAM) != 0 && granule > 0)
					{
						endGranule = granule;
					}
					segmentIndex = 0;
					pageDataPos = 0;
					return true;
				}
				catch (EOFException e)
				{
					return false; // Truncated final page
				}
			}
		}

		/**
		 * Reads up to and including the next "OggS", skipping any garbage before it.
		 */
		private boolean findCapturePattern() throws IOException
		{
			int matched = 0;
			int b;
			while ((b = in.read()) != -1)
			{
				matched = b == "OggS".charAt(matched) ? matched + 1 : (b == 'O' ? 1 : 0);
				if (matched == 4)
				{
					return true;
				}
			}
			return false;
		}

		private void readFully(byte[] buf, int off, int len) throws IOException
		{
			while (len > 0)
			{
				int n = in.read(buf, off, len);
				if (n < 0)
				{
					throw new EOFException();
				}
				off += n;
				len -= n;
			}
		}

		private static long littleEndian(byte[] buf, int off, int len)
		{
			long value = 0;
			for (int i = len - 1; i >= 0; i--)
			{
				value = value << 8 | (buf[off + i] & 0xff);
			}
			return value;
		}

		@Override
		public void close() throws IOException
		{
			in.close();
		}
	}

	/**
	 * Opus in an Ogg container (.opus, sometimes .ogg).
	 * Supports mono and stereo, applies the stream's pre-skip and end trimming so loops stay gapless.
	 */
	final class Opus implements PcmSource
	{
		private static final int SAMPLE_RATE = 48000;
		/**
		 * The longest an Opus packet can be: 120 ms.
		 */
		private static final int MAX_FRAMES = 5760;

		private final Ogg ogg;
		private final OpusDecoder decoder;
		private final AudioFormat format;
		private final int channels;
		private final int preSkip;
		private final short[] pcm;

		// Decoded samples (interleaved) not yet returned
		private int pcmPos;
		private int pcmLen;
		private int framesToSkip;
		private long framesOut;

		Opus(Path path) throws IOException
		{
			ogg = new Ogg(path);
			try
			{
				// Identification header: "OpusHead", version, channels, pre-skip, input rate, output gain, mapping
				if (!ogg.nextPacket() || ogg.packetLen < 19 || !ogg.startsWith("OpusHead"))
				{
					throw new IOException("Not an Opus file: " + path.getFileName());
				}
				byte[] head = ogg.packet;
				channels = head[9] & 0xff;
				preSkip = (head[10] & 0xff) | (head[11] & 0xff) << 8;
				int outputGain = (short) ((head[16] & 0xff) | (head[17] & 0xff) << 8);
				if (channels < 1 || channels > 2 || head[18] != 0)
				{
					throw new IOException("Only mono and stereo Opus is supported: " + path.getFileName());
				}

				// Comment header
				if (!ogg.nextPacket() || !ogg.startsWith("OpusTags"))
				{
					throw new IOException("Damaged Opus file: " + path.getFileName());
				}

				decoder = new OpusDecoder(SAMPLE_RATE, channels);
				decoder.setGain(outputGain);
			}
			catch (IOException | OpusException e)
			{
				ogg.close();
				throw e instanceof IOException ? (IOException) e : new IOException(e);
			}
			catch (RuntimeException e)
			{
				ogg.close();
				throw new IOException("Damaged Opus file: " + path.getFileName(), e);
			}

			format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
			pcm = new short[MAX_FRAMES * channels];
			framesToSkip = preSkip;
		}

		@Override
		public AudioFormat format()
		{
			return format;
		}

		@Override
		public int read(byte[] buf) throws IOException
		{
			int len = 0;
			while (len + 1 < buf.length)
			{
				if (pcmPos >= pcmLen && !decodeNext())
				{
					break;
				}
				short sample = pcm[pcmPos++];
				buf[len++] = (byte) sample;
				buf[len++] = (byte) (sample >> 8);
			}
			return len == 0 ? -1 : len;
		}

		private boolean decodeNext() throws IOException
		{
			while (framesOut < endFrame() && ogg.nextPacket())
			{
				if (ogg.packetLen == 0)
				{
					continue;
				}

				int frames;
				try
				{
					frames = decoder.decode(ogg.packet, 0, ogg.packetLen, pcm, 0, MAX_FRAMES, false);
				}
				catch (OpusException | RuntimeException e)
				{
					continue; // Corrupt packet, skip it
				}

				// The encoder's start-up samples aren't part of the track
				int start = Math.min(framesToSkip, frames);
				framesToSkip -= start;
				// Nor is the padding after the last real sample
				int count = (int) Math.min(frames - start, endFrame() - framesOut);
				if (count <= 0)
				{
					continue;
				}

				framesOut += count;
				pcmPos = start * channels;
				pcmLen = (start + count) * channels;
				return true;
			}
			return false;
		}

		private long endFrame()
		{
			return ogg.endGranule == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, ogg.endGranule - preSkip);
		}

		@Override
		public void close() throws IOException
		{
			ogg.close();
		}
	}

	/**
	 * Vorbis in an Ogg container (.ogg), mono or stereo.
	 */
	final class Vorbis implements PcmSource
	{
		private final Ogg ogg;
		private final Info info = new Info();
		private final Comment comment = new Comment();
		private final DspState dsp = new DspState();
		private final Block block = new Block(dsp);
		private final Packet packet = new Packet();
		private final AudioFormat format;
		private final int channels;
		private final float[][][] pcmRef = new float[1][][];
		private final int[] index;

		// Decoded samples (interleaved) not yet returned
		private short[] pcm = new short[0];
		private int pcmPos;
		private int pcmLen;
		private long framesOut;
		private long packetNo;

		Vorbis(Path path) throws IOException
		{
			ogg = new Ogg(path);
			try
			{
				// Identification, comment and setup headers
				info.init();
				comment.init();
				for (int i = 0; i < 3; i++)
				{
					if (!ogg.nextPacket() || !ogg.startsWith(1, "vorbis") || info.synthesis_headerin(comment, packet()) < 0)
					{
						throw new IOException("Not an Ogg Vorbis file: " + path.getFileName());
					}
				}
				if (info.channels < 1 || info.channels > 2)
				{
					throw new IOException("Only mono and stereo Vorbis is supported: " + path.getFileName());
				}
				dsp.synthesis_init(info);
				block.init(dsp);
			}
			catch (IOException e)
			{
				ogg.close();
				throw e;
			}
			catch (RuntimeException e)
			{
				ogg.close();
				throw new IOException("Damaged Ogg Vorbis file: " + path.getFileName(), e);
			}

			channels = info.channels;
			index = new int[channels];
			format = new AudioFormat(info.rate, 16, channels, true, false);
		}

		/**
		 * Wraps the Ogg reader's current packet for JOrbis.
		 */
		private Packet packet()
		{
			packet.packet_base = ogg.packet;
			packet.packet = 0;
			packet.bytes = ogg.packetLen;
			packet.b_o_s = packetNo == 0 ? 1 : 0;
			packet.e_o_s = 0;
			packet.granulepos = -1;
			packet.packetno = packetNo++;
			return packet;
		}

		@Override
		public AudioFormat format()
		{
			return format;
		}

		@Override
		public int read(byte[] buf) throws IOException
		{
			int len = 0;
			while (len + 1 < buf.length)
			{
				if (pcmPos >= pcmLen && !decodeNext())
				{
					break;
				}
				short sample = pcm[pcmPos++];
				buf[len++] = (byte) sample;
				buf[len++] = (byte) (sample >> 8);
			}
			return len == 0 ? -1 : len;
		}

		private boolean decodeNext() throws IOException
		{
			while (true)
			{
				int frames = dsp.synthesis_pcmout(pcmRef, index);
				if (frames > 0)
				{
					// The last page's granule position says where the real audio ends, so loops stay gapless
					int keep = (int) Math.min(frames, Math.max(0, ogg.endGranule - framesOut));
					if (keep > 0)
					{
						interleave(keep);
					}
					dsp.synthesis_read(frames);
					if (keep > 0)
					{
						framesOut += keep;
						return true;
					}
					continue;
				}
				if (!ogg.nextPacket())
				{
					return false;
				}
				try
				{
					if (block.synthesis(packet()) == 0)
					{
						dsp.synthesis_blockin(block);
					}
				}
				catch (RuntimeException e)
				{
					// Corrupt packet, skip it
				}
			}
		}

		private void interleave(int frames)
		{
			if (pcm.length < frames * channels)
			{
				pcm = new short[frames * channels];
			}
			float[][] channelData = pcmRef[0];
			for (int c = 0; c < channels; c++)
			{
				float[] samples = channelData[c];
				int offset = index[c];
				for (int i = 0; i < frames; i++)
				{
					int value = (int) (samples[offset + i] * 32767f);
					pcm[i * channels + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
				}
			}
			pcmPos = 0;
			pcmLen = frames * channels;
		}

		@Override
		public void close() throws IOException
		{
			ogg.close();
		}
	}

	final class Wav implements PcmSource
	{
		private final AudioInputStream in;

		Wav(Path path) throws IOException
		{
			InputStream raw = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER);
			try
			{
				AudioInputStream decoded = AudioSystem.getAudioInputStream(raw);
				AudioFormat source = decoded.getFormat();
				AudioFormat target = new AudioFormat(source.getSampleRate(), 16, source.getChannels(), true, false);
				in = source.matches(target) ? decoded : AudioSystem.getAudioInputStream(target, decoded);
			}
			catch (UnsupportedAudioFileException | IllegalArgumentException e)
			{
				raw.close();
				throw new IOException("Unsupported WAV file " + path.getFileName(), e);
			}
		}

		@Override
		public AudioFormat format()
		{
			return in.getFormat();
		}

		@Override
		public int read(byte[] buf) throws IOException
		{
			return in.read(buf);
		}

		@Override
		public void close() throws IOException
		{
			in.close();
		}
	}
}
