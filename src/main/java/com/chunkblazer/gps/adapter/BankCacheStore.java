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

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Persists the bank snapshot through a per-account key/value backing (the plugin passes its
 * RSProfile-scoped account state), so one account's bank is never visible to another.
 */
public final class BankCacheStore
{
	public static final String KEY = "gpsBank";

	private final Function<String, String> read;
	private final BiConsumer<String, String> write;
	private final BooleanSupplier accountAvailable;

	public BankCacheStore(Function<String, String> read, BiConsumer<String, String> write,
		BooleanSupplier accountAvailable)
	{
		this.read = read;
		this.write = write;
		this.accountAvailable = accountAvailable;
	}

	public BankSnapshot load()
	{
		try
		{
			if (!accountAvailable.getAsBoolean())
			{
				return BankSnapshot.empty();
			}
			return BankSnapshot.fromJson(read.apply(KEY));
		}
		catch (RuntimeException ex)
		{
			return BankSnapshot.empty();
		}
	}

	public void save(BankSnapshot snapshot)
	{
		if (snapshot == null || !accountAvailable.getAsBoolean())
		{
			return;
		}
		write.accept(KEY, snapshot.toJson());
	}
}
