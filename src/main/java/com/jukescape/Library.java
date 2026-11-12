package com.jukescape;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
import java.util.Properties;
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
	/**
	 * In a playlist or profile folder, holds the id of the {@link BossMode} it plays in. Takes priority over shuffle.
	 */
	static final String MODE_MARKER = ".mode";
	/**
	 * In a playlist or profile folder, which boss mode slots each song plays in (song name = spec, see
	 * {@link SlotPlan}).
	 */
	static final String SLOTS_FILE = ".slots";
	/**
	 * In a playlist or profile folder, the boss mode's options (key = value).
	 */
	static final String OPTIONS_FILE = ".options";
	/**
	 * Settings files that belong to a playlist or profile folder and move with its songs.
	 */
	private static final List<String> SETTINGS_FILES = List.of(".shuffle", ".mode", ".slots", ".options");
	static final String MODE_IN_ORDER = "in-order";
	static final String MODE_SHUFFLE = "shuffle";
	/**
	 * In a track folder with profiles, names the active one.
	 */
	static final String PROFILE_MARKER = ".profile";
	/**
	 * The profile a track's songs go into when it gets a second one.
	 */
	static final String MAIN_PROFILE = "Main";
	private static final int MAX_PROFILE_NAME = 40;

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
		 * The active profile's songs in play order. One file is a plain replacement that loops. Empty when the active
		 * profile has no songs, which means the game's own music plays.
		 */
		final List<Path> files;
		final boolean shuffle;
		/**
		 * The boss mode the active profile plays in, or null for in order or shuffled.
		 */
		final BossMode bossMode;
		/**
		 * Song name to the boss mode slots it plays in, for songs the player set them for.
		 */
		final Map<String, String> slotSpecs;
		/**
		 * The boss mode's options for this profile.
		 */
		final Map<String, String> options;
		/**
		 * The folder the active profile's songs are in, or null for a single file.
		 */
		final Path dir;
		/**
		 * The active profile's name, {@value #MAIN_PROFILE} for a track without profile folders.
		 */
		final String profile;
		/**
		 * Every profile's name, sorted. Always at least one.
		 */
		final List<String> profiles;
		/**
		 * Whether the track's folder holds profile folders, rather than its songs directly.
		 */
		final boolean layered;
		private final List<Long> modified;

		Entry(String track, List<Path> files, List<Long> modified, boolean shuffle, BossMode bossMode,
			Map<String, String> slotSpecs, Map<String, String> options, Path dir, String profile, List<String> profiles,
			boolean layered)
		{
			this.track = track;
			this.files = Collections.unmodifiableList(files);
			this.modified = modified;
			this.shuffle = shuffle;
			this.bossMode = bossMode;
			this.slotSpecs = Collections.unmodifiableMap(slotSpecs);
			this.options = Collections.unmodifiableMap(options);
			this.dir = dir;
			this.profile = profile;
			this.profiles = Collections.unmodifiableList(profiles);
			this.layered = layered;
		}

		boolean hasSongs()
		{
			return !files.isEmpty();
		}

		/**
		 * {@link #MODE_IN_ORDER}, {@link #MODE_SHUFFLE} or a boss mode's id.
		 */
		String mode()
		{
			return bossMode != null ? bossMode.id() : shuffle ? MODE_SHUFFLE : MODE_IN_ORDER;
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
				&& shuffle == other.shuffle && bossMode == other.bossMode && slotSpecs.equals(other.slotSpecs)
				&& options.equals(other.options) && profile.equals(other.profile) && profiles.equals(other.profiles);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(track, files, modified, shuffle, bossMode, slotSpecs, options, profile, profiles);
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
			// A track whose active profile is empty still shows, so another profile can be picked
			if (entry.hasSongs() || entry.layered)
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
		String track = dir != null ? dir.getFileName().toString() : stripExtension(single.getFileName().toString());
		List<Path> root = new ArrayList<>();
		if (single != null)
		{
			root.add(single);
		}
		List<Path> profileDirs = new ArrayList<>();
		if (dir != null)
		{
			root.addAll(songs(dir));
			profileDirs = profileDirs(dir);
		}

		List<Path> files = new ArrayList<>();
		Path songsDir = dir;
		String profile = MAIN_PROFILE;
		List<String> profiles = new ArrayList<>();
		if (profileDirs.isEmpty())
		{
			files.addAll(root);
			profiles.add(MAIN_PROFILE);
		}
		else
		{
			for (Path p : profileDirs)
			{
				profiles.add(p.getFileName().toString());
			}
			// Songs left directly in the folder (only if put there by hand) count as part of the Main profile
			if (!root.isEmpty() && find(profiles, MAIN_PROFILE) == null)
			{
				profiles.add(MAIN_PROFILE);
				profiles.sort(String.CASE_INSENSITIVE_ORDER);
			}
			profile = activeProfile(dir, profiles, !root.isEmpty());
			if (profile.equalsIgnoreCase(MAIN_PROFILE))
			{
				files.addAll(root);
			}
			Path active = findProfileDir(dir, profile);
			if (active != null)
			{
				files.addAll(songs(active));
				songsDir = active;
			}
		}

		List<Long> modified = new ArrayList<>(files.size());
		for (Path file : files)
		{
			modified.add(Files.getLastModifiedTime(file).toMillis());
		}
		BossMode bossMode = songsDir == null ? null : bossMode(songsDir, track);
		boolean shuffle = bossMode == null && songsDir != null && Files.exists(songsDir.resolve(SHUFFLE_MARKER));
		Map<String, String> slotSpecs = songsDir == null ? Map.of() : readSettings(songsDir.resolve(SLOTS_FILE));
		Map<String, String> options = songsDir == null ? Map.of() : readSettings(songsDir.resolve(OPTIONS_FILE));
		return new Entry(track, files, modified, shuffle, bossMode, slotSpecs, options, songsDir, profile, profiles,
			!profileDirs.isEmpty());
	}

	private static Map<String, String> readSettings(Path file)
	{
		if (!Files.isRegularFile(file))
		{
			return Map.of();
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			props.load(reader);
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.warn("Couldn't read {}", file, e);
			return Map.of();
		}
		Map<String, String> settings = new HashMap<>();
		for (String key : props.stringPropertyNames())
		{
			settings.put(key, props.getProperty(key));
		}
		return settings;
	}

	/**
	 * Sets ({@code value} non-null) or clears one setting in a settings file, deleting the file once it's empty.
	 */
	private static void writeSetting(Path file, String key, String value) throws IOException
	{
		Map<String, String> settings = new HashMap<>(readSettings(file));
		if (value == null)
		{
			settings.remove(key);
		}
		else
		{
			settings.put(key, value);
		}
		if (settings.isEmpty())
		{
			Files.deleteIfExists(file);
			return;
		}
		Properties props = new Properties();
		props.putAll(settings);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
		{
			props.store(writer, null);
		}
	}

	/**
	 * Sets which boss mode slots a song plays in, e.g. "1-4" or "1, 2, 4". Null or blank goes back to its position.
	 *
	 * @throws IOException with a message for players if the spec isn't valid
	 */
	synchronized void setSlotSpec(String track, Path song, String spec) throws IOException
	{
		Entry entry = get(track);
		if (entry == null || entry.dir == null || !entry.files.contains(song))
		{
			return;
		}
		String clean = null;
		if (spec != null && !spec.isBlank())
		{
			try
			{
				clean = SlotPlan.normalize(spec);
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException(e.getMessage());
			}
		}
		writeSetting(entry.dir.resolve(SLOTS_FILE), songName(song), clean);
		rescan();
	}

	/**
	 * Sets one of the active profile's boss mode options.
	 */
	synchronized void setOption(String track, String key, String value) throws IOException
	{
		Entry entry = get(track);
		if (entry == null || entry.dir == null)
		{
			return;
		}
		writeSetting(entry.dir.resolve(OPTIONS_FILE), key, value);
		rescan();
	}

	/**
	 * The boss mode a folder's {@value #MODE_MARKER} file names, if it's one this track has.
	 */
	private static BossMode bossMode(Path dir, String track)
	{
		Path marker = dir.resolve(MODE_MARKER);
		if (!Files.isRegularFile(marker))
		{
			return null;
		}
		try
		{
			BossMode mode = BossMode.byId(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim());
			return mode != null && mode.appliesTo(track) ? mode : null;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/**
	 * The profile the track's {@value #PROFILE_MARKER} file names, or a sensible one if it names none of them.
	 */
	private static String activeProfile(Path dir, List<String> profiles, boolean rootSongs)
	{
		String wanted = null;
		try
		{
			wanted = new String(Files.readAllBytes(dir.resolve(PROFILE_MARKER)), StandardCharsets.UTF_8).trim();
		}
		catch (IOException e)
		{
			// No marker yet
		}
		String found = wanted == null ? null : find(profiles, wanted);
		if (found != null)
		{
			return found;
		}
		String main = find(profiles, MAIN_PROFILE);
		return rootSongs && main != null ? main : profiles.get(0);
	}

	private static String find(List<String> names, String name)
	{
		for (String n : names)
		{
			if (n.equalsIgnoreCase(name))
			{
				return n;
			}
		}
		return null;
	}

	/**
	 * A track folder's profile folders, sorted by name.
	 */
	private static List<Path> profileDirs(Path dir) throws IOException
	{
		List<Path> dirs = new ArrayList<>();
		if (!Files.isDirectory(dir))
		{
			return dirs;
		}
		try (DirectoryStream<Path> children = Files.newDirectoryStream(dir))
		{
			for (Path child : children)
			{
				if (!child.getFileName().toString().startsWith(".") && Files.isDirectory(child))
				{
					dirs.add(child);
				}
			}
		}
		dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
		return dirs;
	}

	private static Path findProfileDir(Path dir, String name) throws IOException
	{
		for (Path p : profileDirs(dir))
		{
			if (p.getFileName().toString().equalsIgnoreCase(name))
			{
				return p;
			}
		}
		return null;
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
			try
			{
				verifyPlayable(staged);
			}
			catch (IOException e)
			{
				// Name the player's file, not the temporary copy
				String message = String.valueOf(e.getMessage()).replace(staged.getFileName().toString(), source.getFileName().toString());
				throw new IOException(message, e);
			}

			Entry old = get(track);
			if (old != null && old.layered)
			{
				// With profiles, songs only ever go into the active profile's folder
				materialize(track);
				rescan();
				old = get(track);
			}
			boolean layered = old != null && old.layered;
			if (old != null && !add)
			{
				// Replacing only clears the active profile; the others stay
				release(old);
				deletePlaylist(old.dir);
				if (!layered)
				{
					for (String other : EXTENSIONS)
					{
						Files.deleteIfExists(DIR.resolve(stem + other));
					}
				}
			}

			Path dir = layered ? old.dir : DIR.resolve(stem);
			Files.createDirectories(dir);
			// A single replacement file becomes the first song. Its song name isn't known, so it keeps the track's.
			for (String other : layered ? List.<String>of() : EXTENSIONS)
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
		writeSetting(dir.resolve(SLOTS_FILE), songName(file), null);
		// An emptied playlist goes, but an emptied profile stays, playing the game's music until it gets songs
		if (DIR.equals(dir.getParent()) && songs(dir).isEmpty() && profileDirs(dir).isEmpty())
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
		setMode(track, shuffle ? MODE_SHUFFLE : MODE_IN_ORDER);
	}

	/**
	 * Sets how the active profile plays: {@link #MODE_IN_ORDER}, {@link #MODE_SHUFFLE} or a boss mode's id.
	 */
	synchronized void setMode(String track, String mode) throws IOException
	{
		Entry entry = get(track);
		if (entry == null)
		{
			return;
		}
		if (entry.dir == null)
		{
			throw new IOException(track + " is a single file, add another song to it first");
		}
		BossMode bossMode = BossMode.byId(mode);
		if (bossMode != null && !bossMode.appliesTo(track))
		{
			throw new IOException(bossMode.name() + " mode is only for " + bossMode.track());
		}
		if (bossMode == null && !mode.equals(MODE_SHUFFLE) && !mode.equals(MODE_IN_ORDER))
		{
			throw new IOException("unknown mode " + mode);
		}

		Path shuffle = entry.dir.resolve(SHUFFLE_MARKER);
		Path marker = entry.dir.resolve(MODE_MARKER);
		if (bossMode != null)
		{
			// Leave any shuffle marker, so switching back to a normal mode restores it
			Files.write(marker, bossMode.id().getBytes(StandardCharsets.UTF_8));
		}
		else
		{
			Files.deleteIfExists(marker);
			if (mode.equals(MODE_SHUFFLE))
			{
				if (!Files.exists(shuffle))
				{
					Files.createFile(shuffle);
				}
			}
			else
			{
				Files.deleteIfExists(shuffle);
			}
		}
		rescan();
	}

	/**
	 * Removes a track's replacement, with all of its profiles.
	 */
	synchronized void remove(String track) throws IOException
	{
		Entry entry = get(track);
		if (entry != null)
		{
			release(entry);
		}
		deleteTrack(track);
		rescan();
	}

	synchronized int removeAll() throws IOException
	{
		engine.release(null);
		for (Entry entry : entries.values())
		{
			deleteTrack(entry.track);
		}
		int removed = entries.size();
		rescan();
		return removed;
	}

	private static void deleteTrack(String track) throws IOException
	{
		String stem = fileStem(track);
		Path dir = DIR.resolve(stem);
		for (Path profile : profileDirs(dir))
		{
			deletePlaylist(profile);
		}
		Files.deleteIfExists(dir.resolve(PROFILE_MARKER));
		deletePlaylist(dir);
		for (String ext : EXTENSIONS)
		{
			Files.deleteIfExists(DIR.resolve(stem + ext));
		}
	}

	/**
	 * Adds an empty profile to a track and makes it the active one, so the game's music plays until it gets songs.
	 * The track's current songs become its {@value #MAIN_PROFILE} profile if it had none.
	 *
	 * @return the profile's name as saved
	 */
	synchronized String createProfile(String track, String name) throws IOException
	{
		String clean = profileName(name);
		materialize(track);
		Path dir = DIR.resolve(fileStem(track));
		if (findProfileDir(dir, clean) != null)
		{
			throw new IOException(track + " already has a profile called " + clean);
		}
		Files.createDirectories(dir.resolve(clean));
		writeActiveProfile(dir, clean);
		rescan();
		return clean;
	}

	synchronized void switchProfile(String track, String name) throws IOException
	{
		materialize(track);
		Path dir = DIR.resolve(fileStem(track));
		Path profile = findProfileDir(dir, name);
		if (profile == null)
		{
			throw new IOException(track + " has no profile called " + name);
		}
		writeActiveProfile(dir, profile.getFileName().toString());
		rescan();
	}

	/**
	 * @return the profile's new name as saved
	 */
	synchronized String renameProfile(String track, String from, String to) throws IOException
	{
		String clean = profileName(to);
		materialize(track);
		Path dir = DIR.resolve(fileStem(track));
		Path source = findProfileDir(dir, from);
		if (source == null)
		{
			throw new IOException(track + " has no profile called " + from);
		}
		Path existing = findProfileDir(dir, clean);
		if (existing != null && !existing.equals(source))
		{
			throw new IOException(track + " already has a profile called " + clean);
		}
		boolean active = currentProfile(dir).equalsIgnoreCase(from);
		String keepActive = currentProfile(dir);

		// The playing song's file is open, and Windows can't rename a folder with an open file in it
		for (Path song : songs(source))
		{
			engine.release(song);
		}
		// Through a temporary name, so changing only the case of a name works on Windows too
		Path temp = dir.resolve(TEMP_PREFIX + System.nanoTime());
		Files.move(source, temp);
		Files.move(temp, dir.resolve(clean));
		writeActiveProfile(dir, active ? clean : keepActive);
		rescan();
		return clean;
	}

	/**
	 * Deletes one profile and its songs. Deleting the last one removes the track's replacement.
	 */
	synchronized void deleteProfile(String track, String name) throws IOException
	{
		materialize(track);
		Path dir = DIR.resolve(fileStem(track));
		Path profile = findProfileDir(dir, name);
		if (profile == null)
		{
			throw new IOException(track + " has no profile called " + name);
		}
		String active = currentProfile(dir);
		for (Path song : songs(profile))
		{
			engine.release(song);
		}
		deletePlaylist(profile);

		List<Path> left = profileDirs(dir);
		if (left.isEmpty())
		{
			Files.deleteIfExists(dir.resolve(PROFILE_MARKER));
			deletePlaylist(dir);
		}
		else
		{
			writeActiveProfile(dir, active.equalsIgnoreCase(name) ? left.get(0).getFileName().toString() : active);
		}
		rescan();
	}

	/**
	 * Moves songs kept directly in a track's folder, and a single replacement file, into its {@value #MAIN_PROFILE}
	 * profile folder, so that every profile is a folder before profiles are changed.
	 */
	private void materialize(String track) throws IOException
	{
		String stem = fileStem(track);
		Path dir = DIR.resolve(stem);
		List<Path> root = new ArrayList<>();
		for (String ext : EXTENSIONS)
		{
			Path single = DIR.resolve(stem + ext);
			if (Files.isRegularFile(single))
			{
				root.add(single);
			}
		}
		if (Files.isDirectory(dir))
		{
			root.addAll(songs(dir));
		}
		if (root.isEmpty())
		{
			return;
		}

		String active = Files.isDirectory(dir) && !profileDirs(dir).isEmpty() ? currentProfile(dir) : MAIN_PROFILE;
		Path main = findProfileDir(dir, MAIN_PROFILE);
		if (main == null)
		{
			main = dir.resolve(MAIN_PROFILE);
			Files.createDirectories(main);
		}
		for (Path song : root)
		{
			engine.release(song);
			String ext = extension(song.getFileName().toString());
			Files.move(song, main.resolve(nextSongName(main, songName(song), ext)));
		}
		for (String setting : SETTINGS_FILES)
		{
			Path marker = dir.resolve(setting);
			if (Files.exists(marker))
			{
				if (Files.exists(main.resolve(setting)))
				{
					Files.delete(marker);
				}
				else
				{
					Files.move(marker, main.resolve(setting));
				}
			}
		}
		writeActiveProfile(dir, active);
	}

	/**
	 * The active profile of a track folder that has profile folders, read the same way the index reads it.
	 */
	private static String currentProfile(Path dir) throws IOException
	{
		List<String> names = new ArrayList<>();
		for (Path p : profileDirs(dir))
		{
			names.add(p.getFileName().toString());
		}
		return names.isEmpty() ? MAIN_PROFILE : activeProfile(dir, names, !songs(dir).isEmpty());
	}

	private static void writeActiveProfile(Path dir, String name) throws IOException
	{
		Files.write(dir.resolve(PROFILE_MARKER), name.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A profile name made safe to be a folder name.
	 */
	static String profileName(String name) throws IOException
	{
		String clean = fileStem(name == null ? "" : name.trim());
		if (clean.length() > MAX_PROFILE_NAME)
		{
			clean = EDGE_DOTS_SPACES.matcher(clean.substring(0, MAX_PROFILE_NAME)).replaceAll("");
		}
		if (clean.isEmpty())
		{
			throw new IOException("a profile name needs at least one letter or number");
		}
		return clean;
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
		for (String setting : SETTINGS_FILES)
		{
			Files.deleteIfExists(dir.resolve(setting));
		}
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
