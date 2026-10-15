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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MagicSpellsTest
{
	@Test
	void availableRespectsLevelRequirements()
	{
		assertTrue(MagicSpells.available(0).isEmpty());
		List<String> l1 = MagicSpells.available(1);
		assertTrue(l1.contains("wind strike"));
		assertFalse(l1.contains("fire strike"));
		assertTrue(MagicSpells.available(13).contains("fire strike"));
		assertTrue(MagicSpells.available(99).contains("fire surge"));
	}

	@Test
	void availableBoundaries()
	{
		assertFalse(MagicSpells.available(94).contains("fire surge"));
		assertTrue(MagicSpells.available(95).contains("fire surge"));
		assertFalse(MagicSpells.available(61).contains("wind wave"));
		assertTrue(MagicSpells.available(62).contains("wind wave"));
		assertFalse(MagicSpells.available(65).contains("water wave"));
		assertTrue(MagicSpells.available(66).contains("water wave"));
	}

	@Test
	void everyAvailableSpellHasAKnownMaxHit()
	{
		for (String s : MagicSpells.available(99))
		{
			assertTrue(MagicSpells.baseMaxHit(s, 99) > 0, s);
		}
	}
}
