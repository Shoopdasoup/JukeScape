# JukeScape - OSRS Music Replacer

Replace OSRS music tracks with your own opus/ogg/mp3/wav files, without frame drops.

JukeScape started as a rewrite of [alowaniak/music-replacer](https://github.com/alowaniak/music-replacer), built so the game thread never waits on music.

> **Made with AI:** JukeScape's code was written by [Claude](https://claude.com/claude-code), Anthropic's AI coding assistant. shoopdasoup decided what it should do and how it should look, and tested it in game.

## Why it doesn't stutter

| Original plugin | JukeScape |
| --- | --- |
| Every client frame (~50/s): config read + JSON parse + 2 `Files.exists` calls on the game thread | Every client frame: one widget text compare and a hash lookup only when the track changes |
| On track change, copies the whole file to a temp file **on the game thread**; WAVs are fully decoded into memory first | Game thread just posts a request (~15 µs); a dedicated audio thread streams the file |
| JaCo player singleton, volume pushed to it every frame | Own `SourceDataLine`, volume/fades applied per sample with smoothing, no clicks |
| Errors only in the log | Failures show in chat |
| Mutes the game's music by setting its volume to 0, then runs a game script to restart it afterwards | Never runs game scripts: turns the game's music down to 1 while a replacement plays, then restores your volume |

## Usage

- **Side panel:** the music icon in RuneLite's sidebar shows what's playing (with a skip button) and every replaced track. Click a track to see its playlist, reorder or remove songs, add more, or toggle shuffle.
- **Replace a track:** right-click it in the music tab → **Replace**, then pick one or more opus, ogg, mp3 or wav files.
- **Build a playlist:** right-click a replaced (bold) track → **Add song**. Songs play one after another, in the order you added them.
- **Shuffle, remove songs or remove the replacement:** right-click a replaced track → **Playlist**.
- **Play modes:** the track's **Mode** button in the side panel (or **Playlist → Play mode** in game) plays the songs in order or shuffled. Some boss tracks also get a mode of their own:
  - **Delve** (The Doom, for the Doom of Mokhaiotl): a song for each delve level. Song 1 plays on level 1, song 2 on level 2, and so on, as deep as you like: give it 16 songs and levels 9–16 each get their own. The last song covers every level deeper than your list goes. The song changes as the screen fades in on the new level.
- **Advanced options** (in JukeScape's settings) adds more controls to the side panel. For Delve:
  - **Song changes**: on arrival, or when the Doom appears.
  - **Which levels each song plays on**: click a song and enter levels like printing pages, e.g. `1-4`, `1, 2, 4` or `9+`. A level with no song keeps the previous level's song, so four songs can cover every delve. Several songs on one level play in turn.
- **Profiles:** give a track several playlists and switch between them without losing any, from the track's **Profile** button in the side panel or **Playlist → Switch profile / New profile** in game. A new profile starts empty, and an empty profile plays the game's own music.
- **Everything else:** right-click the music tab icon → **Music replacements**:
  - *Open replacements folder*: any `Track Name.opus`/`.ogg`/`.mp3`/`.wav` you drop in there replaces that track, and a `Track Name` folder of songs is a playlist for it
  - *Reload folder*, *Remove all replacements*

A single replacement loops and a playlist keeps going until the game moves to another track. Replacements follow the in-game master and music volume, and fade between tracks.
Replaced tracks show in bold in the music list and in the "now playing" label.

Songs replaced or added in-game are saved in a folder named after the track, as `01 - Song name.opus`, `02 - …`, so they keep their names. Playlist folders play in file name order, and an empty `.shuffle` file in the folder plays them in random order.
Once a track has profiles, each one is a folder inside the track's folder (`The Doom/Remixes/01 - Song.opus`), and a `.profile` file names the one that plays.

Files live in `%USERPROFILE%\.runelite\music-replacer`, the same folder the original plugin used, so your existing replacements carry over. Turn the original plugin off while using this one.

### Config

- **Replacement volume**: remixes are much louder than the in-game MIDI, so they play at 70% by default
- **Fade time**: how long songs crossfade when the music changes, default 800 ms. Songs in different sample rates (say a 44.1 kHz mp3 and a 48 kHz opus) can't be mixed, so those fade out and then in instead

## YouTube add-on (developer mode only)

`youtube-addon/` holds a second plugin, **JukeScape YouTube**, that adds *A YouTube link* and *A YouTube search* to the Replace and Add song menus.
Paste a video link, or a playlist link to download up to 200 songs into a track's playlist. Playlists and long videos show their length and estimated size and ask before downloading.
It isn't part of the Plugin Hub plugin: it runs [yt-dlp](https://github.com/yt-dlp/yt-dlp), and Hub plugins aren't allowed to run external programs.
Move `youtube-addon/` out of this repository before submitting to the Hub.

- Install yt-dlp and ffmpeg (`winget install yt-dlp` installs both), then set **yt-dlp path** in the JukeScape YouTube settings if it isn't on your PATH.
- Downloads keep YouTube's original Opus audio: ffmpeg only repackages it, so there's no quality loss from re-encoding.
- If yt-dlp starts failing with HTTP 403, it's out of date: run `yt-dlp -U`.

## Running it

Plugins that aren't on the Plugin Hub run through RuneLite's developer mode. This launches it with JukeScape and the YouTube add-on:

```bash
./gradlew run
```

On Windows you can double-click `Launch JukeScape.bat` instead. It points Gradle at a JDK first; edit `JAVA_HOME` at its top if yours is somewhere else.

A JDK 11 or newer is needed to build it. To log in with a Jagex account in developer mode, first follow RuneLite's [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide (launcher → *Configure* → add `--insecure-write-credentials`, then log in once through the normal launcher).

## Credits

- Original plugin by Alowan ([alowaniak/music-replacer](https://github.com/alowaniak/music-replacer)), BSD 2-Clause
- MP3 decoding by [JLayer](https://github.com/umjammer/jlayer) (LGPL 2.1)
- Ogg Vorbis decoding by [JOrbis](http://www.jcraft.com/jorbis/) (LGPL 2.0)
- Opus decoding by [Concentus](https://github.com/lostromb/concentus), a Java port of libopus (BSD)
