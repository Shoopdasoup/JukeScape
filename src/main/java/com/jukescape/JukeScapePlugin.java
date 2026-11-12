package com.jukescape;

import com.google.common.base.Strings;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Provides;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.MidiRequest;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "JukeScape - OSRS Music Replacer",
	description = "Replace music tracks with your own audio files, without frame drops",
	tags = {"music", "replace", "override", "track", "song", "remix", "jukebox"}
)
public class JukeScapePlugin extends Plugin
{
	/**
	 * What the game's own music is turned down to while a replacement plays. At 0 the game drops its track and only
	 * the settings slider script (which plugins aren't allowed to run, it sends a packet) brings it back. At 1 the
	 * track keeps playing inaudibly, so restoring is just setting the volume back.
	 */
	private static final int MUTED_GAME_VOLUME = 1;
	/**
	 * How long after a replacement stops to watch for the game's next song starting, about 6 s. In game it started
	 * within 2 s.
	 */
	private static final int RESTORE_TICKS = 10;
	private static final int MUSIC_TAB = 13;
	private static final int MENU_PAGE_SIZE = 4;
	private static final int NAV_ICON_SIZE = 16;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ChatboxPanelManager chatboxPanelManager;
	@Inject
	private JukeScapeConfig config;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private SpriteManager spriteManager;

	private ExecutorService executor;
	private AudioEngine engine;
	private Library library;
	private volatile JukeScapePanel panel;
	private volatile NavigationButton navButton;
	private final List<ReplacementSource> sources = new CopyOnWriteArrayList<>();
	private volatile float volumeScale;
	private volatile boolean advanced;
	private File lastChooserDir;
	/**
	 * The track whose replacement is playing, for the panel, or null.
	 */
	private volatile String nowPlayingTrack;

	// Client thread state
	private String lastTrack;
	private int lastVersion = -1;
	private Library.Entry active;
	/**
	 * The active boss mode's slot, or -1 when the replacement isn't in a boss mode.
	 */
	private int lastSlot = -1;
	private boolean wasAudible;
	/**
	 * The game's music volume from before we turned it down, or -1 if we haven't.
	 */
	private int savedGameVolume = -1;
	/**
	 * After a replacement stops, the game tick until which the game's music volume keeps being put back as the
	 * game's next song starts, or -1.
	 */
	private int restoreUntilTick = -1;
	private int restoreMidi;
	private boolean trackListDirty = true;

