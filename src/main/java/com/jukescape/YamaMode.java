package com.jukescape;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * Yama: a song for each part of the fight. Yama fights in three phases, and between them the player is moved to fight
 * the Judge of Yama, so there are five parts: Phase 1, Judge 1, Phase 2, Judge 2, Phase 3. With the {@link #THRONE}
 * option on there's a sixth before them, numbered 0, for while Yama sits on his throne.
 * <p>
 * In game (logged from a real duo kill):
 * <ol>
 * <li>On arriving, Yama sits on his throne and talks for about 20 s. {@link #FIGHT_VARBIT} turns 1 as he stands up</li>
 * <li>At about 2/3 health he says "Begone", the screen fades to black, and {@link #JUDGE_VARBIT} turns 1 as the player
 * arrives at the Judge, just before the screen fades back in</li>
 * <li>The Judge dies, {@link #JUDGE_VARBIT} goes back to 0 at once, and about 3 s later the screen has faded to black
 * and starts fading in on Yama</li>
 * <li>The same again at about 1/3 health</li>
 * <li>On the kill {@link #FIGHT_VARBIT} goes back to 0 and Yama is back on his throne</li>
 * </ol>
 * Which Judge it is comes from counting Judges finished since the fight started, and from Yama's health bar, so a
 * plugin started mid-fight still lands on about the right part.
 */
final class YamaMode implements BossMode
{
	static final int FIGHT_VARBIT = VarbitID.YAMA_THRONE_STATE;
	static final int JUDGE_VARBIT = VarbitID.YAMA_TRANSITION_PHASE;

	static final int THRONE_SLOT = 0;
	static final int PHASE_1 = 1;
	static final int JUDGE_1 = 2;
	static final int PHASE_2 = 3;
	static final int JUDGE_2 = 4;
	static final int PHASE_3 = 5;
	private static final String[] SLOT_NAMES = {"Throne", "Phase 1", "Judge 1", "Phase 2", "Judge 2", "Phase 3"};

	static final String THRONE_OFF = "off";
	static final String THRONE_ON = "on";
	static final Option THRONE = new Option("throne", "Throne music", List.of(
		new Choice(THRONE_OFF, "Off", "The Phase 1 song also plays while Yama sits on his throne"),
		new Choice(THRONE_ON, "On", "A song of its own (part 0) while Yama sits on his throne, before and after the "
			+ "fight. Song 1 becomes the throne song unless you've chosen its parts")));

	/**
	 * Health left below which the first (or second) Judge must be behind the player. Yama went to the Judges at 62%
	 * and 32% of his health bar in game; these leave room for big hits landing as he goes.
	 */
	private static final double AFTER_FIRST_JUDGE = 0.5;
	private static final double AFTER_SECOND_JUDGE = 0.2;
	/**
	 * Not asked for this long (about 6 s) means the player left the arena, so the next visit starts over.
	 */
	private static final int AWAY_TICKS = 10;
	/**
	 * Give up waiting for the screen to fade back in from a Judge after this long (about 6 s; it took 3 s in game),
	 * so a fade that's missed only delays the song.
	 */
	private static final int RETURN_TICKS = 10;

	// Client thread only
	private int lastTick = Integer.MIN_VALUE / 2; // Far enough back to count as away, without overflowing
	private int lastFight;
	private int lastJudge;
	private int judgesDone;
	/**
	 * Yama's health from his health bar, 0 to 1, or -1 when not seen this fight.
	 */
	private double health = -1;
	/**
	 * Whether a fight has started since arriving, so that a fight var of 0 means it's over rather than not begun.
	 */
	private boolean fought;
	private int slot = PHASE_1;
	/**
	 * The phase to switch to once the screen fades back in from a Judge, or -1.
	 */
	private int returnSlot = -1;
	private int returnTick;
	private boolean sawFade;
	private boolean sawBlack;

	@Override
	public String id()
	{
		return "yama";
	}

	@Override
	public String name()
	{
		return "Yama phases";
	}

	@Override
	public String description()
	{
		return "a song for each Yama phase and each Judge of Yama";
	}

	@Override
	public String track()
	{
		return "Sign Here";
	}

	@Override
	public int slotCount()
	{
		return SLOT_NAMES.length;
	}

	@Override
	public int firstNumber()
	{
		return 0;
	}

	@Override
	public int firstSlot(Library.Entry entry)
	{
		return throneMusic(entry) ? THRONE_SLOT : PHASE_1;
	}

	@Override
	public String slotNoun()
	{
		return "Part";
	}

	@Override
	public String slotName(int slot)
	{
		return slot >= 0 && slot < SLOT_NAMES.length ? SLOT_NAMES[slot] : BossMode.super.slotName(slot);
	}

	@Override
	public List<String[]> specExamples()
	{
		return List.of(
			new String[]{"2, 4", "both Judge fights"},
			new String[]{"1, 3, 5", "all three Yama phases"},
			new String[]{"3-5", "from Phase 2 to the end"});
	}

	@Override
	public List<Option> options()
	{
		return List.of(THRONE);
	}

	private static boolean throneMusic(Library.Entry entry)
	{
		return entry != null && THRONE.current(entry).value.equals(THRONE_ON);
	}

	@Override
	public int activeSlot(Client client, Library.Entry entry)
	{
		int tick = client.getTickCount();
		if (tick != lastTick)
		{
			onTick(client, entry, tick);
		}
		// The screen fades frame by frame, so this is checked every frame
		if (returnSlot >= 0 && fadedBackIn(client, tick))
		{
			slot = returnSlot;
			returnSlot = -1;
		}
		return slot;
	}

	private void onTick(Client client, Library.Entry entry, int tick)
	{
		if (tick - lastTick > AWAY_TICKS)
		{
			// Back in the arena: start over, before the fight
			judgesDone = 0;
			health = -1;
			lastFight = 0;
			lastJudge = 0;
			fought = false;
			returnSlot = -1;
			slot = PHASE_1;
		}
		lastTick = tick;

		int fight = client.getVarbitValue(FIGHT_VARBIT);
		int judge = client.getVarbitValue(JUDGE_VARBIT);
		if (fight == 1 && lastFight == 0 && judge == 0)
		{
			// A new fight (also after a kill without leaving)
			judgesDone = 0;
			health = -1;
		}
		if (judge == 0 && lastJudge == 1)
		{
			judgesDone++;
		}
		lastFight = fight;
		lastJudge = judge;

		int target;
		if (fight == 0 && judge == 0)
		{
			// On the throne, before or after the fight. Without throne music, it's Phase 1 before the fight, and the
			// kill's song keeps playing after it until the player leaves.
			target = throneMusic(entry) ? THRONE_SLOT : fought ? slot : PHASE_1;
		}
		else
		{
			fought = true;
			readHealth(client);
			int done = judgesDone;
			if (health >= 0)
			{
				done = Math.max(done, health < AFTER_SECOND_JUDGE ? 2 : health < AFTER_FIRST_JUDGE ? 1 : 0);
			}
			done = Math.min(done, 2);
			if (judge == 1)
			{
				// The Judge being fought is the one after those done; a third can't happen
				target = done >= 1 ? JUDGE_2 : JUDGE_1;
			}
			else
			{
				target = done == 0 ? PHASE_1 : done == 1 ? PHASE_2 : PHASE_3;
			}
		}

		boolean backFromJudge = (slot == JUDGE_1 || slot == JUDGE_2) && (target == PHASE_2 || target == PHASE_3);
		if (backFromJudge && returnSlot < 0)
		{
			// Wait for the screen to fade back in on Yama, rather than changing the moment the Judge dies
			returnSlot = target;
			returnTick = tick;
			sawFade = false;
			sawBlack = false;
		}
		else if (!backFromJudge)
		{
			slot = target;
			returnSlot = -1;
		}
	}

	/**
	 * Whether the screen has started fading back in since the Judge died: it went black and has started to clear, or
	 * the fade overlay opened and closed, or it's taking too long. As with the Doom's delves, the black layer is fully
	 * opaque at 0.
	 */
	private boolean fadedBackIn(Client client, int tick)
	{
		if (tick - returnTick > RETURN_TICKS)
		{
			return true;
		}
		if (client.getWidget(InterfaceID.FadeOverlay.UNIVERSE) == null)
		{
			return sawFade;
		}
		sawFade = true;
		Widget fader = client.getWidget(InterfaceID.FadeOverlay.FADER);
		if (fader == null)
		{
			return false;
		}
		if (fader.getOpacity() == 0)
		{
			sawBlack = true;
			return false;
		}
		return sawBlack;
	}

	private void readHealth(Client client)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && npc.getId() == NpcID.YAMA)
			{
				int ratio = npc.getHealthRatio();
				int scale = npc.getHealthScale();
				if (ratio >= 0 && scale > 0)
				{
					health = (double) ratio / scale;
				}
				return;
			}
		}
	}
}
