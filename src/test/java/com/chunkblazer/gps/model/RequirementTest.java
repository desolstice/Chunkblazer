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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementTest
{
	private static Requirement skills(String skill, int lvl)
	{
		return Requirement.builder().skill(skill, lvl).build();
	}

	@Test
	void noneIsAlwaysMet()
	{
		assertTrue(Requirement.NONE.missing(PlayerState.freshAccount()).isEmpty());
	}

	@Test
	void skillShortfallReported()
	{
		List<Missing> m = skills("COOKING", 20).missing(PlayerState.freshAccount());
		assertEquals(1, m.size());
		assertEquals(Missing.Kind.SKILL, m.get(0).getKind());
		assertTrue(m.get(0).getText().contains("20"));
		assertEquals("Cooking 20 (you have 1)", m.get(0).getText());
	}

	@Test
	void questAndChunkAndItemReported()
	{
		Requirement r = Requirement.builder().quest("COOKS_ASSISTANT").chunk(12850)
			.item(new ItemNeed(new HashSet<>(Arrays.asList(1944)), 1, "Egg")).build();
		PlayerState none = PlayerState.freshAccount();
		assertEquals(3, r.missing(none).size());
		PlayerState ok = none.withQuestComplete("COOKS_ASSISTANT", 1).withUnlockedChunk(12850).withItem(1944, 1);
		assertTrue(r.missing(ok).isEmpty());
	}

	@Test
	void missingTextFormats()
	{
		Requirement r = Requirement.builder().quest("COOKS_ASSISTANT").chunk(12850)
			.item(new ItemNeed(new HashSet<>(Arrays.asList(1944)), 1, "Egg"))
			.item(new ItemNeed(new HashSet<>(Arrays.asList(1, 2, 3)), 1, null)).build();
		List<Missing> m = r.missing(PlayerState.freshAccount());
		assertTrue(m.stream().anyMatch(x -> x.getText().equals("Quest: Cooks Assistant")));
		assertTrue(m.stream().anyMatch(x -> x.getText().equals("Chunk 12850 not unlocked")));
		assertTrue(m.stream().anyMatch(x -> x.getText().equals("Egg x1")));
		assertTrue(m.stream().anyMatch(x -> x.getText().equals("any of 3 items")));
	}

	@Test
	void anyOfItemSatisfiedByAnyId()
	{
		Requirement r = Requirement.builder().item(new ItemNeed(new HashSet<>(Arrays.asList(1, 2, 3)), 1, "Tool")).build();
		assertTrue(r.missing(PlayerState.freshAccount().withItem(2, 1)).isEmpty());
	}

	@Test
	void itemQuantityCountsAcrossAnyOfIds()
	{
		Requirement r = Requirement.builder().item(new ItemNeed(new HashSet<>(Arrays.asList(1, 2)), 3, "Logs")).build();
		assertEquals(1, r.missing(PlayerState.freshAccount().withItem(1, 1).withItem(2, 1)).size());
		assertTrue(r.missing(PlayerState.freshAccount().withItem(1, 2).withItem(2, 1)).isEmpty());
	}

	@Test
	void unknownIsNeverMet()
	{
		Requirement r = Requirement.builder().unknown("varbit 1234").build();
		assertTrue(r.isUnknown());
		assertEquals(Missing.Kind.UNKNOWN, r.missing(PlayerState.freshAccount()).get(0).getKind());
	}

	@Test
	void maxHitNeedChecksMinMaxHit()
	{
		Requirement r = Requirement.builder().minMaxHit(24).build();
		List<Missing> m = r.missing(PlayerState.freshAccount());
		assertEquals(1, m.size());
		assertEquals(Missing.Kind.MAX_HIT, m.get(0).getKind());
		assertEquals("Max hit 24 (you have 1)", m.get(0).getText());
		assertTrue(r.missing(PlayerState.builder().maxHit(24).build()).isEmpty());
	}

	@Test
	void combatLevelShortfallReported()
	{
		Requirement r = Requirement.builder().combatLevel(40).build();
		List<Missing> m = r.missing(PlayerState.freshAccount());
		assertEquals(1, m.size());
		assertEquals(Missing.Kind.COMBAT_LEVEL, m.get(0).getKind());
		assertTrue(m.get(0).getText().contains("40"));
		assertTrue(r.missing(PlayerState.builder().combatLevel(40).build()).isEmpty());
	}

	@Test
	void questPointShortfallReported()
	{
		Requirement r = Requirement.builder().questPoints(10).build();
		List<Missing> m = r.missing(PlayerState.freshAccount());
		assertEquals(1, m.size());
		assertEquals(Missing.Kind.QUEST_POINTS, m.get(0).getKind());
		assertTrue(m.get(0).getText().contains("10"));
		assertTrue(r.missing(PlayerState.builder().questPoints(10).build()).isEmpty());
	}

	@Test
	void mergeTakesStrictest()
	{
		Requirement m = skills("COOKING", 20).merge(skills("COOKING", 30)).merge(skills("MINING", 5));
		assertEquals(30, (int) m.getSkills().get("COOKING"));
		assertEquals(5, (int) m.getSkills().get("MINING"));
	}

	@Test
	void approximateMergesWithOrAndMarksMaxHitText()
	{
		Requirement a = Requirement.builder().minMaxHit(10).approximate(true).build();
		Requirement b = Requirement.builder().minMaxHit(5).build();
		assertTrue(a.merge(b).isApproximate());
		assertTrue(b.merge(a).isApproximate());
		assertTrue(!b.isApproximate());
		assertTrue(a.missing(PlayerState.freshAccount()).get(0).getText().endsWith("(approx.)"));
	}

	@Test
	void mergeCombinesEverythingElse()
	{
		ItemNeed a = new ItemNeed(new HashSet<>(Arrays.asList(1)), 1, "A");
		ItemNeed b = new ItemNeed(new HashSet<>(Arrays.asList(2)), 2, "B");
		Requirement x = Requirement.builder().quest("Q1").chunk(1).unknown("u1").item(a)
			.questPoints(5).combatLevel(30).minMaxHit(10).build();
		Requirement y = Requirement.builder().quest("Q2").chunk(2).unknown("u2").item(b)
			.questPoints(3).combatLevel(50).minMaxHit(4).build();
		Requirement m = x.merge(y);
		assertEquals(new HashSet<>(Arrays.asList("Q1", "Q2")), m.getQuests());
		assertEquals(new HashSet<>(Arrays.asList(1, 2)), m.getChunks());
		assertEquals(Arrays.asList("u1", "u2"), m.getUnknown());
		assertEquals(Arrays.asList(a, b), m.getItems());
		assertEquals(5, m.getQuestPoints());
		assertEquals(50, m.getCombatLevel());
		assertEquals(10, m.getMinMaxHit());
		assertTrue(m.isUnknown());
	}

	@Test
	void itemNeedCopiesSourceSet()
	{
		Set<Integer> src = new HashSet<>(Arrays.asList(1));
		ItemNeed need = new ItemNeed(src, 1, "X");
		src.add(2);
		assertEquals(Collections.singleton(1), need.getAnyOf());
		assertThrows(UnsupportedOperationException.class, () -> need.getAnyOf().add(3));
		Requirement r = Requirement.builder().item(need).build();
		assertThrows(UnsupportedOperationException.class, () -> r.getItems().get(0).getAnyOf().add(3));
	}

	@Test
	void nullsFailFast()
	{
		assertThrows(NullPointerException.class, () -> new ItemNeed(null, 1, "X"));
		assertThrows(NullPointerException.class, () -> Requirement.builder().item(null));
		assertThrows(NullPointerException.class, () -> Requirement.NONE.merge(null));
	}
}
