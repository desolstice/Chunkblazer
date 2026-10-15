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

import java.util.Map;
import java.util.function.Supplier;

/**
 * Volatile in-memory bank snapshot backed by a {@link BankCacheStore}. The snapshot is reloaded on
 * every account/profile change so one account's bank is never served to another; a save that was
 * queued under one account is dropped if the active account changed before it ran.
 */
public final class BankCache
{
	private final BankCacheStore store;
	private final Supplier<String> accountKey;
	private volatile BankSnapshot snapshot = BankSnapshot.empty();

	public BankCache(BankCacheStore store, Supplier<String> accountKey)
	{
		this.store = store;
		this.accountKey = accountKey;
	}

	/** Items for {@link PlayerStateProvider}; empty until a bank is seen or loaded for this account. */
	public Map<Integer, Integer> items()
	{
		return snapshot.asMap();
	}

	/** Re-reads the active account's stored bank (empty when no account). Call off the client thread. */
	public void reload()
	{
		snapshot = store.load();
	}

	/** Drops the in-memory snapshot (account unavailable / module stopped). */
	public void clear()
	{
		snapshot = BankSnapshot.empty();
	}

	/**
	 * Captures the account at call time (call on the client thread), returns a task that persists the
	 * snapshot only if that account is still active when it runs (run it on the executor).
	 */
	public Runnable updateTask(Map<Integer, Integer> bank)
	{
		final String key = accountKey.get();
		final BankSnapshot fresh = BankSnapshot.of(bank);
		if (key != null)
		{
			snapshot = fresh;
		}
		return () ->
		{
			if (key != null && key.equals(accountKey.get()))
			{
				store.save(fresh);
			}
		};
	}
}
