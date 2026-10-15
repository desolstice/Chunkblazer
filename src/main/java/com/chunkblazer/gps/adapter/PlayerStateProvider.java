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

import com.chunkblazer.gps.combat.CombatStats;
import com.chunkblazer.gps.combat.MaxHitCalculator;
import com.chunkblazer.gps.model.PlayerState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Reads the live RuneLite client into an immutable {@link PlayerState}. Only the adapter package
 * of the GPS touches the RuneLite API.
 *
 * <p>Limitation (M2): equipment bonuses are not read, so the max hit is a level-only lower bound
 * (see {@link #isMaxHitApproximate()}); gear bonuses are wired in M5.
 */
public final class PlayerStateProvider
{
	/** Standard spellbook combat spells; level requirements per tier in element order (wind, water, earth, fire). */
	private static final String[] ELEMENTS = {"wind", "water", "earth", "fire"};
	private static final String[] TIERS = {"strike", "bolt", "blast", "wave", "surge"};
	private static final int[][] TIER_LEVELS = {
		{1, 5, 9, 13}, {17, 23, 29, 35}, {41, 47, 53, 59}, {62, 66, 70, 75}, {81, 85, 90, 95}
	};

	private final Client client;
	private final Supplier<Set<String>> unlockedRegionIds;
	private final Predicate<String> questFinished;
	private final Supplier<Map<Integer, Integer>> bankItems;

	public PlayerStateProvider(Client client, Supplier<Set<String>> unlockedRegionIds,
		Predicate<String> questFinished, Supplier<Map<Integer, Integer>> bankItems)
	{
		this.client = client;
		this.unlockedRegionIds = unlockedRegionIds;
		this.questFinished = questFinished;
		this.bankItems = bankItems;
	}

	/**
	 * The max hit in real mode ignores equipment, so it is a lower bound.
	 */
	public boolean isMaxHitApproximate()
	{
		return true;
	}

	/**
	 * Builds a snapshot of the current client state. Must be called on the client thread.
	 */
	public PlayerState snapshot()
	{
		PlayerState.Builder b = PlayerState.builder();

		int strength = 1;
		int ranged = 1;
		int magic = 1;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			b.level(skill.name(), level);
			b.xp(skill.name(), client.getSkillExperience(skill));
			if (skill == Skill.STRENGTH)
			{
				strength = level;
			}
			else if (skill == Skill.RANGED)
			{
				ranged = level;
			}
			else if (skill == Skill.MAGIC)
			{
				magic = level;
			}
		}

		for (Quest quest : Quest.values())
		{
			if (isFinished(quest.name()))
			{
				b.quest(quest.name());
			}
		}
		b.questPoints(client.getVarpValue(VarPlayerID.QP));

		for (Map.Entry<Integer, Integer> e : ownedItems().entrySet())
		{
			b.item(e.getKey(), e.getValue());
		}

		Set<String> regions = unlockedRegionIds == null ? null : unlockedRegionIds.get();
		if (regions != null)
		{
			for (String r : regions)
			{
				try
				{
					b.chunk(Integer.parseInt(r.trim()));
				}
				catch (RuntimeException ex)
				{
					// bad entry (NumberFormatException or null): skip
				}
			}
		}

		Player player = client.getLocalPlayer();
		if (player != null)
		{
			b.combatLevel(player.getCombatLevel());
		}

		CombatStats stats = CombatStats.builder()
			.strength(strength)
			.ranged(ranged)
			.magic(magic)
			.style(CombatStats.Style.AGGRESSIVE)
			.build();
		b.maxHit(MaxHitCalculator.best(stats, availableSpells(magic)));
		return b.build();
	}

	private boolean isFinished(String questName)
	{
		try
		{
			return questFinished != null && questFinished.test(questName);
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private Map<Integer, Integer> ownedItems()
	{
		Map<Integer, Integer> owned = new HashMap<>();
		addContainer(owned, client.getItemContainer(InventoryID.INV));
		addContainer(owned, client.getItemContainer(InventoryID.WORN));
		Map<Integer, Integer> bank = bankItems == null ? null : bankItems.get();
		if (bank != null)
		{
			for (Map.Entry<Integer, Integer> e : bank.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null)
				{
					add(owned, e.getKey(), e.getValue());
				}
			}
		}
		return owned;
	}

	private static void addContainer(Map<Integer, Integer> owned, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item != null)
			{
				add(owned, item.getId(), item.getQuantity());
			}
		}
	}

	private static void add(Map<Integer, Integer> owned, int id, int qty)
	{
		if (id <= 0 || qty <= 0)
		{
			return;
		}
		owned.merge(id, qty, Integer::sum);
	}

	/**
	 * The best standard-spellbook combat spell of each tier (strike to surge) usable at the given
	 * magic level, e.g. level 64 yields fire strike, fire bolt, fire blast and wind wave.
	 */
	static List<String> availableSpells(int magicLevel)
	{
		List<String> spells = new ArrayList<>();
		for (int t = 0; t < TIERS.length; t++)
		{
			String best = null;
			for (int e = 0; e < ELEMENTS.length; e++)
			{
				if (magicLevel >= TIER_LEVELS[t][e])
				{
					best = ELEMENTS[e] + " " + TIERS[t];
				}
			}
			if (best != null)
			{
				spells.add(best);
			}
		}
		return spells;
	}
}
