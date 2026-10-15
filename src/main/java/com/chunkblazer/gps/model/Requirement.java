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
package com.chunkblazer.gps.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable set of requirements. Unknown requirements are never met.
 */
public final class Requirement
{
	public static final Requirement NONE = builder().build();

	private final Map<String, Integer> skills;
	private final Set<String> quests;
	private final int questPoints;
	private final int combatLevel;
	private final List<ItemNeed> items;
	private final int minMaxHit;
	private final Set<Integer> chunks;
	private final List<String> unknown;

	private Requirement(Builder b)
	{
		this.skills = Collections.unmodifiableMap(new LinkedHashMap<>(b.skills));
		this.quests = Collections.unmodifiableSet(new LinkedHashSet<>(b.quests));
		this.questPoints = b.questPoints;
		this.combatLevel = b.combatLevel;
		this.items = Collections.unmodifiableList(new ArrayList<>(b.items));
		this.minMaxHit = b.minMaxHit;
		this.chunks = Collections.unmodifiableSet(new LinkedHashSet<>(b.chunks));
		this.unknown = Collections.unmodifiableList(new ArrayList<>(b.unknown));
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public Map<String, Integer> getSkills()
	{
		return skills;
	}

	public Set<String> getQuests()
	{
		return quests;
	}

	public int getQuestPoints()
	{
		return questPoints;
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	public List<ItemNeed> getItems()
	{
		return items;
	}

	public int getMinMaxHit()
	{
		return minMaxHit;
	}

	public Set<Integer> getChunks()
	{
		return chunks;
	}

	public List<String> getUnknown()
	{
		return unknown;
	}

	public boolean isUnknown()
	{
		return !unknown.isEmpty();
	}

	public List<Missing> missing(PlayerState state)
	{
		List<Missing> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : skills.entrySet())
		{
			int have = state.getLevel(e.getKey());
			if (have < e.getValue())
			{
				out.add(new Missing(Missing.Kind.SKILL,
					titleCase(e.getKey()) + " " + e.getValue() + " (you have " + have + ")"));
			}
		}
		for (String q : quests)
		{
			if (!state.hasQuest(q))
			{
				out.add(new Missing(Missing.Kind.QUEST, "Quest: " + titleCase(q)));
			}
		}
		if (state.getQuestPoints() < questPoints)
		{
			out.add(new Missing(Missing.Kind.QUEST_POINTS,
				"Quest points " + questPoints + " (you have " + state.getQuestPoints() + ")"));
		}
		if (state.getCombatLevel() < combatLevel)
		{
			out.add(new Missing(Missing.Kind.COMBAT_LEVEL,
				"Combat level " + combatLevel + " (you have " + state.getCombatLevel() + ")"));
		}
		for (ItemNeed need : items)
		{
			long owned = 0;
			for (int id : need.getAnyOf())
			{
				owned += state.ownedCount(id);
			}
			if (owned < need.getQty())
			{
				String label = need.getName() != null && !need.getName().isEmpty()
					? need.getName() + " x" + need.getQty()
					: "any of " + need.getAnyOf().size() + " items";
				out.add(new Missing(Missing.Kind.ITEM, label));
			}
		}
		if (minMaxHit > 0 && state.getMaxHit() < minMaxHit)
		{
			out.add(new Missing(Missing.Kind.MAX_HIT,
				"Max hit " + minMaxHit + " (you have " + state.getMaxHit() + ")"));
		}
		for (int c : chunks)
		{
			if (!state.hasUnlockedChunk(c))
			{
				out.add(new Missing(Missing.Kind.CHUNK, "Chunk " + c + " not unlocked"));
			}
		}
		for (String u : unknown)
		{
			out.add(new Missing(Missing.Kind.UNKNOWN, u));
		}
		return out;
	}

	public Requirement merge(Requirement other)
	{
		Objects.requireNonNull(other, "other");
		Builder b = new Builder();
		b.skills.putAll(skills);
		other.skills.forEach((k, v) -> b.skills.merge(k, v, Math::max));
		b.quests.addAll(quests);
		b.quests.addAll(other.quests);
		b.questPoints = Math.max(questPoints, other.questPoints);
		b.combatLevel = Math.max(combatLevel, other.combatLevel);
		b.items.addAll(items);
		b.items.addAll(other.items);
		b.minMaxHit = Math.max(minMaxHit, other.minMaxHit);
		b.chunks.addAll(chunks);
		b.chunks.addAll(other.chunks);
		b.unknown.addAll(unknown);
		for (String u : other.unknown)
		{
			if (!b.unknown.contains(u))
			{
				b.unknown.add(u);
			}
		}
		return b.build();
	}

	private static String titleCase(String id)
	{
		StringBuilder sb = new StringBuilder();
		for (String word : id.split("_"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	public static final class Builder
	{
		private final Map<String, Integer> skills = new LinkedHashMap<>();
		private final Set<String> quests = new LinkedHashSet<>();
		private int questPoints;
		private int combatLevel;
		private final List<ItemNeed> items = new ArrayList<>();
		private int minMaxHit;
		private final Set<Integer> chunks = new LinkedHashSet<>();
		private final List<String> unknown = new ArrayList<>();

		private Builder()
		{
		}

		public Builder skill(String skill, int level)
		{
			skills.merge(skill, level, Math::max);
			return this;
		}

		public Builder quest(String id)
		{
			quests.add(id);
			return this;
		}

		public Builder questPoints(int points)
		{
			this.questPoints = points;
			return this;
		}

		public Builder combatLevel(int level)
		{
			this.combatLevel = level;
			return this;
		}

		public Builder item(ItemNeed need)
		{
			items.add(Objects.requireNonNull(need, "need"));
			return this;
		}

		public Builder minMaxHit(int hit)
		{
			this.minMaxHit = hit;
			return this;
		}

		public Builder chunk(int regionId)
		{
			chunks.add(regionId);
			return this;
		}

		public Builder unknown(String description)
		{
			unknown.add(description);
			return this;
		}

		public Requirement build()
		{
			return new Requirement(this);
		}
	}
}
