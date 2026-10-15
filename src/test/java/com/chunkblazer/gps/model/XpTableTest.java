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

class XpTableTest
{
	@Test
	void knownLevelThresholds()
	{
		assertEquals(0, XpTable.xpForLevel(1));
		assertEquals(83, XpTable.xpForLevel(2));
		assertEquals(1154, XpTable.xpForLevel(10));
		assertEquals(13_034_431, XpTable.xpForLevel(99));
	}

	@Test
	void levelForXpBoundaries()
	{
		assertEquals(1, XpTable.levelForXp(0));
		assertEquals(1, XpTable.levelForXp(82));
		assertEquals(2, XpTable.levelForXp(83));
		assertEquals(98, XpTable.levelForXp(13_034_430));
		assertEquals(99, XpTable.levelForXp(13_034_431));
		assertEquals(99, XpTable.levelForXp(200_000_000L));
	}
}
