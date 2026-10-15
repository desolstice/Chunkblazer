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

import com.chunkblazer.gps.model.PlayerState;
import org.junit.jupiter.api.Test;

class SimulatedStateTest
{
	@Test
	void editsProduceMatchingState()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setLevel("COOKING", 40);
		s.toggleQuest("COOKS_ASSISTANT");
		s.setChunk(12850, true);
		s.setItem(1944, 2);
		PlayerState p = s.build();
		assertEquals(40, p.getLevel("COOKING"));
		assertTrue(p.hasQuest("COOKS_ASSISTANT"));
		assertTrue(p.hasUnlockedChunk(12850));
		assertEquals(2, p.ownedCount(1944));
		s.toggleQuest("COOKS_ASSISTANT");
		assertFalse(s.build().hasQuest("COOKS_ASSISTANT"));
	}

	@Test
	void fromRealCopiesAndDoesNotAlias()
	{
		PlayerState real = PlayerState.builder().level("MINING", 50).build();
		SimulatedState s = SimulatedState.fromReal(real);
		s.setLevel("MINING", 60);
		assertEquals(50, real.getLevel("MINING"));
		assertEquals(60, s.build().getLevel("MINING"));
	}

	@Test
	void levelClampedToValidRange()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setLevel("COOKING", 150);
		assertEquals(99, s.build().getLevel("COOKING"));
		s.setLevel("COOKING", -3);
		assertEquals(1, s.build().getLevel("COOKING"));
	}

	@Test
	void setChunkFalseRemoves()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setChunk(1, true);
		s.setChunk(2, true);
		s.setChunk(1, false);
		PlayerState p = s.build();
		assertFalse(p.hasUnlockedChunk(1));
		assertTrue(p.hasUnlockedChunk(2));
	}

	@Test
	void setItemZeroRemoves()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setItem(5, 3);
		assertEquals(3, s.build().ownedCount(5));
		s.setItem(5, 0);
		assertFalse(s.build().getItems().containsKey(5));
	}

	@Test
	void fromRealCopiesEverything()
	{
		PlayerState real = PlayerState.builder().level("MINING", 50).xp("MINING", 123456L).quest("Q").chunk(7)
			.item(9, 4).maxHit(11).questPoints(12).unlock("TOKEN").build();
		PlayerState p = SimulatedState.fromReal(real).build();
		assertEquals(123456L, p.getXp("MINING"));
		assertTrue(p.hasQuest("Q"));
		assertTrue(p.hasUnlockedChunk(7));
		assertEquals(4, p.ownedCount(9));
		// maxHit is recomputed from the simulated levels, not carried over (see fromRealDoesNotFreezeMaxHit).
		assertEquals(12, p.getQuestPoints());
		assertTrue(p.hasUnlock("TOKEN"));
	}

	@Test
	void maxHitAndQuestPointsEditable()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setMaxHit(25);
		s.setQuestPoints(30);
		PlayerState p = s.build();
		assertEquals(25, p.getMaxHit());
		assertEquals(30, p.getQuestPoints());
	}

	@Test
	void levelEditKeepsXpConsistentWithLevel()
	{
		PlayerState real = PlayerState.builder().level("MINING", 50).xp("MINING", 101333L).build();
		SimulatedState s = SimulatedState.fromReal(real);
		s.setLevel("MINING", 60);
		assertEquals(60, s.build().getLevel("MINING"));
		assertEquals(com.chunkblazer.gps.model.XpTable.xpForLevel(60), s.build().getXp("MINING"));
	}

	@Test
	void raisingStrengthRaisesMaxHit()
	{
		SimulatedState s = SimulatedState.fresh();
		int before = s.build().getMaxHit();
		assertTrue(before > 0, "fresh lower bound is not 0: " + before);
		s.setLevel("STRENGTH", 99);
		assertTrue(s.build().getMaxHit() > before);
	}

	@Test
	void explicitMaxHitOverrides()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setMaxHit(50);
		s.setLevel("STRENGTH", 99);
		assertEquals(50, s.build().getMaxHit());
	}

	@Test
	void fromRealDoesNotFreezeMaxHit()
	{
		PlayerState real = PlayerState.builder().maxHit(1).build();
		SimulatedState s = SimulatedState.fromReal(real);
		s.setLevel("STRENGTH", 99);
		assertTrue(s.build().getMaxHit() > 1);
	}

	@Test
	void taskPointsEditableAndCopiedFromReal()
	{
		SimulatedState s = SimulatedState.fromReal(PlayerState.builder().taskPoints(12).build());
		assertEquals(12, s.build().getTaskPoints());
		s.setTaskPoints(99);
		assertEquals(99, s.build().getTaskPoints());
		s.setTaskPoints(-5);
		assertEquals(0, s.build().getTaskPoints());
	}
}
