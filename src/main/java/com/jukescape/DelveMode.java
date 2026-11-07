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
	 * The delve level the player is on, which the game also shows them between levels. The slot is 0 (level 1)
	 * outside a fight.
	 */
	static final int LEVEL_VARP = VarPlayerID.DOM_CURRENT_LEVEL_TEMP;

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
		return Math.max(0, client.getVarpValue(LEVEL_VARP) - 1);
	}
}
