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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chunkblazer.gps.combat.CombatStats;
import com.chunkblazer.gps.combat.MagicSpells;
import com.chunkblazer.gps.combat.MaxHitCalculator;
import com.chunkblazer.gps.model.PlayerState;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import org.junit.jupiter.api.Test;

class PlayerStateProviderTest
{
	private static Client baseClient()
	{
		Client client = mock(Client.class);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		when(client.getLocalPlayer()).thenReturn(null);
		return client;
	}

	private static ItemContainer container(Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		return c;
	}

	@Test
	void mapsClientSkillsAndChunksAndSkipsBadChunkIds()
	{
		Client client = mock(Client.class);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getRealSkillLevel(Skill.COOKING)).thenReturn(33);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		when(client.getLocalPlayer()).thenReturn(null);
		PlayerStateProvider p = new PlayerStateProvider(client, () -> new HashSet<>(Arrays.asList("12850", "oops")),
			q -> q.equals("COOKS_ASSISTANT"), Collections::emptyMap, null);
		PlayerState s = p.snapshot();
		assertEquals(33, s.getLevel("COOKING"));
		assertTrue(s.hasUnlockedChunk(12850));
		assertEquals(1, s.getUnlockedChunks().size());
		assertTrue(s.hasQuest("COOKS_ASSISTANT"));
		assertEquals(0, s.ownedCount(1));
	}

	@Test
	void mergesInventoryWornAndBank()
	{
		Client client = baseClient();
		ItemContainer inv = container(new Item(100, 2), new Item(200, 1));
		ItemContainer worn = container(new Item(100, 1), new Item(300, 1));
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		Map<Integer, Integer> bank = new HashMap<>();
		bank.put(100, 10);
		bank.put(400, 5);
		PlayerState s = new PlayerStateProvider(client, Collections::emptySet, q -> false, () -> bank, null).snapshot();
		assertEquals(13, s.ownedCount(100));
		assertEquals(1, s.ownedCount(200));
		assertEquals(1, s.ownedCount(300));
		assertEquals(5, s.ownedCount(400));
	}

	@Test
	void notedItemsCountAsTheirCanonicalId()
	{
		Client client = baseClient();
		ItemContainer inv = container(new Item(1001, 3), new Item(1002, 2));
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		ItemManager items = mock(ItemManager.class);
		when(items.canonicalize(1001)).thenReturn(1000);
		when(items.canonicalize(1002)).thenReturn(0);
		PlayerState s = new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, items)
			.snapshot();
		assertEquals(3, s.ownedCount(1000));
		assertEquals(0, s.ownedCount(1001));
		assertEquals(2, s.ownedCount(1002));
	}

	@Test
	void ignoresBadItemsAndToleratesNulls()
	{
		Client client = baseClient();
		ItemContainer inv = container(new Item(-1, 1), new Item(0, 5), new Item(7, 0), new Item(8, 2));
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);
		Map<Integer, Integer> bank = new HashMap<>();
		bank.put(0, 3);
		bank.put(9, -1);
		PlayerState s = new PlayerStateProvider(client, Collections::emptySet, q -> false, () -> bank, null).snapshot();
		assertEquals(1, s.getItems().size());
		assertEquals(2, s.ownedCount(8));

		PlayerState s2 = new PlayerStateProvider(client, () -> null, q -> false, () -> null, null).snapshot();
		assertEquals(2, s2.ownedCount(8));
		assertTrue(s2.getUnlockedChunks().isEmpty());
	}

	@Test
	void questPredicateThrowingIsTolerated()
	{
		PlayerStateProvider p = new PlayerStateProvider(baseClient(), Collections::emptySet, q ->
		{
			if (q.equals("COOKS_ASSISTANT"))
			{
				throw new IllegalStateException("boom");
			}
			return q.equals("DRUIDIC_RITUAL");
		}, Collections::emptyMap, null);
		PlayerState s = p.snapshot();
		assertTrue(s.hasQuest("DRUIDIC_RITUAL"));
		assertFalse(s.hasQuest("COOKS_ASSISTANT"));
	}

	@Test
	void maxHitFromLevelsOnlyWhenNothingWorn()
	{
		Client client = baseClient();
		when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(99);
		PlayerStateProvider p = new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap,
			mock(ItemManager.class));
		int melee = MaxHitCalculator.melee(CombatStats.builder().strength(99).build());
		assertTrue(melee > 0);
		assertEquals(melee, p.snapshot().getMaxHit());
		assertFalse(p.isMaxHitApproximate());
	}

	@Test
	void maxHitUsesWornGearStrength()
	{
		Client client = baseClient();
		when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(99);
		ItemContainer wornC = container(new Item(4151, 1));
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(wornC);
		ItemManager items = mock(ItemManager.class);
		when(items.getItemStats(4151)).thenReturn(stats(82, 0, 0f));
		PlayerStateProvider p = new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, items);
		int expected = MaxHitCalculator.melee(CombatStats.builder().strength(99).strengthBonus(82).build());
		assertTrue(expected >= 25);
		assertEquals(expected, p.snapshot().getMaxHit());
	}

	@Test
	void wornItemWithNullStatsOrNullEquipmentIsIgnored()
	{
		Client client = baseClient();
		when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(99);
		ItemContainer wornC = container(new Item(4151, 1), new Item(555, 1), new Item(-1, 1), null);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(wornC);
		ItemManager items = mock(ItemManager.class);
		when(items.getItemStats(4151)).thenReturn(null);
		when(items.getItemStats(555)).thenReturn(new ItemStats(false, 0, 0, null));
		PlayerStateProvider p = new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, items);
		int melee = MaxHitCalculator.melee(CombatStats.builder().strength(99).build());
		assertEquals(melee, p.snapshot().getMaxHit());
	}

	@Test
	void nullItemManagerOrThrowingStatsLookupIsTolerated()
	{
		Client client = baseClient();
		when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(99);
		ItemContainer wornC = container(new Item(4151, 1));
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(wornC);
		int melee = MaxHitCalculator.melee(CombatStats.builder().strength(99).build());
		assertEquals(melee, new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, null)
			.snapshot().getMaxHit());
		ItemManager items = mock(ItemManager.class);
		when(items.getItemStats(anyInt())).thenThrow(new IllegalStateException("boom"));
		assertEquals(melee, new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, items)
			.snapshot().getMaxHit());
	}

	@Test
	void maxHitUsesRangedStrengthAndMagicDamage()
	{
		Client client = baseClient();
		when(client.getRealSkillLevel(Skill.RANGED)).thenReturn(99);
		when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(99);
		ItemContainer wornC = container(new Item(1, 1), new Item(2, 1));
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(wornC);
		ItemManager items = mock(ItemManager.class);
		when(items.getItemStats(1)).thenReturn(stats(0, 60, 0f));
		when(items.getItemStats(2)).thenReturn(stats(0, 0, 15f));
		PlayerStateProvider p = new PlayerStateProvider(client, Collections::emptySet, q -> false, Collections::emptyMap, items);
		CombatStats cs = CombatStats.builder().ranged(99).magic(99).style(CombatStats.Style.ACCURATE)
			.rangedStrengthBonus(60).magicDamagePercent(15).build();
		assertEquals(MaxHitCalculator.best(cs, MagicSpells.available(99)), p.snapshot().getMaxHit());
	}

	private static ItemStats stats(int str, int rstr, float mdmg)
	{
		return new ItemStats(true, 0, 0, ItemEquipmentStats.builder().str(str).rstr(rstr).mdmg(mdmg).build());
	}
}