	@Provides
	JukeScapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(JukeScapeConfig.class);
	}

	/**
	 * Lets companion plugins that declare a dependency on JukeScape inject it to add replacement sources.
	 */
	@Override
	protected Module getPublicModule()
	{
		// A provider rather than toInstance, which would make Guice re-inject this plugin's fields in the dependent's
		// injector, where its config isn't bound
		Provider<JukeScapePlugin> self = () -> this;
		return binder -> binder.bind(JukeScapePlugin.class).toProvider(self);
	}

	@Override
	protected void startUp()
	{
		executor = Executors.newCachedThreadPool(r ->
		{
			Thread t = new Thread(r, "jukescape-io");
			t.setDaemon(true);
			return t;
		});
		engine = new AudioEngine(path -> chat("Couldn't play " + path.getFileName() + ", is it a valid opus/ogg/mp3/flac/wav file?"));
		library = new Library(engine);
		loadConfig();

		runIo("read " + Library.DIR, () ->
		{
			library.deleteTempFiles();
			library.rescan();
		});

		// The sidebar button uses the game's own music tab icon, which loads with the game's sprites
		JukeScapePanel panel = new JukeScapePanel(this, library, engine);
		this.panel = panel;
		spriteManager.getSpriteAsync(SpriteID.SideIcons.MUSIC, 0, sprite -> SwingUtilities.invokeLater(() ->
		{
			if (this.panel != panel)
			{
				return; // Shut down while the sprite loaded
			}
			navButton = NavigationButton.builder()
				.tooltip("JukeScape")
				.icon(sprite == null ? Icons.navigation() : ImageUtil.resizeImage(sprite, NAV_ICON_SIZE, NAV_ICON_SIZE, true))
				.priority(8)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
		}));
	}

	@Override
	protected void shutDown()
	{
		panel = null;
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		nowPlayingTrack = null;
		executor.shutdownNow();
		engine.shutdown();
		clientThread.invoke(() ->
		{
			restoreGameMusic();
			setNowPlayingFont(false);
			setTrackListFonts(false);
			active = null;
			lastTrack = null;
			lastVersion = -1;
			wasAudible = false;
			trackListDirty = true;
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (JukeScapeConfig.GROUP.equals(event.getGroup()))
		{
			loadConfig();
		}
	}

	/**
	 * Config getters go through the config manager, so cache the values instead of calling them every tick.
	 */
	private void loadConfig()
	{
		volumeScale = config.volume() / 100f;
		engine.setFadeMs(config.crossfade());
		advanced = config.advanced();
	}

	/**
	 * Whether the panel shows advanced options. Safe from any thread.
	 */
	boolean advanced()
	{
		return advanced;
	}

	void setSlotSpec(String track, Path song, String spec)
	{
		runIo("set which " + (spec == null ? "levels" : "levels " + spec) + " " + Library.songName(song) + " plays on",
			() -> library.setSlotSpec(track, song, spec));
	}

	void setOption(String track, String key, String value)
	{
		runIo("change " + track + "'s settings", () -> library.setOption(track, key, value));
	}

	public void addReplacementSource(ReplacementSource source)
	{
		sources.add(source);
	}

	List<ReplacementSource> sources()
	{
		return sources;
	}

	String nowPlayingTrack()
	{
		return nowPlayingTrack;
	}

	/**
	 * Adds a song to {@code track} from a companion plugin's source, started from the side panel. Sources that can
	 * prompt in dialogs do; the rest prompt in the chatbox, on the client thread. Call on the Swing thread.
	 */
	void addFrom(ReplacementSource source, String track, Component parent)
	{
		if (!source.chooseFromPanel(track, true, parent))
		{
			clientThread.invokeLater(() -> source.choose(track, true));
		}
	}

	public void removeReplacementSource(ReplacementSource source)
	{
		sources.remove(source);
	}

	/**
	 * A path in the replacements folder for a source to download into. Leftovers from interrupted downloads are
	 * deleted on startup.
	 */
	public Path tempPath(String name)
	{
		return Library.DIR.resolve(Library.TEMP_PREFIX + name);
	}

	/**
	 * Makes a downloaded file the replacement for {@code track}, or adds it to the track's playlist, after checking
	 * it plays, and reports the result in chat. Takes ownership of the file, and blocks, so only call it off the
	 * client thread.
	 *
	 * @param name the song's name, for its file name in a playlist
	 */
	public void install(String track, Path file, String name, boolean add) throws IOException
	{
		install(track, file, name, add, true);
	}

	/**
	 * Like {@link #install(String, Path, String, boolean)}, but {@code report} false skips the chat message, for
	 * sources that install many songs in a row and report once at the end.
	 */
	public void install(String track, Path file, String name, boolean add, boolean report) throws IOException
	{
		library.install(track, file, true, name, add);
		if (report)
		{
			reportInstalled(track, name, add);
		}
	}

	private void reportInstalled(String track, String name, boolean add)
	{
		int songs = songCount(track);
		chat(add && songs > 1 ? "Added " + name + " to " + track + " (" + songs + " songs)." : "Replaced " + track + " with " + name + ".");
	}

	/**
	 * How many songs {@code track}'s replacement has, or 0 if it has none.
	 */
	public int songCount(String track)
	{
		Library.Entry entry = library.get(track);
		return entry == null ? 0 : entry.files.size();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			engine.stop();
			nowPlayingTrack = null;
			restoreGameMusic();
			active = null;
			lastTrack = null;
			lastVersion = -1;
			wasAudible = false;
		}
	}

	/**
	 * Runs every client frame, so it only does field reads and comparisons. Everything else happens on change.
	 */
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Widget nowPlaying = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		String track = nowPlaying == null ? lastTrack : nowPlaying.getText();
		int version = library.version();
		boolean changed = false;
		if (version != lastVersion || !Objects.equals(track, lastTrack))
		{
			trackListDirty |= version != lastVersion;
			lastTrack = track;
			lastVersion = version;
			Library.Entry entry = Strings.isNullOrEmpty(track) ? null : library.get(track);
			if (entry != null && !entry.hasSongs())
			{
				entry = null; // An empty profile plays the game's own music
			}
			if (!Objects.equals(entry, active))
			{
				active = entry;
				changed = true;
				setNowPlayingFont(active != null);
			}
		}

		// A boss mode picks one song by how far into the fight the player is. Only a var read, so fine every frame.
		int slot = active != null && active.bossMode != null ? active.bossMode.activeSlot(client, active) : -1;
		changed |= slot != lastSlot;
		lastSlot = slot;

		float volume = effectiveVolume();
		boolean audible = volume > 0;
		if (changed || audible != wasAudible)
		{
			// Like the game, a track starts over when music is unmuted
			if (active != null && audible)
			{
				if (slot >= 0)
				{
					engine.play(SlotPlan.songsFor(slot, active), false);
				}
				else
				{
					engine.play(active.files, active.shuffle);
				}
				nowPlayingTrack = active.track;
			}
			else
			{
				engine.stop();
				nowPlayingTrack = null;
			}
			wasAudible = audible;
		}
		engine.setVolume(volume);

		if (active != null)
		{
			// Keep the game's own track inaudible. Reading the volume gives the player's setting, not what the track
			// is playing at (confirmed in game: it reads 75 straight after turning the track down to 1), and the game
			// puts the track back to the setting whenever its interface changes, like switching side tabs. So turn
			// it down every frame. At 0 the player muted music themselves and there's nothing to turn down.
			int setting = client.getMusicVolume();
			if (setting != 0)
			{
				if (setting != MUTED_GAME_VOLUME)
				{
					savedGameVolume = setting;
				}
				client.setMusicVolume(MUTED_GAME_VOLUME);
			}
			restoreUntilTick = -1;
		}
		else
		{
			restoreGameMusic();
			keepRestoring();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (trackListDirty)
		{
			trackListDirty = !setTrackListFonts(true);
		}
	}

	/**
	 * Boss modes can switch songs on what the game says, ahead of its state updating.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		for (BossMode mode : BossMode.ALL)
		{
			mode.onChatMessage(event, client);
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() == VarClientID.TOPLEVEL_PANEL && client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL) == MUSIC_TAB)
		{
			trackListDirty = true;
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		MenuEntry first = event.getFirstEntry();
		if (first == null)
		{
			return;
		}

		int widgetId = first.getParam1();
		if (widgetId == InterfaceID.Music.JUKEBOX)
		{
			String track = Text.removeTags(first.getTarget());
			if (track.isEmpty())
			{
				return;
			}
			// The last entry added shows at the top
			if (library.get(track) != null)
			{
				addMenuEntry(first, "Playlist", () -> openPlaylistMenu(track));
				addMenuEntry(first, "Add song", () -> openReplaceMenu(track, true));
			}
			addMenuEntry(first, "Replace", () -> openReplaceMenu(track, false));
		}
		else if (widgetId == InterfaceID.Toplevel.STONE13
			|| widgetId == InterfaceID.ToplevelOsrsStretch.STONE13
			|| widgetId == InterfaceID.ToplevelPreEoc.STONE13
			|| widgetId == InterfaceID.ToplevelOsm.STONE13)
		{
			addMenuEntry(first, "Music replacements", this::openLibraryMenu);
		}
	}

	private void addMenuEntry(MenuEntry copyOf, String option, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(copyOf.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> action.run());
	}

	/**
	 * @param add add songs to the track's playlist instead of replacing it
	 */
	private void openReplaceMenu(String track, boolean add)
	{
		if (sources.isEmpty())
		{
			chooseLocalFiles(track, add);
			return;
		}
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput(
			add ? "Add a song to " + track + " from:" : "Replace " + track + " with:");
		for (ReplacementSource source : sources)
		{
			menu.option(source.label(), () -> source.choose(track, add));
		}
		menu.option("A local audio file", () -> chooseLocalFiles(track, add));
		menu.build();
	}

	private void openPlaylistMenu(String track)
	{
		Library.Entry entry = library.get(track);
		if (entry == null)
		{
			return;
		}
		int songs = entry.files.size();
		String profile = entry.profiles.size() > 1 ? " (" + entry.profile + ")" : "";
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput(
			track + profile + ": " + songs + (songs == 1 ? " song" : " songs") + (songs > 1 ? ", " + modeDescription(entry) : ""));
		if (entry.dir != null && (songs > 1 || !BossMode.forTrack(entry.track).isEmpty()))
		{
			menu.option("Play mode", () -> chooseMode(entry));
		}
		if (songs > 1)
		{
			menu.option("Remove a song", () -> chooseSongToRemove(track, entry.files, 0));
		}
		if (entry.profiles.size() > 1)
		{
			menu.option("Switch profile", () -> chooseProfile(track, entry, 0));
		}
		menu.option("New profile", () -> askNewProfile(track));
		menu.option("Remove replacement", () ->
		{
			if (entry.profiles.size() > 1)
			{
				chatboxPanelManager.openTextMenuInput("Delete all " + entry.profiles.size() + " profiles of " + track + " and their songs?")
					.option("Yes, delete them all", () -> removeReplacement(track))
					.option("No", () -> {})
					.build();
			}
			else
			{
				removeReplacement(track);
			}
		});
		menu.build();
	}

	private void chooseMode(Library.Entry entry)
	{
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput("How should " + entry.track + " play?");
		menu.option(modeOption("In order", Library.MODE_IN_ORDER, entry), () -> setMode(entry.track, Library.MODE_IN_ORDER));
		menu.option(modeOption("Shuffle", Library.MODE_SHUFFLE, entry), () -> setMode(entry.track, Library.MODE_SHUFFLE));
		for (BossMode mode : BossMode.forTrack(entry.track))
		{
			menu.option(modeOption(mode.name() + ": " + mode.description(), mode.id(), entry), () -> setMode(entry.track, mode.id()));
		}
		menu.build();
	}

	private static String modeOption(String label, String mode, Library.Entry entry)
	{
		return entry.mode().equals(mode) ? label + " (current)" : label;
	}

	/**
	 * "in order", "shuffled" or the boss mode's name, for menus.
	 */
	static String modeDescription(Library.Entry entry)
	{
		return entry.bossMode != null ? entry.bossMode.name() + " mode" : entry.shuffle ? "shuffled" : "in order";
	}

	void setMode(String track, String mode)
	{
		runIo("change how " + track + " plays", () ->
		{
			library.setMode(track, mode);
			Library.Entry entry = library.get(track);
			if (entry == null)
			{
				return;
			}
			String message = track + " now plays " + modeDescription(entry).replace(" mode", "") + ".";
			if (entry.bossMode != null)
			{
				BossMode boss = entry.bossMode;
				message = track + " now plays in " + boss.name() + " mode: song 1 for " + boss.slotName(boss.firstSlot(entry))
					+ ", song 2 for the next, and so on. The last song covers the rest.";
			}
			chat(message);
		});
	}

	private void removeReplacement(String track)
	{
		runIo("remove " + track, () ->
		{
			library.remove(track);
			chat("Removed the replacement for " + track + ".");
		});
	}

	private void chooseProfile(String track, Library.Entry entry, int offset)
	{
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput("Switch " + track + " to which profile?");
		int end = Math.min(entry.profiles.size(), offset + MENU_PAGE_SIZE);
		for (String name : entry.profiles.subList(offset, end))
		{
			String label = name.equals(entry.profile) ? name + " (playing)" : name;
			menu.option(Text.escapeJagex(truncate(label, 50)), () -> switchProfile(track, name));
		}
		if (end < entry.profiles.size())
		{
			menu.option("More profiles", () -> chooseProfile(track, entry, end));
		}
		menu.build();
	}

	/**
	 * Asks for a name in the chatbox, then adds an empty profile to {@code track} and switches to it.
	 */
	void askNewProfile(String track)
	{
		chatboxPanelManager.openTextInput("Name the new profile for " + track + ":")
			.onDone((String name) ->
			{
				if (!name.isBlank())
				{
					newProfile(track, name);
				}
			})
			.build();
	}

	void newProfile(String track, String name)
	{
		runIo("add a profile to " + track, () ->
		{
			Library.Entry before = library.get(track);
			boolean first = before != null && !before.layered && before.hasSongs();
			String saved = library.createProfile(track, name);
			chat("Switched " + track + " to its new profile " + saved + "."
				+ (first ? " Its songs so far are kept in the profile " + Library.MAIN_PROFILE + "." : "")
				+ " The game's music plays until you add songs to it.");
		});
	}

	void switchProfile(String track, String name)
	{
		runIo("switch " + track + " to " + name, () ->
		{
			library.switchProfile(track, name);
			Library.Entry entry = library.get(track);
			int songs = entry == null ? 0 : entry.files.size();
			chat(track + " now plays its profile " + name + (songs == 0 ? ", which has no songs, so the game's music plays." : "."));
		});
	}

	private void chooseSongToRemove(String track, List<Path> songs, int offset)
	{
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput("Remove which song from " + track + "?");
		int end = Math.min(songs.size(), offset + MENU_PAGE_SIZE);
		for (Path song : songs.subList(offset, end))
		{
			String name = Library.songName(song);
			menu.option(Text.escapeJagex(truncate(name, 50)), () -> runIo("remove " + name, () ->
			{
				library.removeSong(song);
				chat("Removed " + name + " from " + track + ".");
			}));
		}
		if (end < songs.size())
		{
			menu.option("More songs", () -> chooseSongToRemove(track, songs, end));
		}
		menu.build();
	}

	private void openLibraryMenu()
	{
		int count = library.size();
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput("Music replacements (" + count + " tracks)");
		menu.option("Open replacements folder", this::openFolder);
		menu.option("Reload folder", () -> runIo("reload " + Library.DIR, () ->
		{
			library.rescan();
			chat("Loaded " + library.size() + " replacements.");
		}));
		if (count > 0)
		{
			menu.option("Remove all replacements", this::confirmRemoveAll);
		}
		menu.build();
	}

	/**
	 * Lets the player pick one or more files. When replacing, the first replaces the track and the rest are added
	 * after it.
	 */
	void chooseLocalFiles(String track, boolean add)
	{
		SwingUtilities.invokeLater(() ->
		{
			JFileChooser chooser = new JFileChooser(lastChooserDir);
			chooser.setDialogTitle(add ? "Add songs to " + track : "Replace " + track);
			chooser.setMultiSelectionEnabled(true);
			chooser.setFileFilter(new FileNameExtensionFilter("Audio files (opus, ogg, mp3, flac, wav)", "opus", "ogg", "mp3", "flac", "wav"));
			if (chooser.showOpenDialog(client.getCanvas()) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			File[] files = chooser.getSelectedFiles();
			if (files.length == 0)
			{
				return;
			}
			lastChooserDir = files[0].getParentFile();
			List<Path> paths = new ArrayList<>();
			for (File file : files)
			{
				paths.add(file.toPath());
			}
			installFiles(track, paths, add);
		});
	}

	/**
	 * Copies audio files in as songs, skipping (and reporting) any that won't play. When replacing, the first one
	 * that works replaces the track and the rest are added after it.
	 */
	void installFiles(String track, List<Path> files, boolean add)
	{
		runIo((add ? "add songs to " : "replace ") + track, () ->
		{
			int installed = 0;
			for (Path file : files)
			{
				try
				{
					library.install(track, file, false, Library.songName(file), add || installed > 0);
					installed++;
				}
				catch (InterruptedIOException e)
				{
					throw e;
				}
				catch (IOException e)
				{
					log.warn("Couldn't add {} to {}", file, track, e);
					chat("Couldn't add " + file.getFileName() + ": " + e.getMessage());
				}
			}
			if (installed == 0)
			{
				return;
			}
			if (files.size() == 1)
			{
				reportInstalled(track, Library.songName(files.get(0)), add);
			}
			else if (add)
			{
				chat("Added " + installed + (installed == 1 ? " song" : " songs") + " to " + track + " (" + songCount(track) + " songs).");
			}
			else
			{
				chat("Replaced " + track + " with " + installed + (installed == 1 ? " song." : " songs."));
			}
		});
	}

	void openFolder()
	{
		runIo("open " + Library.DIR, () ->
		{
			Files.createDirectories(Library.DIR);
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
			{
				Desktop.getDesktop().open(Library.DIR.toFile());
			}
			else
			{
				chat("Replacements are in " + Library.DIR);
			}
			chat("Name opus/ogg/mp3/flac/wav files exactly like the track (e.g. Sea Shanty 2.opus), or put several in a folder named "
				+ "like the track for a playlist, then use Reload folder.");
		});
	}

	private void confirmRemoveAll()
	{
		chatboxPanelManager.openTextMenuInput("Delete all " + library.size() + " replacement files?")
			.option("Yes, delete them", () -> runIo("remove replacements", () ->
				chat("Removed " + library.removeAll() + " replacements.")))
			.option("No", () -> { })
			.build();
	}

	private float effectiveVolume()
	{
		float master = client.getVarpValue(VarPlayerID.OPTION_MASTER_VOLUME) / 100f;
		float music = client.getVarpValue(VarPlayerID.OPTION_MUSIC) / 100f;
		float volume = master * music;
		// Squared because loudness is perceived logarithmically
		return Math.max(0f, Math.min(1f, volume * volume * volumeScale));
	}

	private void restoreGameMusic()
	{
		if (savedGameVolume < 0)
		{
			return;
		}
		// The player's setting still reads back while the track is turned down, so always put the track back to it,
		// picking up any change made in the settings meanwhile. Only if it somehow reads as turned down, use the
		// setting from before.
		int setting = client.getMusicVolume();
		client.setMusicVolume(setting == MUTED_GAME_VOLUME ? savedGameVolume : setting);
		savedGameVolume = -1;
		restoreUntilTick = client.getTickCount() + RESTORE_TICKS;
		restoreMidi = midiPlaying();
	}

	/**
	 * Setting the game's music volume only reaches the song already playing (confirmed in game), but when a
	 * replacement stops, the game's next song starts a few seconds later, after the old one fades out, and would play
	 * as quietly as the replacement left it. So for a while, put the volume back again whenever the game's songs
	 * change, and once more at the end.
	 */
	private void keepRestoring()
	{
		if (restoreUntilTick < 0)
		{
			return;
		}
		int midi = midiPlaying();
		boolean last = client.getTickCount() > restoreUntilTick;
		if (midi != restoreMidi || last)
		{
			restoreMidi = midi;
			client.setMusicVolume(client.getMusicVolume());
		}
		if (last)
		{
			restoreUntilTick = -1;
		}
	}

	/**
	 * Which of the game's songs are playing, as a hash: only compared, to see when they change.
	 */
	private int midiPlaying()
	{
		int hash = 1;
		for (MidiRequest request : client.getActiveMidiRequests())
		{
			hash = hash * 31 + request.getArchiveId() * 2 + (request.isJingle() ? 1 : 0);
		}
		return hash;
	}

	private void setNowPlayingFont(boolean replaced)
	{
		Widget nowPlaying = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		if (nowPlaying != null)
		{
			nowPlaying.setFontId(replaced ? FontID.BOLD_12 : FontID.PLAIN_12);
		}
	}

	/**
	 * Bolds replaced tracks in the music list. Only runs when the list or the library changes.
	 *
	 * @return false if the music list isn't loaded
	 */
	private boolean setTrackListFonts(boolean highlight)
	{
		Widget list = client.getWidget(InterfaceID.Music.JUKEBOX);
		if (list == null)
		{
			return false;
		}
		Widget[] rows = list.getDynamicChildren();
		if (rows != null)
		{
			for (Widget row : rows)
			{
				Library.Entry entry = highlight ? library.get(row.getText()) : null;
				row.setFontId(entry != null && entry.hasSongs() ? FontID.BOLD_12 : FontID.PLAIN_12);
			}
		}
		return true;
	}

	private static String truncate(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	interface IoTask
	{
		void run() throws Exception;
	}

	/**
	 * Runs disk and network work off the client thread, reporting failures in chat.
	 */
	void runIo(String what, IoTask task)
	{
		executor.execute(() ->
		{
			try
			{
				task.run();
			}
			catch (InterruptedIOException e)
			{
				log.debug("Cancelled: {}", what);
			}
			catch (Exception e)
			{
				log.warn("Failed to {}", what, e);
				chat("Failed to " + what + ": " + e.getMessage());
			}
		});
	}

	/**
	 * Shows a message in the chatbox. Safe to call from any thread.
	 */
	public void chat(String message)
	{
		clientThread.invokeLater(() ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[JukeScape] " + Text.escapeJagex(message), null));
	}
}
