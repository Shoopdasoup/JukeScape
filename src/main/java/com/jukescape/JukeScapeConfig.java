package com.jukescape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(JukeScapeConfig.GROUP)
public interface JukeScapeConfig extends Config
{
	String GROUP = "jukescape";

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "volume",
		name = "Replacement volume",
		description = "Volume of replacements relative to the in-game music slider.<br>" +
			"Remixes are usually much louder than the original tracks.",
		position = 1
	)
	default int volume()
	{
		return 70;
	}

	@Range(max = 5000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "crossfade",
		name = "Fade time",
		description = "How long the old song and the new one crossfade for. 0 switches instantly.",
		position = 2
	)
	default int crossfade()
	{
		return 800;
	}

	@ConfigItem(
		keyName = "advanced",
		name = "Advanced options",
		description = "Show extra settings in the JukeScape side panel, like which delve levels each song plays on.<br>"
			+ "Settings you've made keep working when this is off.",
		position = 3
	)
	default boolean advanced()
	{
		return false;
	}
}
