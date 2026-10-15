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

class BankCacheStoreTest
{
	@Test
	void savedSnapshotIsLoadedBack()
	{
		Map<String, String> backing = new HashMap<>();
		BankCacheStore s = new BankCacheStore(backing::get, backing::put, () -> true);
		s.save(BankSnapshot.of(Collections.singletonMap(554, 7)));
		assertEquals(7, (int) s.load().asMap().get(554));
		assertTrue(backing.containsKey(BankCacheStore.KEY));
	}

	@Test
	void noAccountMeansNoWriteAndEmptyLoad()
	{
		Map<String, String> backing = new HashMap<>();
		BankCacheStore s = new BankCacheStore(backing::get, backing::put, () -> false);
		s.save(BankSnapshot.of(Collections.singletonMap(554, 7)));
		assertTrue(backing.isEmpty());
		assertTrue(s.load().asMap().isEmpty());
	}

	@Test
	void differentAccountsAreIndependentBecauseBackingIsPerAccount()
	{
		Map<String, String> a = new HashMap<>();
		Map<String, String> b = new HashMap<>();
		new BankCacheStore(a::get, a::put, () -> true).save(BankSnapshot.of(Collections.singletonMap(1, 1)));
		assertTrue(new BankCacheStore(b::get, b::put, () -> true).load().asMap().isEmpty());
	}

	@Test
	void neverOpenedBankLoadsEmpty()
	{
		Map<String, String> backing = new HashMap<>();
		assertTrue(new BankCacheStore(backing::get, backing::put, () -> true).load().asMap().isEmpty());
	}
}
