package com.jukescape;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.util.Text;

/**
 * Doom of Mokhaiotl: songs per delve level, for as many levels as the player gives songs to. Delves go on past level
 * 8 for as long as the player survives, so there's no last slot.
 * <p>
 * In game (logged from a real run), a descent goes:
 * <ol>
 * <li>"Descend" shows "You jump further into the burrow..." and fades the screen to black</li>
 * <li>about 2 s later, on arrival, the chat says "Delve level: N" while the screen is still black</li>
 * <li>about 1 s after that the screen fades back in (the fade overlay closes)</li>
 * <li>about 2 s after that the Doom appears and {@link #LEVELS_CLEARED_VARP} catches up</li>
 * </ol>
 * The song changes at 3 or 4, as the profile's {@link #SWITCH} option says. Either way the var is what it settles
 * on, so a missed message only delays the change to 4.
 */
final class DelveMode implements BossMode
{
	/**
	 * One less than the delve level the player is on: 0 during level 1, 1 from the Doom appearing on level 2, and so
	 * on. Back to 0 outside a fight. Confirmed in game: reading it as the level itself played every song one level
	 * late.
	 */
	static final int LEVELS_CLEARED_VARP = VarPlayerID.DOM_CURRENT_LEVEL_TEMP;

	static final String SWITCH_ON_ARRIVAL = "arrival";
	static final String SWITCH_ON_SPAWN = "spawn";
	static final Option SWITCH = new Option("switch", "Song changes", List.of(
		new Choice(SWITCH_ON_ARRIVAL, "On arrival", "When the screen fades in on the new delve"),
		new Choice(SWITCH_ON_SPAWN, "When the Doom appears", "A few seconds after arriving, when the fight starts")));

	private static final Pattern ARRIVAL_MESSAGE = Pattern.compile("Delve level: (\\d+)");
	private static final Pattern COLOUR_CODE = Pattern.compile("@[a-z_]+@");
	/**
	 * Give up on an arrival the var never confirms (about 18 s), so a mistake can't stick.
	 */
	private static final int PENDING_TICKS = 30;

	// Client thread only
	private int arrivalSlot = -1;
	private int arrivalTick;

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
	public String slotNoun()
	{
		return "Level";
	}

	@Override
	public List<Option> options()
	{
		return List.of(SWITCH);
	}

	@Override
	public int activeSlot(Client client, Library.Entry entry)
	{
		// Slot N is level N + 1, the level after the N cleared
		int cleared = Math.max(0, client.getVarpValue(LEVELS_CLEARED_VARP));
		if (arrivalSlot >= 0)
		{
			if (cleared >= arrivalSlot || client.getTickCount() - arrivalTick > PENDING_TICKS)
			{
				arrivalSlot = -1;
			}
			else if (SWITCH.current(entry).value.equals(SWITCH_ON_ARRIVAL) && fadedIn(client))
			{
				return arrivalSlot;
			}
		}
		return cleared;
	}

	/**
	 * Whether the screen has faded back in since the arrival message. The fade overlay closes and reopens on the
	 * arrival tick itself, so it only counts from the tick after.
	 */
	private boolean fadedIn(Client client)
	{
		return client.getTickCount() > arrivalTick && client.getWidget(InterfaceID.FadeOverlay.UNIVERSE) == null;
	}

	@Override
	public void onChatMessage(ChatMessage message, Client client)
	{
		if (message.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		// Arrives as "@mes_hl_red@Delve level: 2</col>": strip the colour code as well as the tags. Only the arrival
		// message is exactly this; the end-of-level one goes on with the duration.
		String text = COLOUR_CODE.matcher(Text.removeTags(message.getMessage())).replaceAll("").trim();
		Matcher arrived = ARRIVAL_MESSAGE.matcher(text);
		if (arrived.matches())
		{
			arrivalSlot = Integer.parseInt(arrived.group(1)) - 1;
			arrivalTick = client.getTickCount();
		}
	}
}
