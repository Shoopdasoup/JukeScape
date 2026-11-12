package com.jukescape;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which songs play in which slots of a {@link BossMode}, shared by every boss mode.
 * <p>
 * Each song plays in the slots its spec lists, written like page ranges when printing: "1-4", "1, 2, 4" or "9+"
 * (9 and up). Numbers here are the ones players see: slot 0 is number {@link BossMode#firstNumber()}. A song with
 * no spec plays in the slot matching its position in the playlist, counting from the profile's
 * {@link BossMode#firstSlot first slot}. A slot no song lists keeps the previous slot's songs, and slots before the
 * first listed
 * one use the earliest listed. Several songs in one slot play in turn.
 */
final class SlotPlan
{
	private static final Pattern PART = Pattern.compile("(\\d{1,4})\\s*(?:(-)\\s*(\\d{1,4})|(\\+))?");
	private static final int OPEN = Integer.MAX_VALUE;

	private SlotPlan()
	{
	}

	/**
	 * @return inclusive [from, to] ranges, to being {@link #OPEN} for "N+"
	 * @throws IllegalArgumentException with a message for players, if it isn't a valid spec
	 */
	static List<int[]> parse(String spec)
	{
		return parse(spec, 0);
	}

	/**
	 * @param min the lowest number allowed
	 */
	static List<int[]> parse(String spec, int min)
	{
		List<int[]> ranges = new ArrayList<>();
		for (String part : spec.split(","))
		{
			part = part.trim();
			if (part.isEmpty())
			{
				continue;
			}
			Matcher m = PART.matcher(part);
			if (!m.matches())
			{
				throw new IllegalArgumentException("'" + part + "' isn't a number, a range like 1-4, or like 9+");
			}
			int from = Integer.parseInt(m.group(1));
			int to = m.group(4) != null ? OPEN : m.group(2) != null ? Integer.parseInt(m.group(3)) : from;
			if (to < from)
			{
				throw new IllegalArgumentException("'" + part + "' isn't a valid range");
			}
			if (from < min)
			{
				throw new IllegalArgumentException("the numbers start at " + min);
			}
			ranges.add(new int[]{from, to});
		}
		if (ranges.isEmpty())
		{
			throw new IllegalArgumentException("enter at least one number");
		}
		return ranges;
	}

	/**
	 * A spec tidied up for saving, e.g. "1 -4,6" becomes "1-4, 6".
	 */
	static String normalize(String spec)
	{
		List<String> parts = new ArrayList<>();
		for (int[] r : parse(spec))
		{
			parts.add(r[1] == OPEN ? r[0] + "+" : r[0] == r[1] ? Integer.toString(r[0]) : r[0] + "-" + r[1]);
		}
		return String.join(", ", parts);
	}

	/**
	 * Which of {@code entry}'s songs play in {@code slot} (from 0), in playlist order.
	 */
	static List<Path> songsFor(int slot, Library.Entry entry)
	{
		List<Path> songs = new ArrayList<>();
		for (int i : owners(slot + entry.bossMode.firstNumber(), ranges(entry), entry))
		{
			songs.add(entry.files.get(i));
		}
		return songs;
	}

	/**
	 * Which slots a song effectively covers, for the panel: "Level 3", "Levels 1-4", "Levels 1, 2, 4", "Level 6
	 * onward", or "Unused".
	 */
	static String label(int song, Library.Entry entry)
	{
		BossMode mode = entry.bossMode;
		List<List<int[]>> ranges = ranges(entry);
		int low = low(entry);
		int last = lastBound(ranges, mode);

		List<int[]> covered = new ArrayList<>();
		for (int level = low; level <= last; level++)
		{
			if (owners(level, ranges, entry).contains(song))
			{
				if (!covered.isEmpty() && covered.get(covered.size() - 1)[1] == level - 1)
				{
					covered.get(covered.size() - 1)[1] = level;
				}
				else
				{
					covered.add(new int[]{level, level});
				}
			}
		}
		// Past every bound, each level plays the same as the one after the last bound
		boolean onward = mode.slotCount() == BossMode.UNLIMITED && owners(last + 1, ranges, entry).contains(song);
		if (onward)
		{
			if (!covered.isEmpty() && covered.get(covered.size() - 1)[1] == last)
			{
				covered.get(covered.size() - 1)[1] = OPEN;
			}
			else
			{
				covered.add(new int[]{last + 1, OPEN});
			}
		}
		if (covered.isEmpty())
		{
			return "Unused";
		}
		if (mode.slotCount() != BossMode.UNLIMITED)
		{
			return named(covered, low, mode);
		}

		List<String> parts = new ArrayList<>();
		for (int[] r : covered)
		{
			parts.add(r[1] == OPEN ? r[0] + " onward" : r[0] == r[1] ? Integer.toString(r[0]) : r[0] + "-" + r[1]);
		}
		boolean plural = covered.size() > 1 || (covered.get(0)[0] != covered.get(0)[1] && covered.get(0)[1] != OPEN);
		return mode.slotNoun() + (plural ? "s " : " ") + String.join(", ", parts);
	}

	/**
	 * Covered slots by their names, for modes with a fixed set of them: "Judge 1", "Phase 1 & Phase 3", "Phase 2 to
	 * Phase 3" or "All parts".
	 */
	private static String named(List<int[]> covered, int low, BossMode mode)
	{
		int base = mode.firstNumber();
		if (covered.size() == 1 && covered.get(0)[0] == low && covered.get(0)[1] == mode.slotCount() - 1 + base)
		{
			return "All parts";
		}
		List<String> parts = new ArrayList<>();
		for (int[] r : covered)
		{
			if (r[1] - r[0] >= 2)
			{
				parts.add(mode.slotName(r[0] - base) + " to " + mode.slotName(r[1] - base));
				continue;
			}
			for (int slot = r[0]; slot <= r[1]; slot++)
			{
				parts.add(mode.slotName(slot - base));
			}
		}
		// "Judge 1 & Judge 2": the panel's pixel font draws commas much like colons, and a colon follows
		int last = parts.size() - 1;
		return last == 0 ? parts.get(0) : String.join(", ", parts.subList(0, last)) + " & " + parts.get(last);
	}

	/**
	 * Each song's ranges: its spec's, or just its position.
	 */
	private static List<List<int[]>> ranges(Library.Entry entry)
	{
		Map<String, String> specs = entry.slotSpecs;
		List<List<int[]>> all = new ArrayList<>();
		for (int i = 0; i < entry.files.size(); i++)
		{
			String spec = specs.get(Library.songName(entry.files.get(i)));
			List<int[]> parsed = null;
			if (spec != null)
			{
				try
				{
					parsed = parse(spec);
				}
				catch (IllegalArgumentException e)
				{
					// Edited by hand into something invalid; treat as unset
				}
			}
			all.add(parsed != null ? parsed : Collections.singletonList(new int[]{low(entry) + i, low(entry) + i}));
		}
		return all;
	}

	/**
	 * The songs that play on {@code level}: the ones listing it, else the nearest earlier level's, else the earliest
	 * listed level's.
	 */
	private static List<Integer> owners(int level, List<List<int[]>> ranges, Library.Entry entry)
	{
		BossMode mode = entry.bossMode;
		int low = low(entry);
		if (ranges.isEmpty())
		{
			return Collections.emptyList();
		}
		if (mode.slotCount() != BossMode.UNLIMITED)
		{
			level = Math.min(level, mode.slotCount() - 1 + mode.firstNumber());
		}
		for (int l = level; l >= low; l--)
		{
			List<Integer> owners = listing(l, ranges);
			if (!owners.isEmpty())
			{
				return owners;
			}
		}
		// The earliest listed slot this profile uses, or failing that the earliest listed at all
		int first = OPEN;
		int any = OPEN;
		for (List<int[]> song : ranges)
		{
			for (int[] r : song)
			{
				any = Math.min(any, r[0]);
				if (r[1] >= low)
				{
					first = Math.min(first, Math.max(r[0], low));
				}
			}
		}
		return listing(first != OPEN ? first : any, ranges);
	}

	private static List<Integer> listing(int level, List<List<int[]>> ranges)
	{
		List<Integer> owners = new ArrayList<>();
		for (int i = 0; i < ranges.size(); i++)
		{
			for (int[] r : ranges.get(i))
			{
				if (level >= r[0] && level <= r[1])
				{
					owners.add(i);
					break;
				}
			}
		}
		return owners;
	}

	/**
	 * The lowest number {@code entry}'s profile uses, where its first song goes by default.
	 */
	private static int low(Library.Entry entry)
	{
		return entry.bossMode.firstSlot(entry) + entry.bossMode.firstNumber();
	}

	/**
	 * The highest level any range starts or ends at, past which nothing changes.
	 */
	private static int lastBound(List<List<int[]>> ranges, BossMode mode)
	{
		if (mode.slotCount() != BossMode.UNLIMITED)
		{
			return mode.slotCount() - 1 + mode.firstNumber();
		}
		int last = 1;
		for (List<int[]> song : ranges)
		{
			for (int[] r : song)
			{
				last = Math.max(last, r[1] == OPEN ? r[0] : r[1]);
			}
		}
		return last;
	}
}
