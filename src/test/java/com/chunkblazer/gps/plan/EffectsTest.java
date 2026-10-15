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

import static org.junit.jupiter.api.Assertions.*;

import com.chunkblazer.gps.model.PlayerState;
import org.junit.jupiter.api.Test;

class EffectsTest
{
	@Test
	void reachLevelRaisesButNeverLowers()
	{
		PlayerState s = PlayerState.builder().level("COOKING", 50).build();
		Effects e = Effects.builder().reachLevel("COOKING", 30).reachLevel("MINING", 20).build();
		PlayerState r = e.apply(s);
		assertEquals(50, r.getLevel("COOKING"));
		assertEquals(20, r.getLevel("MINING"));
	}

	@Test
	void xpQuestItemsUnlocksPointsAllApply()
	{
		Effects e = Effects.builder().xp("COOKING", 300).questComplete("COOKS_ASSISTANT", 1).item(1944, 2)
			.unlock("TELEPORT:Explorer's ring").taskPoints(3).build();
		PlayerState r = e.apply(PlayerState.freshAccount());
		assertTrue(r.getLevel("COOKING") >= 4);
		assertTrue(r.hasQuest("COOKS_ASSISTANT"));
		assertEquals(1, r.getQuestPoints());
		assertEquals(2, r.ownedCount(1944));
		assertTrue(r.hasUnlock("TELEPORT:Explorer's ring"));
		assertEquals(3, r.getTaskPoints());
	}

	@Test
	void plusCombines()
	{
		Effects a = Effects.builder().xp("COOKING", 100).taskPoints(1).reachLevel("MINING", 10).build();
		Effects b = Effects.builder().xp("COOKING", 50).taskPoints(2).reachLevel("MINING", 15).build();
		Effects c = a.plus(b);
		assertEquals(150L, (long) c.getXp().get("COOKING"));
		assertEquals(3, c.getTaskPoints());
		assertEquals(15, (int) c.getReachLevel().get("MINING"));
	}

	@Test
	void noneIsIdentity()
	{
		PlayerState s = PlayerState.freshAccount();
		assertEquals(s.getLevel("ATTACK"), Effects.NONE.apply(s).getLevel("ATTACK"));
	}
}
