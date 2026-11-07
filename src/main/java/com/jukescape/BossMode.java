package com.jukescape;

import java.util.List;
import net.runelite.api.Client;

/**
 * A play mode made for one boss fight: the track's songs are split into named slots (delve levels, phases, ...), and
 * the song for whichever slot the fight is in plays. Song N plays in slot N; if there are fewer songs than slots, the
 * last song covers the rest.
 * <p>
 * Each boss gets its own implementation, listed in {@link #ALL}. Everything boss-specific stays in that class.
 */
interface BossMode
{
	List<BossMode> ALL = List.of(new DelveMode());

	/**
	 * Saved in a profile's {@value Library#MODE_MARKER} file. Never change it once released.
	 */
	String id();

	/**
	 * Shown in menus, e.g. "Delve".
	 */
	String name();

	/**
	 * What it does, in a few words for menus, e.g. "a song for each delve level".
	 */
	String description();

	/**
	 * The music track the fight plays, which this mode is offered on.
	 */
	String track();

	/**
	 * The slots' names in order, e.g. "Level 1".
	 */
	List<String> slots();

	/**
	 * Which slot the fight is in, from 0. Called every client frame while the mode is playing, so it must be cheap
	 * (a var read, not a widget search). Runs on the client thread.
	 */
	int activeSlot(Client client);

	default boolean appliesTo(String track)
	{
		return track().equalsIgnoreCase(track);
	}

	static BossMode byId(String id)
	{
		for (BossMode mode : ALL)
		{
			if (mode.id().equals(id))
			{
				return mode;
			}
		}
		return null;
	}

	static List<BossMode> forTrack(String track)
	{
		return ALL.stream().filter(mode -> mode.appliesTo(track)).collect(java.util.stream.Collectors.toList());
	}

	/**
	 * Which song plays in {@code slot}, given {@code songs} songs.
	 */
	static int songFor(int slot, int songs)
	{
		return Math.max(0, Math.min(slot, songs - 1));
	}

	/**
	 * What a song covers, for the panel: "Level 3", "Level 6 onward" for the last song when it covers several
	 * slots, or "Unused" when there are more songs than slots.
	 */
	default String slotLabel(int song, int songs)
	{
		List<String> slots = slots();
		if (song >= slots.size())
		{
			return "Unused";
		}
		return song == songs - 1 && songs < slots.size() ? slots.get(song) + " onward" : slots.get(song);
	}
}
