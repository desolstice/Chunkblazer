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
package com.chunkblazer.gps.combat;

import com.chunkblazer.gps.travel.TravelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillTimeCheckTest
{
	@Test
	void estimateScalesWithHpAndSpeed()
	{
		// hp 20, max 10, speed 4, chance 0.7: avg dmg 3.5/attack -> ceil(20/3.5)=6 attacks -> 24 ticks
		assertEquals(24, KillTimeCheck.estimateTicks(20, 10, 4, 0.7));
	}

	@Test
	void zeroMaxHitIsInfinite()
	{
		assertEquals(TravelConfig.INF, KillTimeCheck.estimateTicks(20, 0, 4, 0.7));
	}

	@Test
	void canKillInTimeComparesToLimit()
	{
		assertTrue(KillTimeCheck.canKillInTime(20, 10, 4, 0.7, 24));
		assertFalse(KillTimeCheck.canKillInTime(20, 10, 4, 0.7, 23));
	}

	@Test
	void derivedMinMaxHitForTwentyTickKill()
	{
		// desert lizard hp 25 in 20 ticks: attacks = 20/4 = 5; need 25 / (5 * 0.7) = 7.14 avg -> max hit 15
		assertEquals(15, KillTimeCheck.requiredMaxHit(25, 20, 4, 0.7));
	}

	@Test
	void requiredMaxHitAssumesAtLeastOneAttack()
	{
		// limit shorter than one attack still counts as one attack: ceil(2*10/0.7)=29
		assertEquals(29, KillTimeCheck.requiredMaxHit(10, 2, 4, 0.7));
	}
}
