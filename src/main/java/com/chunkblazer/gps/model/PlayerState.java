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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of a player's progression. Skills are RuneLite Skill enum names (upper case).
 */
public final class PlayerState
{
	private final Map<String, Long> xp;
	private final Map<String, Integer> levels;
	private final Set<String> quests;
	private final int questPoints;
	private final Integer combatOverride;
	private final Map<Integer, Integer> items;
	private final Set<Integer> chunks;
	private final Set<String> unlocks;
	private final int maxHit;

	private PlayerState(Builder b)
	{
		this.xp = Collections.unmodifiableMap(new HashMap<>(b.xp));
		this.levels = Collections.unmodifiableMap(new HashMap<>(b.levels));
		this.quests = Collections.unmodifiableSet(new HashSet<>(b.quests));
		this.questPoints = b.questPoints;
		this.combatOverride = b.combatOverride;
		this.items = Collections.unmodifiableMap(new HashMap<>(b.items));
		this.chunks = Collections.unmodifiableSet(new HashSet<>(b.chunks));
		this.unlocks = Collections.unmodifiableSet(new HashSet<>(b.unlocks));
		this.maxHit = b.maxHit;
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public static PlayerState freshAccount()
	{
		return builder().build();
	}

	public Builder toBuilder()
	{
		Builder b = new Builder();
		b.xp.putAll(xp);
		b.levels.putAll(levels);
		b.quests.addAll(quests);
		b.questPoints = questPoints;
		b.combatOverride = combatOverride;
		b.items.putAll(items);
		b.chunks.addAll(chunks);
		b.unlocks.addAll(unlocks);
		b.maxHit = maxHit;
		return b;
	}

	private static int defaultLevel(String skill)
	{
		return "HITPOINTS".equals(skill) ? 10 : 1;
	}

	public int getLevel(String skill)
	{
		Integer l = levels.get(skill);
		return l != null ? l : defaultLevel(skill);
	}

	public long getXp(String skill)
	{
		Long x = xp.get(skill);
		return x != null ? x : XpTable.xpForLevel(getLevel(skill));
	}

	public boolean hasQuest(String id)
	{
		return quests.contains(id);
	}

	public Set<String> getQuests()
	{
		return quests;
	}

	public int getQuestPoints()
	{
		return questPoints;
	}

	public int getMaxHit()
	{
		return maxHit;
	}

	public int getCombatLevel()
	{
		if (combatOverride != null)
		{
			return combatOverride;
		}
		int defence = getLevel("DEFENCE");
		int hitpoints = getLevel("HITPOINTS");
		int prayer = getLevel("PRAYER");
		double base = 0.25 * (defence + hitpoints + Math.floor(prayer / 2.0));
		double melee = 0.325 * (getLevel("ATTACK") + getLevel("STRENGTH"));
		double ranged = 0.325 * Math.floor(1.5 * getLevel("RANGED"));
		double mage = 0.325 * Math.floor(1.5 * getLevel("MAGIC"));
		return (int) Math.floor(base + Math.max(melee, Math.max(ranged, mage)));
	}

	public int ownedCount(int itemId)
	{
		Integer c = items.get(itemId);
		return c != null ? c : 0;
	}

	public Map<Integer, Integer> getItems()
	{
		return items;
	}

	public boolean hasUnlockedChunk(int regionId)
	{
		return chunks.contains(regionId);
	}

	public Set<Integer> getUnlockedChunks()
	{
		return chunks;
	}

	public boolean hasUnlock(String token)
	{
		return unlocks.contains(token);
	}

	public Set<String> getUnlocks()
	{
		return unlocks;
	}

	public PlayerState withXp(String skill, long delta)
	{
		Builder b = toBuilder();
		long next = getXp(skill) + delta;
		b.xp.put(skill, next);
		b.levels.put(skill, Math.max(getLevel(skill), XpTable.levelForXp(next)));
		return b.build();
	}

	public PlayerState withQuestComplete(String id, int points)
	{
		if (quests.contains(id))
		{
			return this;
		}
		Builder b = toBuilder();
		b.quests.add(id);
		b.questPoints += points;
		return b.build();
	}

	public PlayerState withItem(int itemId, int qty)
	{
		Builder b = toBuilder();
		b.items.merge(itemId, qty, Integer::sum);
		return b.build();
	}

	public PlayerState withUnlockedChunk(int regionId)
	{
		Builder b = toBuilder();
		b.chunks.add(regionId);
		return b.build();
	}

	public PlayerState withUnlock(String token)
	{
		Builder b = toBuilder();
		b.unlocks.add(token);
		return b.build();
	}

	public static final class Builder
	{
		private final Map<String, Long> xp = new HashMap<>();
		private final Map<String, Integer> levels = new HashMap<>();
		private final Set<String> quests = new HashSet<>();
		private int questPoints;
		private Integer combatOverride;
		private final Map<Integer, Integer> items = new HashMap<>();
		private final Set<Integer> chunks = new HashSet<>();
		private final Set<String> unlocks = new HashSet<>();
		private int maxHit;

		private Builder()
		{
		}

		public Builder level(String skill, int level)
		{
			levels.put(skill, level);
			return this;
		}

		public Builder xp(String skill, long value)
		{
			xp.put(skill, value);
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
			this.combatOverride = level;
			return this;
		}

		public Builder item(int id, int qty)
		{
			items.put(id, qty);
			return this;
		}

		public Builder chunk(int regionId)
		{
			chunks.add(regionId);
			return this;
		}

		public Builder unlock(String token)
		{
			unlocks.add(token);
			return this;
		}

		public Builder maxHit(int maxHit)
		{
			this.maxHit = maxHit;
			return this;
		}

		public PlayerState build()
		{
			return new PlayerState(this);
		}
	}
}
