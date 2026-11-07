package com.jukescape;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Doom of Mokhaiotl: one song per delve level, for as many levels as the player gives songs to. Delves go on past
 * level 8 for as long as the player survives, so there's no last slot: the last song covers every deeper level.
 */
final class DelveMode implements BossMode
{
	/**
	 * How many levels of the current delve the player has cleared: 0 during level 1, 1 once level 1's boss dies and
	 * through level 2, and so on. So the next level's song starts at the kill, while the player heads down. Back to 0
	 * outside a fight. (Confirmed in game: reading it as the current level played every song one level late.)
	 */
	static final int LEVELS_CLEARED_VARP = VarPlayerID.DOM_CURRENT_LEVEL_TEMP;

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
	public int slotCount()
	{
		return UNLIMITED;
	}

	@Override
	public String slotName(int slot)
	{
		return "Level " + (slot + 1);
	}

	@Override
	public int activeSlot(Client client)
	{
		// Slot N is level N + 1, the level after the N cleared
		return Math.max(0, client.getVarpValue(LEVELS_CLEARED_VARP));
	}
}
