package com.jukescape;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * Sidebar panel listing replaced tracks and their playlists. Everything here runs on the Swing thread and only reads
 * the library's immutable index; changes go through the plugin's IO threads.
 */
final class JukeScapePanel extends PluginPanel
{
	private static final int REFRESH_MS = 500;

	private final JukeScapePlugin plugin;
	private final Library library;
	private final AudioEngine engine;

	private final JLabel nowTrack = new JLabel();
	private final JLabel nowSong = new JLabel();
	private final JLabel skip = iconButton(Icons::skip, "Next song");
	private final IconTextField search = new IconTextField();
	private final JPanel trackList = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final Timer refresh = new Timer(REFRESH_MS, e -> refresh());
	private final Set<String> expanded = new HashSet<>();

	// What the list was last built from
	private int shownVersion = -1;
	private String shownTrack;
	private Path shownSong;

	JukeScapePanel(JukeScapePlugin plugin, Library library, AudioEngine engine)
	{
		this.plugin = plugin;
		this.library = library;
		this.engine = engine;

		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		trackList.setOpaque(false);

		JPanel top = new JPanel(new DynamicGridLayout(0, 1, 0, 8));
		top.setOpaque(false);
		top.add(nowPlaying());
		search.setIcon(IconTextField.Icon.SEARCH);
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		top.add(search);

		add(top, BorderLayout.NORTH);
		add(trackList, BorderLayout.CENTER);
		add(footer(), BorderLayout.SOUTH);
	}

	@Override
	public void onActivate()
	{
		rebuild();
		refresh.start();
	}

	@Override
	public void onDeactivate()
	{
		refresh.stop();
	}

	private JPanel nowPlaying()
	{
		JPanel box = new JPanel(new BorderLayout(6, 0));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(6, 8, 6, 8));

		JPanel text = new JPanel(new DynamicGridLayout(2, 1, 0, 2));
		text.setOpaque(false);
		nowTrack.setFont(FontManager.getRunescapeBoldFont());
		nowSong.setFont(FontManager.getRunescapeSmallFont());
		nowSong.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.add(nowTrack);
		text.add(nowSong);
		box.add(text, BorderLayout.CENTER);

