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
package com.chunkblazer.gps.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.gps.model.PlayerState;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimulationSessionTest
{
	private static NuzlockeChunk chunk(Integer... regions)
	{
		NuzlockeChunk c = new NuzlockeChunk();
		c.setRegionIds(Arrays.asList(regions));
		return c;
	}

	@Test
	void inactiveUntilStartedAndIgnoresEdits()
	{
		SimulationSession s = new SimulationSession();
		assertFalse(s.isActive());
		assertNull(s.view());
		s.setLevel("COOKING", 50);
		assertNull(s.view());
	}

	@Test
	void freshStartHoldsFreeRegionsAndEditsRepublish()
	{
		SimulationSession s = new SimulationSession();
		AtomicInteger changes = new AtomicInteger();
		s.setListener(changes::incrementAndGet);
		s.startFresh(Arrays.asList(7, 8));
		assertTrue(s.isActive());
		assertTrue(s.view().hasUnlockedChunk(7));
		s.setChunk(12850, true);
		s.setLevel("COOKING", 30);
		s.toggleQuest("Q");
		s.setItem(1944, 3);
		s.setTaskPoints(40);
		s.setMaxHit(12);
		PlayerState v = s.view();
		assertTrue(v.hasUnlockedChunk(12850));
		assertEquals(30, v.getLevel("COOKING"));
		assertTrue(v.hasQuest("Q"));
		assertEquals(3, v.ownedCount(1944));
		assertEquals(40, v.getTaskPoints());
		assertEquals(12, v.getMaxHit());
		assertEquals(7, changes.get());
		s.setChunk(12850, false);
		assertFalse(s.view().hasUnlockedChunk(12850));
	}

	@Test
	void copyFromRealThenStopClearsView()
	{
		SimulationSession s = new SimulationSession();
		s.startFromReal(PlayerState.builder().level("MINING", 50).taskPoints(9).chunk(5).build());
		assertEquals(50, s.view().getLevel("MINING"));
		assertEquals(9, s.view().getTaskPoints());
		assertTrue(s.view().hasUnlockedChunk(5));
		s.stop();
		assertFalse(s.isActive());
		assertNull(s.view());
	}

	@Test
	void startingRegionsAreTheFreeOnesAcrossChunks()
	{
		Set<Integer> free = SimulationSession.startingRegions(
			Arrays.asList(chunk(1, 2), chunk(3), null, new NuzlockeChunk()), r -> r != 2);
		assertEquals(new HashSet<>(Arrays.asList(1, 3)), free);
		assertTrue(SimulationSession.startingRegions(Collections.emptyList(), r -> true).isEmpty());
	}
}
