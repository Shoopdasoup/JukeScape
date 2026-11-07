package com.jukescape;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays replacement tracks on a dedicated thread.
 * <p>
 * The client thread only posts requests and writes volatile fields, so it never touches the disk, a decoder or an
 * audio line. Tracks are streamed rather than loaded into memory, and volume, fades, looping and moving through
 * playlists all happen here.
 */
@Slf4j
final class AudioEngine implements Runnable
{
	private static final int LINE_BUFFER_MS = 300;
	private static final int CHUNK_BYTES = 8192;
	private static final float VOLUME_SLEW_SECONDS = 0.05f;

	private static final class Request
	{
		/**
		 * The songs to play, or null to stop.
		 */
		final List<Path> playlist;
		final boolean shuffle;
		/**
		 * Skip the fade-out and drop whatever is still buffered.
		 */
		final boolean immediate;
		final CompletableFuture<Void> done = new CompletableFuture<>();

		Request(List<Path> playlist, boolean shuffle, boolean immediate)
		{
			this.playlist = playlist;
			this.shuffle = shuffle;
			this.immediate = immediate;
		}
	}

	private final AtomicReference<Request> pending = new AtomicReference<>();
	private final Consumer<Path> onError;
	private final Thread thread;

	private volatile boolean running = true;
	private volatile float volume;
	private volatile int fadeMs = 800;
	private volatile Path current;
	private volatile boolean skipRequested;

	// Only touched by the audio thread
	private PcmSource source;
	private SourceDataLine line;
	private Request next;
	private List<Path> playlist;
	private boolean shuffle;
	private int index;
	/**
	 * Songs in a row that ended without playing anything, so a playlist of broken files stops instead of spinning.
	 */
	private int emptySongs;
	private long sourceBytes;
	private float fade;
	private float gain;

