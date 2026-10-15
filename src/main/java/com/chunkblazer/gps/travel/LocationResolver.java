/*
 * Copyright (c) 2026, Vani-Lab
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazer.gps.travel;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TargetNpc;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.LocationEntry;
import com.chunkblazer.gps.data.QuestStep;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves tasks, quest steps and names to representative tiles. Deterministic and null-tolerant. */
public final class LocationResolver
{
	/** How far (Chebyshev tiles) from its home chunk centre a task's target spawn may be to count as the task's location. */
	public static final int HOME_RADIUS_TILES = 192;

	private static final ResolvedLocation NONE = new ResolvedLocation(null, true, "NONE");

	private static final Comparator<LocationEntry> TIE = Comparator
		.comparing(LocationEntry::getName, Comparator.nullsLast(Comparator.<String>naturalOrder()))
		.thenComparing(LocationEntry::getX)
		.thenComparing(LocationEntry::getY)
		.thenComparingInt(LocationEntry::getPlane);

	private final QuestCatalog quests;
	private final Map<Integer, List<LocationEntry>> byId = new HashMap<>();
	private final Map<String, List<LocationEntry>> byExactName = new HashMap<>();
	private final Map<String, List<LocationEntry>> byLowerName = new HashMap<>();
	private final List<LocationEntry> banks = new ArrayList<>();