		onClick(skip, engine::skip);
		box.add(skip, BorderLayout.EAST);
		showNowPlaying(null, null);
		return box;
	}

	private void showNowPlaying(String track, Path song)
	{
		boolean playing = track != null && song != null;
		nowTrack.setText(playing ? track : "No replacement playing");
		nowTrack.setForeground(playing ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		nowSong.setText(playing ? Library.songName(song) : " ");
		nowSong.setToolTipText(playing ? Library.songName(song) : null);
		skip.setVisible(playing);
	}

	/**
	 * Polls for changes while the panel is open, which is cheaper than having the client thread push them.
	 */
	private void refresh()
	{
		String track = plugin.nowPlayingTrack();
		Path song = track == null ? null : engine.currentSong();
		if (library.version() != shownVersion || !Objects.equals(track, shownTrack) || !Objects.equals(song, shownSong))
		{
			rebuild();
		}
	}

	private void rebuild()
	{
		shownVersion = library.version();
		shownTrack = plugin.nowPlayingTrack();
		shownSong = shownTrack == null ? null : engine.currentSong();
		showNowPlaying(shownTrack, shownSong);

		String filter = search.getText().trim().toLowerCase(Locale.ROOT);
		List<Library.Entry> entries = library.all();
		trackList.removeAll();
		for (Library.Entry entry : entries)
		{
			if (matches(entry, filter))
			{
				trackList.add(trackPanel(entry, !filter.isEmpty()));
			}
		}
		if (trackList.getComponentCount() == 0)
		{
			trackList.add(message(entries.isEmpty()
				? "No replacements yet. Right-click a track in the music tab and choose Replace."
				: "No tracks or songs match."));
		}
		trackList.revalidate();
		trackList.repaint();
	}

	private static boolean matches(Library.Entry entry, String filter)
	{
		if (filter.isEmpty() || entry.track.toLowerCase(Locale.ROOT).contains(filter))
		{
			return true;
		}
		for (Path song : entry.files)
		{
			if (Library.songName(song).toLowerCase(Locale.ROOT).contains(filter))
			{
				return true;
			}
		}
		return false;
	}

	private JPanel trackPanel(Library.Entry entry, boolean searching)
	{
		String key = entry.track.toLowerCase(Locale.ROOT);
		boolean open = searching || expanded.contains(key);
		boolean playing = entry.track.equalsIgnoreCase(shownTrack);

		JPanel box = new JPanel(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel header = new JPanel(new DynamicGridLayout(2, 1, 0, 2));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(new EmptyBorder(6, 8, 6, 8));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel name = new JLabel(entry.track);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(playing ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		int songs = entry.files.size();
		String profile = entry.profiles.size() > 1 ? entry.profile + ": " : "";
		String count = songs == 0 ? "no songs, game music" : songs + (songs == 1 ? " song" : " songs");
		JLabel info = new JLabel(profile + count + (songs > 1 && entry.shuffle ? ", shuffled" : ""));
		info.setFont(FontManager.getRunescapeSmallFont());
		info.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(name);
		header.add(info);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!expanded.remove(key))
				{
					expanded.add(key);
				}
				rebuild();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		box.add(header, BorderLayout.NORTH);

		if (open)
		{
			JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
			body.setOpaque(false);
			body.setBorder(new EmptyBorder(0, 8, 8, 8));
			body.add(profileButton(entry));
			for (int i = 0; i < songs; i++)
			{
				body.add(songRow(entry, i, playing));
			}
			if (songs == 0)
			{
				JLabel empty = message("No songs, so the game's music plays.");
				empty.setToolTipText("Add songs to this profile, or switch to another one");
				body.add(empty);
			}
			body.add(actions(entry));
			box.add(body, BorderLayout.CENTER);
		}
		return box;
	}

	private JPanel songRow(Library.Entry entry, int index, boolean trackPlaying)
	{
		Path song = entry.files.get(index);
		String name = Library.songName(song);

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		JLabel label = new JLabel((index + 1) + ". " + name);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(trackPlaying && song.equals(shownSong) ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		label.setToolTipText(name);
		row.add(label, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		buttons.setOpaque(false);
		// Hand-dropped single files have no folder to reorder in
		if (entry.dir != null && entry.files.size() > 1)
		{
			JLabel up = iconButton(Icons::up, "Move up");
			JLabel down = iconButton(Icons::down, "Move down");
			up.setEnabled(index > 0);
			down.setEnabled(index < entry.files.size() - 1);
			if (index > 0)
			{
				onClick(up, () -> plugin.runIo("move " + name, () -> library.moveSong(song, -1)));
			}
			if (index < entry.files.size() - 1)
			{
				onClick(down, () -> plugin.runIo("move " + name, () -> library.moveSong(song, 1)));
			}
			buttons.add(up);
			buttons.add(down);
		}
		JLabel remove = iconButton(Icons::remove, "Remove this song");
		onClick(remove, () ->
		{
			if (confirm("Remove " + name + " from " + entry.track + "? This deletes the file."))
			{
				plugin.runIo("remove " + name, () -> library.removeSong(song));
			}
		});
		buttons.add(remove);
		row.add(buttons, BorderLayout.EAST);
		return row;
	}

	/**
	 * Shows the active profile, and opens a menu to switch to another or create, rename or delete one.
	 */
	private JButton profileButton(Library.Entry entry)
	{
		JButton button = button("Profile: " + entry.profile);
		button.setToolTipText("Each profile is its own playlist for this track. Click to switch, add, rename or delete profiles.");
		button.addActionListener(e ->
		{
			JPopupMenu menu = new JPopupMenu();
			ButtonGroup group = new ButtonGroup();
			for (String name : entry.profiles)
			{
				JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, name.equals(entry.profile));
				group.add(item);
				if (!name.equals(entry.profile))
				{
					item.addActionListener(ev -> plugin.switchProfile(entry.track, name));
				}
				menu.add(item);
			}
			menu.addSeparator();

			JMenuItem create = new JMenuItem("New profile...");
			create.addActionListener(ev ->
			{
				String name = ask("Name the new profile for " + entry.track + ". It starts empty, so the game's music plays until you add songs.", "");
				if (name != null)
				{
					plugin.newProfile(entry.track, name);
				}
			});
			menu.add(create);

			JMenuItem rename = new JMenuItem("Rename " + entry.profile + "...");
			rename.addActionListener(ev ->
			{
				String name = ask("New name for the profile " + entry.profile + ":", entry.profile);
				if (name != null && !name.equals(entry.profile))
				{
					plugin.runIo("rename " + entry.profile, () ->
					{
						String saved = library.renameProfile(entry.track, entry.profile, name);
						plugin.chat("Renamed " + entry.track + "'s profile " + entry.profile + " to " + saved + ".");
					});
				}
			});
			menu.add(rename);

			if (entry.profiles.size() > 1)
			{
				JMenuItem delete = new JMenuItem("Delete " + entry.profile + "...");
				delete.addActionListener(ev ->
				{
					int songs = entry.files.size();
					if (confirm("Delete the profile " + entry.profile + " from " + entry.track + "?"
						+ (songs == 0 ? "" : " This deletes " + (songs == 1 ? "its song file." : "its " + songs + " song files."))))
					{
						plugin.runIo("delete " + entry.profile, () ->
						{
							library.deleteProfile(entry.track, entry.profile);
							plugin.chat("Deleted " + entry.track + "'s profile " + entry.profile + ".");
						});
					}
				});
				menu.add(delete);
			}
			menu.show(button, 0, button.getHeight());
		});
		return button;
	}

	/**
	 * @return the text entered, or null if cancelled or left blank
	 */
	private String ask(String question, String initial)
	{
		Object answer = JOptionPane.showInputDialog(this, question, "JukeScape", JOptionPane.PLAIN_MESSAGE, null, null, initial);
		return answer == null || answer.toString().isBlank() ? null : answer.toString().trim();
	}

	private JPanel actions(Library.Entry entry)
	{
		boolean canShuffle = entry.dir != null && entry.files.size() > 1;
		JPanel actions = new JPanel(new GridLayout(1, canShuffle ? 3 : 2, 4, 0));
		actions.setOpaque(false);
		actions.setBorder(new EmptyBorder(4, 0, 0, 0));

		JButton add = button("Add");
		add.setToolTipText("Add songs to this track's playlist");
		add.addActionListener(e ->
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem files = new JMenuItem("Audio files...");
			files.addActionListener(ev -> plugin.chooseLocalFiles(entry.track, true));
			menu.add(files);
			for (ReplacementSource source : plugin.sources())
			{
				JMenuItem item = new JMenuItem(source.label() + "...");
				item.addActionListener(ev -> plugin.addFrom(source, entry.track));
				menu.add(item);
			}
			menu.show(add, 0, add.getHeight());
		});
		actions.add(add);

		if (canShuffle)
		{
			boolean shuffle = !entry.shuffle;
			JButton order = button(entry.shuffle ? "In order" : "Shuffle");
			order.setToolTipText(entry.shuffle ? "Play the songs in order" : "Play the songs in random order");
			order.addActionListener(e -> plugin.runIo("change the order of " + entry.track,
				() -> library.setShuffle(entry.track, shuffle)));
			actions.add(order);
		}

		JButton remove = button("Remove");
		remove.setToolTipText("Remove this track's replacement");
		remove.addActionListener(e ->
		{
			int songs = entry.files.size();
			String what = entry.profiles.size() > 1
				? "all " + entry.profiles.size() + " of its profiles and their song files."
				: songs == 1 ? "its song file." : "all " + songs + " of its song files.";
			if (confirm("Remove the replacement for " + entry.track + "? This deletes " + what))
			{
				plugin.runIo("remove " + entry.track, () -> library.remove(entry.track));
			}
		});
		actions.add(remove);
		return actions;
	}

	private JPanel footer()
	{
		JPanel footer = new JPanel(new GridLayout(1, 3, 4, 0));
		footer.setOpaque(false);
		JButton folder = footerButton("Folder");
		folder.setToolTipText("Open the replacements folder");
		folder.addActionListener(e -> plugin.openFolder());
		JButton reload = footerButton("Reload");
		reload.setToolTipText("Pick up files you added or changed in the folder");
		reload.addActionListener(e -> plugin.runIo("reload " + Library.DIR, library::rescan));
		JButton removeAll = footerButton("Remove all");
		removeAll.setToolTipText("Delete every replacement");
		removeAll.addActionListener(e ->
		{
			if (confirm("Delete all " + library.size() + " replacements and their song files?"))
			{
				plugin.runIo("remove replacements", () -> plugin.chat("Removed " + library.removeAll() + " replacements."));
			}
		});
		footer.add(folder);
		footer.add(reload);
		footer.add(removeAll);
		return footer;
	}

	private static JButton footerButton(String text)
	{
		return button(text, ColorScheme.DARKER_GRAY_COLOR, ColorScheme.DARKER_GRAY_HOVER_COLOR);
	}

	private boolean confirm(String question)
	{
		return JOptionPane.showConfirmDialog(this, question, "JukeScape", JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
	}

	private static JLabel message(String text)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(4, 2, 4, 2));
		label.setHorizontalAlignment(SwingConstants.LEFT);
		return label;
	}

	private static JButton button(String text)
	{
		return button(text, ColorScheme.DARK_GRAY_COLOR, ColorScheme.DARK_GRAY_HOVER_COLOR);
	}

	/**
	 * @param background a shade that stands out from what the button sits on
	 */
	private static JButton button(String text, Color background, Color hover)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 2));
		button.setBackground(background);
		button.setForeground(ColorScheme.TEXT_COLOR);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setBackground(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setBackground(background);
			}
		});
		return button;
	}

	/**
	 * An icon that highlights on hover, like the small buttons in RuneLite's own panels.
	 */
	private static JLabel iconButton(Function<Boolean, javax.swing.ImageIcon> icon, String tooltip)
	{
		JLabel label = new JLabel(icon.apply(false));
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (label.isEnabled())
				{
					label.setIcon(icon.apply(true));
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(icon.apply(false));
			}
		});
		return label;
	}

	private static void onClick(JLabel label, Runnable action)
	{
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (label.isEnabled())
				{
					action.run();
				}
			}
		});
	}
}
