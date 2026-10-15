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
package com.chunkblazer.gps.adapter;

import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.XpTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutable editor model for the simulate mode. It holds its own collections (so it can remove
 * quests and chunks, which the immutable {@link PlayerState} builder cannot) and produces a
 * {@link PlayerState} on {@link #build()}. Not thread safe.
 */
public final class SimulatedState
{
	private static final String[] ALL_SKILLS = {
		"ATTACK", "DEFENCE", "STRENGTH", "HITPOINTS", "RANGED", "PRAYER", "MAGIC", "COOKING", "WOODCUTTING",
		"FLETCHING", "FISHING", "FIREMAKING", "CRAFTING", "SMITHING", "MINING", "HERBLORE", "AGILITY", "THIEVING",
		"SLAYER", "FARMING", "RUNECRAFT", "HUNTER", "CONSTRUCTION"
	};

	private final Map<String, Integer> levels = new HashMap<>();
	private final Map<String, Long> xp = new HashMap<>();
	private final Set<String> quests = new HashSet<>();
	private final Set<Integer> chunks = new HashSet<>();
	private final Map<Integer, Integer> items = new HashMap<>();
	private final Set<String> unlocks = new HashSet<>();
	private int questPoints;
	private int maxHit;

	private SimulatedState()
	{
	}

	public static SimulatedState fresh()
	{
		return new SimulatedState();
	}

	public static SimulatedState fromReal(PlayerState real)
	{
		SimulatedState s = new SimulatedState();
		// Resolved through the getters so defaults apply.
		for (String skill : ALL_SKILLS)
		{
			s.levels.put(skill, real.getLevel(skill));
			s.xp.put(skill, real.getXp(skill));
		}
		s.quests.addAll(real.getQuests());
		s.chunks.addAll(real.getUnlockedChunks());
		s.items.putAll(real.getItems());
		s.unlocks.addAll(real.getUnlocks());
		s.questPoints = real.getQuestPoints();
		s.maxHit = real.getMaxHit();
		return s;
	}

	public void setLevel(String skill, int level)
	{
		int clamped = Math.max(1, Math.min(99, level));
		levels.put(skill, clamped);
		// Keep xp consistent with the edited level so PlayerState.getXp() doesn't return stale xp.
		xp.put(skill, (long) XpTable.xpForLevel(clamped));
	}

	public void toggleQuest(String id)
	{
		if (!quests.remove(id))
		{
			quests.add(id);
		}
	}

	public void setChunk(int regionId, boolean unlocked)
	{
		if (unlocked)
		{
			chunks.add(regionId);
		}
		else
		{
			chunks.remove(regionId);
		}
	}

	public void setItem(int id, int qty)
	{
		if (qty <= 0)
		{
			items.remove(id);
		}
		else
		{
			items.put(id, qty);
		}
	}

	public void setMaxHit(int maxHit)
	{
		this.maxHit = Math.max(0, maxHit);
	}

	public void setQuestPoints(int questPoints)
	{
		this.questPoints = Math.max(0, questPoints);
	}

	public PlayerState build()
	{
		PlayerState.Builder b = PlayerState.builder();
		levels.forEach(b::level);
		xp.forEach(b::xp);
		quests.forEach(b::quest);
		chunks.forEach(b::chunk);
		items.forEach(b::item);
		unlocks.forEach(b::unlock);
		return b.questPoints(questPoints).maxHit(maxHit).build();
	}
}