	public LocationResolver(GpsData data, QuestCatalog quests)
	{
		this.quests = quests;
		if (data == null)
		{
			return;
		}
		for (LocationEntry e : data.getLocations())
		{
			if (e == null || e.getX() == null || e.getY() == null)
			{
				continue;
			}
			for (Integer id : e.getIds())
			{
				if (id != null)
				{
					byId.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
				}
			}
			if (e.getName() != null)
			{
				byExactName.computeIfAbsent(e.getName(), k -> new ArrayList<>()).add(e);
				byLowerName.computeIfAbsent(e.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
			}
			if ("BANK".equalsIgnoreCase(e.getKind()))
			{
				banks.add(e);
			}
		}
	}

	public ResolvedLocation forTask(NuzlockeTask t, int homeRegionId)
	{
		Tile home = TileMath.centre(homeRegionId);
		if (t != null)
		{
			TargetNpc npc = t.getTargetNpc();
			if (npc != null)
			{
				List<LocationEntry> hits = new ArrayList<>();
				if (npc.getNpcIds() != null)
				{
					for (Integer id : npc.getNpcIds())
					{
						List<LocationEntry> l = id == null ? null : byId.get(id);
						if (l != null)
						{
							hits.addAll(l);
						}
					}
				}
				LocationEntry best = closest(nearHome(hits, home), home);
				if (best != null)
				{
					return fromEntry(best, "NPC_ID");
				}
				String name = stripVariant(npc.getName());
				if (name != null)
				{
					best = closest(nearHome(byLowerName.get(name.toLowerCase(Locale.ROOT)), home), home);
					if (best != null)
					{
						return fromEntry(best, "NAME");
					}
				}
			}
			if ("QUEST_CHECK".equals(t.getCompletionType()))
			{
				TaskConstraints c = t.getConstraints();
				if (c != null && c.getQuest() != null)
				{
					ResolvedLocation q = forQuestStep(c.getQuest(), 0);
					if (q.getTile() != null)
					{
						return new ResolvedLocation(q.getTile(), q.isApproximate(), "QUEST_START");
					}
				}
			}
		}
		return new ResolvedLocation(home, true, "CHUNK_CENTRE");
	}

	/**
	 * Entries within {@link #HOME_RADIUS_TILES} of the task's home chunk centre. A task can only be done inside its own
	 * chunk, so a spawn of the target far away (another continent, a dungeon) is not where this task happens, and
	 * routing to it would make the task look unreachable; those fall back to the chunk centre instead.
	 */
	private static List<LocationEntry> nearHome(List<LocationEntry> entries, Tile home)
	{
		List<LocationEntry> out = new ArrayList<>();
		if (entries != null)
		{
			for (LocationEntry e : entries)
			{
				if (e != null && e.getX() != null && e.getY() != null
					&& Math.max(Math.abs((long) e.getX() - home.getX()), Math.abs((long) e.getY() - home.getY())) <= HOME_RADIUS_TILES)
				{
					out.add(e);
				}
			}
		}
		return out;
	}

	public ResolvedLocation forQuestStep(String questId, int stepIndex)
	{
		Optional<QuestModel> m = questId == null || quests == null ? Optional.empty() : quests.get(questId);
		if (!m.isPresent())
		{
			return NONE;
		}
		QuestModel q = m.get();
		List<QuestStep> steps = q.getSteps() == null ? Collections.emptyList() : q.getSteps();
		if (stepIndex >= 0 && stepIndex < steps.size() && steps.get(stepIndex) != null)
		{
			Tile loc = steps.get(stepIndex).getLocation();
			if (loc.isKnown())
			{
				return new ResolvedLocation(loc, loc.isApproximate(), "QUEST_STEP");
			}
		}
		if (q.getChunks() != null)
		{
			for (Integer c : q.getChunks())
			{
				if (c != null)
				{
					return new ResolvedLocation(TileMath.centre(c), true, "QUEST_CHUNK");
				}
			}
		}
		return NONE;
	}

	public ResolvedLocation forName(String name, int fallbackRegionId)
	{
		Tile home = TileMath.centre(fallbackRegionId);
		if (name != null)
		{
			LocationEntry best = closest(byExactName.get(name), home);
			if (best == null)
			{
				best = closest(byLowerName.get(name.toLowerCase(Locale.ROOT)), home);
			}
			if (best != null)
			{
				return fromEntry(best, "NAME");
			}
		}
		return new ResolvedLocation(home, true, "CHUNK_CENTRE");
	}

	/**
	 * The entry named {@code name} (case-insensitive) or carrying it as a base name ("Tree" matches "Tree (Varrock)"),
	 * restricted to tiles {@code allowed} accepts and closest to {@code reference} (ties by name, then x, y, plane;
	 * with no reference the tie order alone decides). Empty when nothing qualifies.
	 */
	public Optional<ResolvedLocation> findNamed(String name, java.util.function.Predicate<Tile> allowed, Tile reference)
	{
		if (name == null || name.trim().isEmpty())
		{
			return Optional.empty();
		}
		String base = name.trim().toLowerCase(Locale.ROOT);
		List<LocationEntry> hits = new ArrayList<>();
		for (Map.Entry<String, List<LocationEntry>> e : byLowerName.entrySet())
		{
			if (e.getKey().equals(base) || e.getKey().startsWith(base + " ("))
			{
				for (LocationEntry le : e.getValue())
				{
					if (allowed == null || allowed.test(new Tile(le.getX(), le.getY(), le.getPlane(), false, "LOCATIONS")))
					{
						hits.add(le);
					}
				}
			}
		}
		LocationEntry best = closest(hits, reference != null && reference.isKnown() ? reference : null);
		return best == null ? Optional.empty() : Optional.of(fromEntry(best, "NAME"));
	}

	public Optional<ResolvedLocation> nearestBank(Tile from)
	{
		// Plane is ignored when ranking banks (approximate model).
		if (from == null || !from.isKnown())
		{
			return Optional.empty();
		}
		LocationEntry best = closest(banks, from);
		return best == null ? Optional.empty() : Optional.of(fromEntry(best, "BANK"));
	}

	private static ResolvedLocation fromEntry(LocationEntry e, String source)
	{
		return new ResolvedLocation(new Tile(e.getX(), e.getY(), e.getPlane(), false, "LOCATIONS"), false, source);
	}

	/** Closest entry to the reference tile; ties broken by name, then x, y, plane. Null/empty yields null. */
	private static LocationEntry closest(List<LocationEntry> entries, Tile ref)
	{
		if (entries == null)
		{
			return null;
		}
		LocationEntry best = null;
		long bestD = 0;
		for (LocationEntry e : entries)
		{
			if (e == null || e.getX() == null || e.getY() == null)
			{
				continue;
			}
			// Plane is ignored when ranking: approximate model.
			long d = ref == null ? 0
				: Math.max(Math.abs((long) e.getX() - ref.getX()), Math.abs((long) e.getY() - ref.getY()));
			if (best == null || d < bestD || (d == bestD && TIE.compare(e, best) < 0))
			{
				best = e;
				bestD = d;
			}
		}
		return best;
	}

	private static String stripVariant(String name)
	{
		if (name == null)
		{
			return null;
		}
		int i = name.indexOf('#');
		String s = (i >= 0 ? name.substring(0, i) : name).trim();
		return s.isEmpty() ? null : s;
	}
}
