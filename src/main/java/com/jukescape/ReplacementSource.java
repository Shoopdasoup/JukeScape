package com.jukescape;

import java.awt.Component;

/**
 * Another way to pick a replacement, offered in a track's Replace and Add song menus next to local files. Companion
 * plugins register these with {@link JukeScapePlugin#addReplacementSource}.
 */
public interface ReplacementSource
{
	/**
	 * Menu text, e.g. "A local audio file".
	 */
	String label();

	/**
	 * Called on the client thread when the player picks this source for {@code track} in the game. Prompts belong in
	 * the chatbox. Pass {@code add} on to {@link JukeScapePlugin#install}.
	 *
	 * @param add whether the song gets added to the track's playlist rather than replacing it
	 */
	void choose(String track, boolean add);

	/**
	 * Like {@link #choose}, but started from the side panel, so prompts should be dialogs over {@code parent} rather
	 * than the chatbox, which would mean clicking back into the game. Called on the Swing thread.
	 *
	 * @return false if this source can only prompt in the chatbox, in which case {@link #choose} is used
	 */
	default boolean chooseFromPanel(String track, boolean add, Component parent)
	{
		return false;
	}

	/**
	 * Whether this source can add what {@code text} describes, for text dropped on a track in the side panel (a link
	 * dragged from a browser, say).
	 */
	default boolean accepts(String text)
	{
		return false;
	}

	/**
	 * Adds what {@code text} describes to {@code track}'s playlist. Called on the Swing thread, only after
	 * {@link #accepts} returned true; prompt in dialogs over {@code parent}.
	 */
	default void drop(String track, String text, Component parent)
	{
	}
}
