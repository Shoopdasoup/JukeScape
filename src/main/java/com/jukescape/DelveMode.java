package com.jukescape;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.util.Text;

/**
 * Doom of Mokhaiotl: one song per delve level, for as many levels as the player gives songs to. Delves go on past
 * level 8 for as long as the player survives, so there's no last slot: the last song covers every deeper level.
 * <p>
 * The next level's song starts the moment the player jumps down the burrow. In game, a descent goes:
 * <ol>
 * <li>"Descend" in the burrow's window shows "You jump further into the burrow..." (only on descending, not on just
 * opening the window)</li>
 * <li>about 2 s later, on arrival, the chat says "Delve level: N"</li>
 * <li>about 3 s after that, {@link #LEVELS_CLEARED_VARP} catches up</li>
 * </ol>
 * The messages switch the song early; the var is what it settles on, so a missed or misread message only delays the
 * switch to where the var puts it.
 */
final class DelveMode implements BossMode
{
	/**
	 * One less than the delve level the player is on: 0 during level 1, 1 from arriving on level 2, and so on. Back
	 * to 0 outside a fight. Confirmed in game: reading it as the level itself played every song one level late.
	 */
	static final int LEVELS_CLEARED_VARP = VarPlayerID.DOM_CURRENT_LEVEL_TEMP;

	private static final String JUMP_MESSAGE = "You jump further into the burrow";
	private static final Pattern ARRIVAL_MESSAGE = Pattern.compile("Delve level: (\\d+)");
	private static final Pattern COLOUR_CODE = Pattern.compile("@[a-z_]+@");
	/**
	 * The jump message shows again on arrival; ignore repeats this soon after the first.
	 */
	private static final int REPEAT_TICKS = 15;
	/**
	 * Give up on a message the var never confirms (about 18 s), so a mistake can't stick.
	 */
	private static final int PENDING_TICKS = 30;

	// Client thread only
	private int pendingSlot = -1;
	private int pendingTick;
	private int lastJumpTick = Integer.MIN_VALUE / 2;

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
		int cleared = Math.max(0, client.getVarpValue(LEVELS_CLEARED_VARP));
		if (pendingSlot >= 0)
		{
			if (cleared >= pendingSlot || client.getTickCount() - pendingTick > PENDING_TICKS)
			{
				pendingSlot = -1;
			}
			else
			{
				return pendingSlot;
			}
		}
		return cleared;
	}

	@Override
	public void onChatMessage(ChatMessage message, Client client)
	{
		// The arrival message comes as "@mes_hl_red@Delve level: 2</col>": strip the colour code as well as the tags
		String text = COLOUR_CODE.matcher(Text.removeTags(message.getMessage())).replaceAll("").trim();
		int tick = client.getTickCount();
		if (message.getType() == ChatMessageType.MESBOX && text.startsWith(JUMP_MESSAGE))
		{
			if (tick - lastJumpTick > REPEAT_TICKS)
			{
				lastJumpTick = tick;
				pend(Math.max(0, client.getVarpValue(LEVELS_CLEARED_VARP)) + 1, tick);
			}
		}
		else if (message.getType() == ChatMessageType.GAMEMESSAGE)
		{
			// Only the arrival message is exactly this; the end-of-level one goes on with the duration
			Matcher arrived = ARRIVAL_MESSAGE.matcher(text);
			if (arrived.matches())
			{
				pend(Integer.parseInt(arrived.group(1)) - 1, tick);
			}
		}
	}

	private void pend(int slot, int tick)
	{
		pendingSlot = slot;
		pendingTick = tick;
	}
}
