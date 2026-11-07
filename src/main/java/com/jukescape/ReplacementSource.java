package com.jukescape;

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
	 * Called on the client thread when the player picks this source for {@code track}. Pass {@code add} on to
	 * {@link JukeScapePlugin#install}.
	 *
	 * @param add whether the song gets added to the track's playlist rather than replacing it
	 */
	void choose(String track, boolean add);
}
