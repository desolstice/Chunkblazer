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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Item;

/**
 * Volatile in-memory bank snapshot backed by a {@link BankCacheStore}. The snapshot is reloaded on
 * every account/profile change so one account's bank is never served to another; a save that was
 * queued under one account is dropped if the active account changed before it ran.
 */
public final class BankCache
{
	/** Snapshot tagged with the account key it belongs to; immutable so one volatile read is consistent. */
	private static final class Held
	{
		private static final Held NONE = new Held(null, BankSnapshot.empty());

		private final String key;
		private final BankSnapshot snapshot;

		private Held(String key, BankSnapshot snapshot)
		{
			this.key = key;
			this.snapshot = snapshot;
		}
	}

	private final BankCacheStore store;
	private final Supplier<String> accountKey;
	private volatile Held held = Held.NONE;
	/** Bumped by every live update or clear; a reload only publishes if nothing newer happened. */
	private long version;

	public BankCache(BankCacheStore store, Supplier<String> accountKey)
	{
		this.store = store;
		this.accountKey = accountKey;
	}

	/** Builds an id to quantity map from a container, merging ids after {@code canonicalize} (un-notes items). */
	public static Map<Integer, Integer> fromContainer(Item[] items, IntUnaryOperator canonicalize)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (Item item : items)
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				out.merge(canonicalize.applyAsInt(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		return out;
	}

	/** Items for {@link PlayerStateProvider}; empty until a bank is seen or loaded for the current account. */
	public Map<Integer, Integer> items()
	{
		Held h = held;
		String key = accountKey.get();
		if (h.key == null || !h.key.equals(key))
		{
			return Collections.emptyMap();
		}
		return h.snapshot.asMap();
	}

	/**
	 * Re-reads the active account's stored bank (empty when no account). Call off the client thread. A
	 * reload that raced an account switch or a fresher {@link #updateTask} is discarded.
	 */
	public void reload()
	{
		final String key = accountKey.get();
		final long startVersion;
		synchronized (this)
		{
			startVersion = version;
		}
		final BankSnapshot loaded = store.load();
		synchronized (this)
		{
			if (version == startVersion && key != null && key.equals(accountKey.get()))
			{
				held = new Held(key, loaded);
			}
		}
	}

	/** Drops the in-memory snapshot (account unavailable / module stopped). */
	public synchronized void clear()
	{
		version++;
		held = Held.NONE;
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
			synchronized (this)
			{
				version++;
				held = new Held(key, fresh);
			}
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
