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
import com.chunkblazer.gps.combat.MagicSpells;
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
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Reads the live RuneLite client into an immutable {@link PlayerState}. Only the adapter package
 * of the GPS touches the RuneLite API.
 *
 * <p>Max hit uses the worn equipment's strength, ranged strength and magic damage bonuses, with
 * fixed assumptions: aggressive melee, accurate ranged, no prayer and no boosts.
 */
public final class PlayerStateProvider
{
	private final Client client;
	private final Supplier<Set<String>> unlockedRegionIds;
	private final Predicate<String> questFinished;
	private final Supplier<Map<Integer, Integer>> bankItems;
	private final ItemManager itemManager;

	public PlayerStateProvider(Client client, Supplier<Set<String>> unlockedRegionIds,
		Predicate<String> questFinished, Supplier<Map<Integer, Integer>> bankItems, ItemManager itemManager)
	{
		this.client = client;
		this.unlockedRegionIds = unlockedRegionIds;
		this.questFinished = questFinished;
		this.bankItems = bankItems;
		this.itemManager = itemManager;
	}

	/**
	 * The max hit is computed from worn gear, so it is no longer a level-only lower bound.
	 */
	public boolean isMaxHitApproximate()
	{
		return false;
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

		GearBonuses gear = wornBonuses();
		CombatStats meleeStats = combatStats(strength, ranged, magic, gear, CombatStats.Style.AGGRESSIVE);
		CombatStats rangedStats = combatStats(strength, ranged, magic, gear, CombatStats.Style.ACCURATE);
		int maxHit = Math.max(MaxHitCalculator.melee(meleeStats), MaxHitCalculator.ranged(rangedStats));
		maxHit = Math.max(maxHit, MaxHitCalculator.best(meleeStats, MagicSpells.available(magic)));
		b.maxHit(maxHit);
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

	private static CombatStats combatStats(int strength, int ranged, int magic, GearBonuses gear, CombatStats.Style style)
	{
		return CombatStats.builder()
			.strength(strength)
			.ranged(ranged)
			.magic(magic)
			.strengthBonus(gear.getStrength())
			.rangedStrengthBonus(gear.getRangedStrength())
			.magicDamagePercent((int) Math.round(gear.getMagicDamagePercent()))
			.style(style)
			.build();
	}

	/** Sums the worn items' bonuses; unknown items, null stats or a failing lookup contribute zero. */
	private GearBonuses wornBonuses()
	{
		List<GearBonuses.Triple> triples = new ArrayList<>();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (itemManager == null || worn == null || worn.getItems() == null)
		{
			return GearBonuses.sum(triples);
		}
		for (Item item : worn.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			try
			{
				ItemStats stats = itemManager.getItemStats(item.getId());
				ItemEquipmentStats eq = stats == null ? null : stats.getEquipment();
				if (eq != null)
				{
					triples.add(new GearBonuses.Triple(eq.getStr(), eq.getRstr(), eq.getMdmg()));
				}
			}
			catch (RuntimeException ex)
			{
				// unknown item: contributes nothing
			}
		}
		return GearBonuses.sum(triples);
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

	private void addContainer(Map<Integer, Integer> owned, ItemContainer container)
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
				add(owned, canonical(item.getId()), item.getQuantity());
			}
		}
	}

	/** Noted items count as their base item; an unknown or failing lookup keeps the raw id. */
	private int canonical(int id)
	{
		if (itemManager == null || id <= 0)
		{
			return id;
		}
		try
		{
			int c = itemManager.canonicalize(id);
			return c > 0 ? c : id;
		}
		catch (RuntimeException ex)
		{
			return id;
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
}
