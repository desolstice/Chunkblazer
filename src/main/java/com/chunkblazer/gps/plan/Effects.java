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
package com.chunkblazer.gps.plan;

import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.XpTable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable state changes caused by completing a plan node. Iteration order is insertion order, so applying
 * effects is deterministic.
 */
public final class Effects
{
	public static final Effects NONE = builder().build();

	private final Map<String, Long> xp;
	private final Map<String, Integer> reachLevel;
	private final Map<String, Integer> questsCompleted;
	private final Map<Integer, Integer> items;
	private final Set<String> unlocks;
	private final int taskPoints;

	private Effects(Builder b)
	{
		this.xp = Collections.unmodifiableMap(new LinkedHashMap<>(b.xp));
		this.reachLevel = Collections.unmodifiableMap(new LinkedHashMap<>(b.reachLevel));
		this.questsCompleted = Collections.unmodifiableMap(new LinkedHashMap<>(b.questsCompleted));
		this.items = Collections.unmodifiableMap(new LinkedHashMap<>(b.items));
		this.unlocks = Collections.unmodifiableSet(new LinkedHashSet<>(b.unlocks));
		this.taskPoints = b.taskPoints;
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public Map<String, Long> getXp()
	{
		return xp;
	}

	public Map<String, Integer> getReachLevel()
	{
		return reachLevel;
	}

	/** Quest id to quest points awarded. */
	public Map<String, Integer> getQuestsCompleted()
	{
		return questsCompleted;
	}

	public Map<Integer, Integer> getItems()
	{
		return items;
	}

	public Set<String> getUnlocks()
	{
		return unlocks;
	}

	public int getTaskPoints()
	{
		return taskPoints;
	}

	/** Order: reachLevel, xp, quests, items, unlocks, task points. */
	public PlayerState apply(PlayerState s)
	{
		PlayerState r = s;
		for (Map.Entry<String, Integer> e : reachLevel.entrySet())
		{
			long target = XpTable.xpForLevel(e.getValue());
			long current = r.getXp(e.getKey());
			if (current < target)
			{
				r = r.withXp(e.getKey(), target - current);
			}
		}
		for (Map.Entry<String, Long> e : xp.entrySet())
		{
			r = r.withXp(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, Integer> e : questsCompleted.entrySet())
		{
			r = r.withQuestComplete(e.getKey(), e.getValue());
		}
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			r = r.withItem(e.getKey(), e.getValue());
		}
		for (String u : unlocks)
		{
			r = r.withUnlock(u);
		}
		if (taskPoints != 0)
		{
			r = r.withTaskPoints(taskPoints);
		}
		return r;
	}

	/** Union: xp, items and points are summed, reachLevel takes the max, quests and unlocks are unioned. */
	public Effects plus(Effects o)
	{
		Builder b = new Builder();
		xp.forEach(b::xp);
		o.xp.forEach(b::xp);
		reachLevel.forEach(b::reachLevel);
		o.reachLevel.forEach(b::reachLevel);
		questsCompleted.forEach(b::questComplete);
		o.questsCompleted.forEach(b::questComplete);
		items.forEach(b::item);
		o.items.forEach(b::item);
		unlocks.forEach(b::unlock);
		o.unlocks.forEach(b::unlock);
		b.taskPoints(taskPoints + o.taskPoints);
		return b.build();
	}

	public static final class Builder
	{
		private final Map<String, Long> xp = new LinkedHashMap<>();
		private final Map<String, Integer> reachLevel = new LinkedHashMap<>();
		private final Map<String, Integer> questsCompleted = new LinkedHashMap<>();
		private final Map<Integer, Integer> items = new LinkedHashMap<>();
		private final Set<String> unlocks = new LinkedHashSet<>();
		private int taskPoints;

		private Builder()
		{
		}

		/** Adds to any xp already recorded for the skill. */
		public Builder xp(String skill, long amount)
		{
			xp.merge(skill, amount, Long::sum);
			return this;
		}

		/** Keeps the highest level requested for the skill. */
		public Builder reachLevel(String skill, int level)
		{
			reachLevel.merge(skill, level, Math::max);
			return this;
		}

		public Builder questComplete(String id, int questPoints)
		{
			questsCompleted.put(id, questPoints);
			return this;
		}

		/** Adds to any quantity already recorded for the item. */
		public Builder item(int itemId, int qty)
		{
			items.merge(itemId, qty, Integer::sum);
			return this;
		}

		public Builder unlock(String token)
		{
			unlocks.add(token);
			return this;
		}

		/** Sets (replaces) the task points awarded. */
		public Builder taskPoints(int points)
		{
			this.taskPoints = points;
			return this;
		}

		public Effects build()
		{
			return new Effects(this);
		}
	}
}
