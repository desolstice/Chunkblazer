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

class BankCacheTest
{
	private final Map<String, Map<String, String>> accounts = new HashMap<>();
	private String active;

	private BankCache cache()
	{
		BankCacheStore store = new BankCacheStore(
			k -> accounts.getOrDefault(active, Collections.emptyMap()).get(k),
			(k, v) -> accounts.computeIfAbsent(active, a -> new HashMap<>()).put(k, v),
			() -> active != null);
		return new BankCache(store, () -> active);
	}

	@Test
	void fromContainerCanonicalizesSkipsEmptyAndMerges()
	{
		Map<Integer, Integer> m = BankCache.fromContainer(new net.runelite.api.Item[]{
			new net.runelite.api.Item(1000, 2), new net.runelite.api.Item(10, 3), new net.runelite.api.Item(-1, 5),
			new net.runelite.api.Item(10, 0), new net.runelite.api.Item(11, 4)}, id -> id == 1000 ? 11 : id);
		assertEquals(2, m.size());
		assertEquals(6, (int) m.get(11));
		assertEquals(3, (int) m.get(10));
	}

	@Test
	void accountBNeverSeesAccountABankAfterReload()
	{
		BankCache c = cache();
		active = "A";
		c.updateTask(Collections.singletonMap(554, 9)).run();
		assertEquals(9, (int) c.items().get(554));

		active = "B";
		c.reload();
		assertTrue(c.items().isEmpty());

		active = "A";
		c.reload();
		assertEquals(9, (int) c.items().get(554));
	}

	@Test
	void saveQueuedUnderAThenRunAfterSwitchToBIsDropped()
	{
		BankCache c = cache();
		active = "A";
		Runnable task = c.updateTask(Collections.singletonMap(554, 9));
		active = "B";
		task.run();
		assertTrue(accounts.getOrDefault("B", Collections.emptyMap()).isEmpty());
		assertTrue(accounts.getOrDefault("A", Collections.emptyMap()).isEmpty());
	}

	@Test
	void staleReloadStartedUnderAIsNotPublishedAfterSwitchToB()
	{
		BankCacheStore store = new BankCacheStore(
			k ->
			{
				String stored = accounts.getOrDefault(active, Collections.emptyMap()).get(k);
				active = "B";
				return stored;
			},
			(k, v) -> accounts.computeIfAbsent(active, a -> new HashMap<>()).put(k, v),
			() -> true);
		BankCache c = new BankCache(store, () -> active);
		active = "A";
		c.updateTask(Collections.singletonMap(554, 9)).run();
		c.clear();
		active = "A";
		c.reload();
		assertTrue(c.items().isEmpty());
		active = "A";
		assertTrue(c.items().isEmpty());
	}

	@Test
	void staleReloadDoesNotClobberFresherUpdate()
	{
		BankCache[] holder = new BankCache[1];
		BankCacheStore store = new BankCacheStore(
			k ->
			{
				holder[0].updateTask(Collections.singletonMap(1, 5));
				return null;
			},
			(k, v) -> { },
			() -> true);
		active = "A";
		holder[0] = new BankCache(store, () -> active);
		holder[0].reload();
		assertEquals(5, (int) holder[0].items().get(1));
	}

	@Test
	void noAccountMeansNothingCachedOrSaved()
	{
		BankCache c = cache();
		active = null;
		c.updateTask(Collections.singletonMap(554, 9)).run();
		assertTrue(c.items().isEmpty());
		assertTrue(accounts.isEmpty());
	}
}
