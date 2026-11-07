package com.jukescape;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The replacements folder is the only source of truth: a file named after a track replaces that track, and a folder
 * named after a track is a playlist of songs that replace it, played in file name order (or shuffled, if the folder
 * holds a {@value #SHUFFLE_MARKER} file).
 * <p>
 * The folder is indexed into an immutable map off the client thread, so lookups during a game tick are a single
 * hash lookup with no disk access. Single files use the same folder and naming the original Music Replacer used, so
 * its downloads keep working.
 */
@Slf4j
final class Library
{
	static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("music-replacer");
	/**
	 * Supported formats, most preferred first.
	 */
	static final List<String> EXTENSIONS = List.of(".opus", ".ogg", ".mp3", ".wav");

	/**
	 * Prefix of in-progress files, which the index ignores.
	 */
	static final String TEMP_PREFIX = ".tmp-";
	static final String SHUFFLE_MARKER = ".shuffle";

	private static final Pattern ACCENTS = Pattern.compile("\\p{M}");
	private static final Pattern INVALID_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
	private static final Pattern EDGE_DOTS_SPACES = Pattern.compile("^[. ]+|[. ]+$");
	/**
	 * The "01 - " that keeps playlist songs in the order they were added.
	 */
	private static final Pattern SONG_NUMBER = Pattern.compile("^(\\d+) - ");
	private static final int MAX_SONG_NAME = 80;

	static final class Entry
	{
		/**
		 * The track's name as its file or folder is named, e.g. "Sea Shanty 2".
		 */
		final String track;
		/**
		 * Songs in play order. One file is a plain replacement that loops.
		 */
		final List<Path> files;
		final boolean shuffle;
		/**
		 * The playlist folder, or null for a single file.
		 */
		final Path dir;
		private final List<Long> modified;

		Entry(String track, List<Path> files, List<Long> modified, boolean shuffle, Path dir)
		{
			this.track = track;
			this.files = Collections.unmodifiableList(files);
			this.modified = modified;
			this.shuffle = shuffle;
			this.dir = dir;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Entry))
			{
				return false;
			}
			Entry other = (Entry) o;
			return track.equals(other.track) && files.equals(other.files) && modified.equals(other.modified)
				&& shuffle == other.shuffle;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(track, files, modified, shuffle);
		}
	}

	private final AudioEngine engine;
	private final AtomicInteger version = new AtomicInteger();
	private volatile Map<String, Entry> entries = Collections.emptyMap();

	Library(AudioEngine engine)
	{
		this.engine = engine;
	}

	Entry get(String track)
	{
		Map<String, Entry> snapshot = entries;
		return snapshot.isEmpty() || track == null ? null : snapshot.get(key(track));
	}

	int size()
	{
		return entries.size();
	}

	/**
	 * Every replaced track, sorted by name.
	 */
	List<Entry> all()
	{
		List<Entry> all = new ArrayList<>(entries.values());
		all.sort(Comparator.comparing(entry -> entry.track.toLowerCase(Locale.ROOT)));
		return all;
	}

	/**
	 * Bumped whenever the index changes.
	 */
	int version()
	{
		return version.get();
	}

	synchronized void rescan() throws IOException
	{
		Files.createDirectories(DIR);
		Map<String, Path> singles = new HashMap<>();
		Map<String, Path> playlists = new HashMap<>();
		try (DirectoryStream<Path> children = Files.newDirectoryStream(DIR))
		{
			for (Path child : children)
			{
				String name = child.getFileName().toString();
				if (name.startsWith("."))
				{
					continue;
				}
				if (Files.isDirectory(child))
				{
					playlists.put(key(name), child);
					continue;
				}
				String ext = extension(name);
				if (ext != null && Files.isRegularFile(child))
				{
					// If a track has files in several formats, use the most preferred one
					singles.merge(key(name.substring(0, name.length() - ext.length())), child,
						(a, b) -> preference(a) <= preference(b) ? a : b);
				}
			}
		}

		Map<String, Entry> scanned = new HashMap<>();
		for (Map.Entry<String, Path> e : singles.entrySet())
		{
			scanned.put(e.getKey(), entry(e.getValue(), playlists.remove(e.getKey())));
		}
		for (Map.Entry<String, Path> e : playlists.entrySet())
		{
			Entry entry = entry(null, e.getValue());
			if (!entry.files.isEmpty())
			{
				scanned.put(e.getKey(), entry);
			}
		}

		if (!scanned.equals(entries))
		{
			entries = Collections.unmodifiableMap(scanned);
			version.incrementAndGet();
		}
	}

	/**
	 * @param single a plain replacement file, played first if there's also a playlist
	 */
	private static Entry entry(Path single, Path dir) throws IOException
	{
		List<Path> files = new ArrayList<>();
		if (single != null)
		{
			files.add(single);
		}
		if (dir != null)
		{
			files.addAll(songs(dir));
		}
		List<Long> modified = new ArrayList<>(files.size());
		for (Path file : files)
		{
			modified.add(Files.getLastModifiedTime(file).toMillis());
		}
		boolean shuffle = dir != null && Files.exists(dir.resolve(SHUFFLE_MARKER));
		String track = dir != null ? dir.getFileName().toString() : stripExtension(single.getFileName().toString());
		return new Entry(track, files, modified, shuffle, dir);
	}

	private static List<Path> songs(Path dir) throws IOException
	{
		List<Path> songs = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir))
		{
			for (Path file : files)
			{
				String name = file.getFileName().toString();
				if (!name.startsWith(".") && extension(name) != null && Files.isRegularFile(file))
				{
					songs.add(file);
				}
			}
		}
		songs.sort(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)));
		return songs;
	}

	/**
	 * Deletes leftovers from downloads that were interrupted by closing the client.
	 */
	synchronized void deleteTempFiles() throws IOException
	{
		Files.createDirectories(DIR);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(DIR, TEMP_PREFIX + "*"))
		{
			for (Path file : files)
			{
				Files.deleteIfExists(file);
			}
		}
	}

	/**
	 * Makes {@code source} the replacement for {@code track}, or adds it to the end of the track's playlist, after
	 * checking that it can actually be played. Songs always go in the track's folder, even the first one, so they
	 * keep their names.
	 *
	 * @param moveSource move instead of copy, for temporary downloads
	 * @param name       the song's name, used for its file name
	 * @param add        add to the playlist instead of replacing everything. A single replacement file (dropped
	 *                   into the folder by hand) becomes the playlist's first song.
	 */
	synchronized void install(String track, Path source, boolean moveSource, String name, boolean add) throws IOException
	{
		String stem = fileStem(track);
		String ext = extension(source.getFileName().toString());
		if (stem.isEmpty() || ext == null)
		{
			throw new IOException("only " + String.join("/", EXTENSIONS) + " files are supported");
		}

		Files.createDirectories(DIR);
		Path staged = DIR.resolve(TEMP_PREFIX + System.nanoTime() + ext);
		try
		{
			if (moveSource)
			{
				Files.move(source, staged, StandardCopyOption.REPLACE_EXISTING);
			}
			else
			{
				Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
			}
			verifyPlayable(staged);

			Entry old = get(track);
			if (old != null && !add)
			{
				release(old);
				deletePlaylist(old.dir);
				for (String other : EXTENSIONS)
				{
					Files.deleteIfExists(DIR.resolve(stem + other));
				}
			}

			Path dir = DIR.resolve(stem);
			Files.createDirectories(dir);
			// A single replacement file becomes the first song. Its song name isn't known, so it keeps the track's.
			for (String other : EXTENSIONS)
			{
				Path single = DIR.resolve(stem + other);
				if (Files.isRegularFile(single))
				{
					engine.release(single);
					Files.move(single, dir.resolve(nextSongName(dir, stem, other)));
				}
			}
			Files.move(staged, dir.resolve(nextSongName(dir, name, ext)));
		}
		finally
		{
			Files.deleteIfExists(staged);
			if (moveSource)
			{
				Files.deleteIfExists(source);
			}
		}
		rescan();
	}

	/**
	 * Removes one song from a track's replacement.
	 */
	synchronized void removeSong(Path file) throws IOException
	{
		engine.release(file);
		Files.deleteIfExists(file);
		Path dir = file.getParent();
		if (!dir.equals(DIR) && songs(dir).isEmpty())
		{
			deletePlaylist(dir);
		}
		rescan();
	}

	/**
	 * Moves a playlist song up (negative {@code by}) or down, renumbering the folder's files to match.
	 */
	synchronized void moveSong(Path file, int by) throws IOException
	{
		Path dir = file.getParent();
		if (dir.equals(DIR))
		{
			return;
		}
		List<Path> order = songs(dir);
		int from = order.indexOf(file);
		int to = from + by;
		if (from < 0 || to < 0 || to >= order.size())
		{
			return;
		}
		order.add(to, order.remove(from));

		// Through temporary names first, so two songs swapping numbers never collide
		Map<Path, Path> renames = new LinkedHashMap<>();
		for (int i = 0; i < order.size(); i++)
		{
			Path song = order.get(i);
			String name = song.getFileName().toString();
			String target = String.format("%02d - %s%s", i + 1, songName(song), extension(name));
			if (!name.equals(target))
			{
				renames.put(song, dir.resolve(target));
			}
		}
		Map<Path, Path> staged = new LinkedHashMap<>();
		for (Map.Entry<Path, Path> rename : renames.entrySet())
		{
			engine.release(rename.getKey());
			Path temp = dir.resolve(TEMP_PREFIX + staged.size() + extension(rename.getKey().getFileName().toString()));
			Files.move(rename.getKey(), temp, StandardCopyOption.REPLACE_EXISTING);
			staged.put(temp, rename.getValue());
		}
		for (Map.Entry<Path, Path> rename : staged.entrySet())
		{
			Files.move(rename.getKey(), rename.getValue());
		}
		rescan();
	}

	synchronized void setShuffle(String track, boolean shuffle) throws IOException
	{
		Entry entry = get(track);
		if (entry == null || entry.dir == null)
		{
			return;
		}
		Path marker = entry.dir.resolve(SHUFFLE_MARKER);
		if (shuffle)
		{
			if (!Files.exists(marker))
			{
				Files.createFile(marker);
			}
		}
		else
		{
			Files.deleteIfExists(marker);
		}
		rescan();
	}

	synchronized void remove(String track) throws IOException
	{
		Entry entry = get(track);
		if (entry != null)
		{
			release(entry);
			deletePlaylist(entry.dir);
		}
		String stem = fileStem(track);
		for (String ext : EXTENSIONS)
		{
			Files.deleteIfExists(DIR.resolve(stem + ext));
		}
		rescan();
	}

	synchronized int removeAll() throws IOException
	{
		engine.release(null);
		for (Entry entry : entries.values())
		{
			for (Path file : entry.files)
			{
				Files.deleteIfExists(file);
			}
			deletePlaylist(entry.dir);
		}
		int removed = entries.size();
		rescan();
		return removed;
	}

	private void release(Entry entry)
	{
		for (Path file : entry.files)
		{
			engine.release(file);
		}
	}

	/**
	 * Deletes a playlist folder's songs and shuffle marker, and the folder itself unless something else was put in it.
	 */
	private static void deletePlaylist(Path dir) throws IOException
	{
		if (dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		for (Path song : songs(dir))
		{
			Files.deleteIfExists(song);
		}
		Files.deleteIfExists(dir.resolve(SHUFFLE_MARKER));
		try
		{
			Files.deleteIfExists(dir);
		}
		catch (DirectoryNotEmptyException e)
		{
			log.debug("Leaving {}, it has other files in it", dir);
		}
	}

	/**
	 * A file name that sorts after every song already in {@code dir}.
	 */
	private static String nextSongName(Path dir, String name, String ext) throws IOException
	{
		int last = 0;
		for (Path song : songs(dir))
		{
			Matcher m = SONG_NUMBER.matcher(song.getFileName().toString());
			if (m.find())
			{
				last = Math.max(last, Integer.parseInt(m.group(1)));
			}
		}
		String stem = fileStem(name == null ? "" : name);
		if (stem.length() > MAX_SONG_NAME)
		{
			stem = stem.substring(0, MAX_SONG_NAME).trim();
		}
		return String.format("%02d - %s%s", last + 1, stem.isEmpty() ? "Song" : stem, ext);
	}

	/**
	 * A song's name for menus: its file name without the number and extension.
	 */
	static String songName(Path file)
	{
		return SONG_NUMBER.matcher(stripExtension(file.getFileName().toString())).replaceFirst("");
	}

	private static String stripExtension(String fileName)
	{
		String ext = extension(fileName);
		return ext == null ? fileName : fileName.substring(0, fileName.length() - ext.length());
	}

	private static void verifyPlayable(Path file) throws IOException
	{
		try (PcmSource source = PcmSource.open(file))
		{
			if (source.read(new byte[4096]) <= 0)
			{
				throw new IOException("the file contains no audio");
			}
		}
	}

	private static int preference(Path file)
	{
		return EXTENSIONS.indexOf(extension(file.getFileName().toString()));
	}

	/**
	 * @return the supported extension of {@code fileName} in lower case, or null
	 */
	static String extension(String fileName)
	{
		String lower = fileName.toLowerCase(Locale.ROOT);
		for (String ext : EXTENSIONS)
		{
			if (lower.endsWith(ext))
			{
				return ext;
			}
		}
		return null;
	}

	/**
	 * The file name (without extension) a track's replacement is stored under.
	 */
	static String fileStem(String track)
	{
		String name = Normalizer.normalize(track, Normalizer.Form.NFKD);
		name = ACCENTS.matcher(name).replaceAll("");
		name = INVALID_CHARS.matcher(name).replaceAll("_");
		return EDGE_DOTS_SPACES.matcher(name).replaceAll("");
	}

	private static String key(String trackOrStem)
	{
		return fileStem(trackOrStem).toLowerCase(Locale.ROOT);
	}
}
