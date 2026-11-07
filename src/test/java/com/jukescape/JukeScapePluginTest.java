package com.jukescape;

import com.jukescape.youtube.JukeScapeYouTubePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class JukeScapePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(JukeScapePlugin.class, JukeScapeYouTubePlugin.class);
		RuneLite.main(args);
	}
}