	AudioEngine(Consumer<Path> onError)
	{
		this.onError = onError;
		thread = new Thread(this, "jukescape-audio");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Fades out whatever is playing and then starts {@code playlist}, which loops. If the song that's playing is in
	 * the new playlist it just carries on, so adding songs or toggling shuffle doesn't restart it. Never blocks.
	 */
	void play(List<Path> playlist, boolean shuffle)
	{
		post(new Request(List.copyOf(playlist), shuffle, false));
	}

	/**
	 * Fades out and stops. Never blocks.
	 */
	void stop()
	{
		post(new Request(null, false, false));
	}

	/**
	 * Jumps to the next song in the playlist (or restarts a single song). Never blocks.
	 */
	void skip()
	{
		skipRequested = true;
		LockSupport.unpark(thread);
	}

	/**
	 * The song that's playing, or null.
	 */
	Path currentSong()
	{
		return current;
	}

	/**
	 * @param volume linear gain in [0, 1], smoothed on the audio thread
	 */
	void setVolume(float volume)
	{
		this.volume = volume;
	}

	void setFadeMs(int fadeMs)
	{
		this.fadeMs = Math.max(0, fadeMs);
	}

	/**
	 * Stops playback right away if {@code path} (or anything, when null) is open, and waits until its file handle is
	 * closed so the file can be replaced or deleted. Blocks, so never call it on the client thread.
	 */
	void release(Path path)
	{
		Path playing = current;
		if (playing == null || (path != null && !path.equals(playing)))
		{
			return;
		}

		Request request = new Request(null, false, true);
		post(request);
		try
		{
			request.done.get(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			log.debug("Timed out releasing {}", path, e);
		}
	}

	void shutdown()
	{
		running = false;
		LockSupport.unpark(thread);
	}

	private void post(Request request)
	{
		Request replaced = pending.getAndSet(request);
		if (replaced != null)
		{
			replaced.done.complete(null);
		}
		LockSupport.unpark(thread);
	}

	@Override
	public void run()
	{
		byte[] buf = new byte[CHUNK_BYTES];
		while (running)
		{
			try
			{
				step(buf);
			}
			catch (Exception e)
			{
				log.warn("Playback of {} failed", current, e);
				closeSource();
			}
		}

		closeSource();
		closeLine(false);
		complete(next);
		complete(pending.getAndSet(null));
	}

	private void step(byte[] buf) throws Exception
	{
		Request request = pending.getAndSet(null);
		if (request != null)
		{
			complete(next);
			next = request;
			if (!request.immediate && request.playlist != null && current != null && request.playlist.contains(current))
			{
				// Keep playing the current song (fading back in if it was on its way out) with the new playlist
				playlist = request.playlist;
				shuffle = request.shuffle;
				index = playlist.indexOf(current);
				next = null;
				request.done.complete(null);
			}
		}

		// Switch once the current track has faded out (or right away if nothing is playing)
		if (next != null && (source == null || next.immediate || fade <= 0f))
		{
			swap();
			return;
		}

		if (skipRequested)
		{
			skipRequested = false;
			if (source != null && next == null)
			{
				closeSource();
				line.flush();
				emptySongs = 0;
				openNext();
				if (source == null)
				{
					closeLine(false);
				}
				return;
			}
		}

		if (source == null)
		{
			LockSupport.park(this);
			return;
		}

		int n = source.read(buf);
		if (n <= 0)
		{
			// Playlists keep going until the game decides to move on by changing the track. A single song loops
			// seamlessly.
			emptySongs = sourceBytes == 0 ? emptySongs + 1 : 0;
			closeSource();
			if (next == null && emptySongs < playlist.size())
			{
				openNext();
			}
			if (source == null && next == null)
			{
				closeLine(true);
			}
			return;
		}

		sourceBytes += n;
		AudioFormat format = source.format();
		applyGain(buf, n, format.getChannels(), format.getSampleRate(), next == null ? 1f : 0f);
		line.write(buf, 0, n);
	}

	private void swap()
	{
		Request request = next;
		next = null;

		closeSource();
		if (request.immediate && line != null)
		{
			line.flush();
		}
		playlist = request.playlist;
		if (playlist != null && !playlist.isEmpty())
		{
			shuffle = request.shuffle;
			index = shuffle ? ThreadLocalRandom.current().nextInt(playlist.size()) : 0;
			emptySongs = 0;
			fade = 0f;
			open(playlist.get(index), true);
			if (source == null)
			{
				openNext();
			}
		}
		if (source == null)
		{
			closeLine(!request.immediate);
		}
		request.done.complete(null);
	}

	/**
	 * Opens the song after the one that just ended, skipping songs that fail to open.
	 */
	private void openNext()
	{
		int size = playlist.size();
		for (int tries = 0; tries < size && source == null; tries++)
		{
			if (shuffle && size > 1)
			{
				// Any song but the one that just played
				index = (index + 1 + ThreadLocalRandom.current().nextInt(size - 1)) % size;
			}
			else
			{
				index = (index + 1) % size;
			}
			open(playlist.get(index), false);
		}
	}

	private void open(Path path, boolean fadeIn)
	{
		PcmSource opened = null;
		try
		{
			opened = PcmSource.open(path);
			ensureLine(opened.format());
			source = opened;
			current = path;
			sourceBytes = 0;
			if (fadeIn)
			{
				fade = 0f;
			}
		}
		catch (Exception e)
		{
			log.warn("Couldn't play {}", path, e);
			PcmSource.closeQuietly(opened);
			onError.accept(path);
		}
	}

	private void ensureLine(AudioFormat format) throws LineUnavailableException
	{
		if (line != null && line.getFormat().matches(format))
		{
			return;
		}

		closeLine(false);
		int frameSize = format.getFrameSize();
		int bufferBytes = (int) (format.getFrameRate() * LINE_BUFFER_MS / 1000) * frameSize;
		SourceDataLine newLine = AudioSystem.getSourceDataLine(format);
		newLine.open(format, bufferBytes);
		newLine.start();
		line = newLine;
	}

	private void closeSource()
	{
		PcmSource.closeQuietly(source);
		source = null;
		current = null;
	}

	private void closeLine(boolean drain)
	{
		if (line == null)
		{
			return;
		}
		if (drain)
		{
			line.drain();
		}
		line.close();
		line = null;
	}

	/**
	 * Scales 16-bit little-endian samples in place, ramping the fade envelope and volume per frame so changes never
	 * click.
	 */
	private void applyGain(byte[] buf, int len, int channels, float sampleRate, float fadeTarget)
	{
		float targetGain = volume;
		float fadeStep = 1f / Math.max(1f, fadeMs * sampleRate / 1000f);
		float gainStep = 1f / (VOLUME_SLEW_SECONDS * sampleRate);
		int frameBytes = channels * 2;

		for (int i = 0; i + frameBytes <= len; i += frameBytes)
		{
			fade = approach(fade, fadeTarget, fadeStep);
			gain = approach(gain, targetGain, gainStep);
			float g = fade * gain;
			for (int j = i, end = i + frameBytes; j < end; j += 2)
			{
				int sample = (int) ((short) ((buf[j] & 0xff) | (buf[j + 1] << 8)) * g);
				buf[j] = (byte) sample;
				buf[j + 1] = (byte) (sample >> 8);
			}
		}
	}

	private static float approach(float value, float target, float step)
	{
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
	}

	private static void complete(Request request)
	{
		if (request != null)
		{
			request.done.complete(null);
		}
	}
}
