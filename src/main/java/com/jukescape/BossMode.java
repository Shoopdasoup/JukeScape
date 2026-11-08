package com.jukescape;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;

/**
 * A play mode made for one boss fight: the fight is split into slots (delve levels, phases, ...), and the songs for
 * whichever slot the fight is in play. Which songs go in which slots is the player's call, see {@link SlotPlan}.
 * <p>
 * Each boss gets its own implementation, listed in {@link #ALL}. Everything boss-specific stays in that class.
 */
interface BossMode
{
	List<BossMode> ALL = List.of(new DelveMode());

	int UNLIMITED = Integer.MAX_VALUE;

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
	 * How many slots the fight has, or {@link #UNLIMITED} when it goes on as long as the player does.
	 */
	int slotCount();

	/**
	 * What a slot is called, e.g. "Level" or "Phase".
	 */
	String slotNoun();

	/**
	 * A slot's name, e.g. "Level 1" for slot 0.
	 */
	default String slotName(int slot)
	{
		return slotNoun() + " " + (slot + 1);
	}

	/**
	 * Which slot the fight is in, from 0. Called every client frame while the mode is playing, so it must be cheap.
	 * Runs on the client thread.
	 *
	 * @param entry the profile playing, for its {@link #options()}
	 */
	int activeSlot(Client client, Library.Entry entry);

	/**
	 * Settings the mode offers per profile, shown as advanced options in the panel.
	 */
	default List<Option> options()
	{
		return List.of();
	}

	/**
	 * Lets a mode react to what the game says in the chatbox. Called for every chat message, on the client thread.
	 */
	default void onChatMessage(ChatMessage message, Client client)
	{
	}

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
	 * A per-profile setting with a few choices. The first choice is the default.
	 */
	final class Option
	{
		final String key;
		final String label;
		final List<Choice> choices;

		Option(String key, String label, List<Choice> choices)
		{
			this.key = key;
			this.label = label;
			this.choices = choices;
		}

		/**
		 * The choice {@code entry}'s profile has, or the default.
		 */
		Choice current(Library.Entry entry)
		{
			String value = entry.options.get(key);
			for (Choice choice : choices)
			{
				if (choice.value.equals(value))
				{
					return choice;
				}
			}
			return choices.get(0);
		}
	}

	final class Choice
	{
		final String value;
		final String label;
		final String description;

		Choice(String value, String label, String description)
		{
			this.value = value;
			this.label = label;
			this.description = description;
		}
	}
}
