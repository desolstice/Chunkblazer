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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerStateTest
{
	@Test
	void freshAccountHasLevelOneAndTenHitpoints()
	{
		PlayerState s = PlayerState.freshAccount();
		assertEquals(1, s.getLevel("ATTACK"));
		assertEquals(10, s.getLevel("HITPOINTS"));
		assertEquals(1, s.getLevel("NOT_A_SKILL"));
		assertEquals(3, s.getCombatLevel());
		assertEquals(0, s.getQuestPoints());
		assertEquals(0, s.getMaxHit());
	}

	@Test
	void withXpProjectsLevelAndLeavesOriginalUntouched()
	{
		PlayerState a = PlayerState.freshAccount();
		PlayerState b = a.withXp("COOKING", 300);
		assertEquals(1, a.getLevel("COOKING"));
		assertEquals(300, b.getXp("COOKING"));
		assertEquals(Math.max(1, XpTable.levelForXp(300)), b.getLevel("COOKING"));
		assertTrue(b.getLevel("COOKING") >= 4);
	}

	@Test
	void questCompletionAddsPointsOnce()
	{
		PlayerState s = PlayerState.freshAccount().withQuestComplete("COOKS_ASSISTANT", 1);
		assertTrue(s.hasQuest("COOKS_ASSISTANT"));
		assertEquals(1, s.getQuestPoints());
		assertEquals(1, s.withQuestComplete("COOKS_ASSISTANT", 1).getQuestPoints());
	}

	@Test
	void itemsAccumulateAndUnknownIsZero()
	{
		PlayerState s = PlayerState.freshAccount().withItem(1944, 2).withItem(1944, 1);
		assertEquals(3, s.ownedCount(1944));
		assertEquals(0, s.ownedCount(1));
	}

	@Test
	void unlockedChunksAndTokens()
	{
		PlayerState s = PlayerState.freshAccount().withUnlockedChunk(12850).withUnlock("TELEPORT:Explorer's ring");
		assertTrue(s.hasUnlockedChunk(12850));
		assertFalse(s.hasUnlockedChunk(1));
		assertTrue(s.hasUnlock("TELEPORT:Explorer's ring"));
	}

	@Test
	void combatLevelFormula()
	{
		PlayerState s = PlayerState.builder().level("ATTACK", 99).level("STRENGTH", 99).level("DEFENCE", 99)
			.level("HITPOINTS", 99).level("PRAYER", 99).level("RANGED", 99).level("MAGIC", 99).build();
		assertEquals(126, s.getCombatLevel());
	}

	@Test
	void toBuilderRoundTripCopiesAllState()
	{
		PlayerState s = PlayerState.builder().level("ATTACK", 40).xp("COOKING", 500).quest("Q1").questPoints(4)
			.combatLevel(50).item(1944, 2).chunk(12850).unlock("T").maxHit(12).build();
		PlayerState c = s.toBuilder().build();
		assertEquals(40, c.getLevel("ATTACK"));
		assertEquals(500, c.getXp("COOKING"));
		assertTrue(c.getQuests().contains("Q1"));
		assertEquals(4, c.getQuestPoints());
		assertEquals(50, c.getCombatLevel());
		assertEquals(2, c.getItems().get(1944));
		assertTrue(c.getUnlockedChunks().contains(12850));
		assertTrue(c.getUnlocks().contains("T"));
		assertEquals(12, c.getMaxHit());
		assertThrows(UnsupportedOperationException.class, () -> c.getQuests().add("x"));
		PlayerState d = c.toBuilder().level("ATTACK", 41).build();
		assertEquals(40, c.getLevel("ATTACK"));
		assertEquals(41, d.getLevel("ATTACK"));
	}

	@Test
	void taskPointsDefaultToZeroAndAreCopiedByEveryWith()
	{
		PlayerState s = PlayerState.freshAccount();
		assertEquals(0, s.getTaskPoints());
		PlayerState t = s.withTaskPoints(4).withTaskPoints(3);
		assertEquals(0, s.getTaskPoints());
		assertEquals(7, t.getTaskPoints());
		PlayerState u = t.withXp("COOKING", 10).withItem(1, 1).withUnlock("X").withQuestComplete("Q", 1)
			.withUnlockedChunk(5).toBuilder().level("ATTACK", 3).build();
		assertEquals(7, u.getTaskPoints());
		assertEquals(9, PlayerState.builder().taskPoints(9).build().getTaskPoints());
	}
}
