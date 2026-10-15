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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankSnapshotTest
{
	@Test
	void roundTrip()
	{
		Map<Integer, Integer> m = new HashMap<>();
		m.put(554, 1000);
		m.put(1944, 3);
		BankSnapshot b = BankSnapshot.fromJson(BankSnapshot.of(m).toJson());
		assertEquals(1000, (int) b.asMap().get(554));
		assertEquals(3, (int) b.asMap().get(1944));
	}

	@Test
	void dropsNonPositiveAndPlaceholders()
	{
		Map<Integer, Integer> m = new HashMap<>();
		m.put(-1, 5);
		m.put(0, 5);
		m.put(100, 0);
		m.put(101, 2);
		assertEquals(Collections.singleton(101), BankSnapshot.of(m).asMap().keySet());
	}

	@Test
	void garbageIsEmptyNotException()
	{
		assertTrue(BankSnapshot.fromJson(null).asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("").asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("{not json").asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("[1,2,3]").asMap().isEmpty());
	}

	@Test
	void badEntryDoesNotDiscardTheRest()
	{
		BankSnapshot b = BankSnapshot.fromJson("{\"554\":7,\"x\":1,\"9\":\"nope\"}");
		assertEquals(Collections.singletonMap(554, 7), b.asMap());
	}
}
