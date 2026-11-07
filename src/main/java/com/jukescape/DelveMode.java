package com.jukescape;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Doom of Mokhaiotl: one song per delve level. Levels 1 to 8 get harder each time, and every level past 8 (a "deep
 * delve") plays like level 8, so they share the last slot.
 */
final class DelveMode implements BossMode
{
	/**
	 * The delve level the player is on, which the game also shows them between levels. The slot is 0 (level 1)
	 * outside a fight.
	 */
	static final int LEVEL_VARP = VarPlayerID.DOM_CURRENT_LEVEL_TEMP;

	private static final List<String> SLOTS = List.of(
		"Level 1", "Level 2", "Level 3", "Level 4", "Level 5", "Level 6", "Level 7", "Level 8", "Deep delves");

	@Override
	public String id()
	{
		return "delve";
	}

	@Override
	public String name()
	{
		return "Delve";
	}

	@Override
	public String description()
	{
		return "a song for each delve level";
	}

	@Override
	public String track()
	{
		return "The Doom";
	}

	@Override
	public List<String> slots()
	{
		return SLOTS;
	}

	@Override
	public int activeSlot(Client client)
	{
		int level = client.getVarpValue(LEVEL_VARP);
		return Math.max(0, Math.min(level, SLOTS.size()) - 1);
	}
}
