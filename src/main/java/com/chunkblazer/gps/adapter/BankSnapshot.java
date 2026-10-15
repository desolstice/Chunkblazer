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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable bank contents (canonical item id to quantity) with defensive JSON (de)serialisation. */
public final class BankSnapshot
{
	private static final Gson GSON = new Gson();
	private static final BankSnapshot EMPTY = new BankSnapshot(Collections.emptyMap());

	private final Map<Integer, Integer> items;

	private BankSnapshot(Map<Integer, Integer> items)
	{
		this.items = items;
	}

	public static BankSnapshot empty()
	{
		return EMPTY;
	}

	/** Drops non-positive quantities and the placeholder id -1 (and any id below 0). */
	public static BankSnapshot of(Map<Integer, Integer> source)
	{
		if (source == null || source.isEmpty())
		{
			return EMPTY;
		}
		Map<Integer, Integer> m = new TreeMap<>();
		for (Map.Entry<Integer, Integer> e : source.entrySet())
		{
			Integer id = e.getKey();
			Integer qty = e.getValue();
			if (id == null || qty == null || id <= 0 || qty <= 0)
			{
				continue;
			}
			m.put(id, qty);
		}
		return m.isEmpty() ? EMPTY : new BankSnapshot(Collections.unmodifiableMap(m));
	}

	public Map<Integer, Integer> asMap()
	{
		return items;
	}

	public String toJson()
	{
		JsonObject o = new JsonObject();
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			o.addProperty(String.valueOf(e.getKey()), e.getValue());
		}
		return GSON.toJson(o);
	}

	/** Null, blank or invalid input yields {@link #empty()}; never throws. */
	public static BankSnapshot fromJson(String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return EMPTY;
		}
		try
		{
			JsonElement root = GSON.fromJson(json, JsonElement.class);
			if (root == null || !root.isJsonObject())
			{
				return EMPTY;
			}
			Map<Integer, Integer> m = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet())
			{
				try
				{
					m.put(Integer.parseInt(e.getKey().trim()), e.getValue().getAsInt());
				}
				catch (RuntimeException skip)
				{
					// one bad entry must not discard the rest
				}
			}
			return of(m);
		}
		catch (RuntimeException ex)
		{
			return EMPTY;
		}
	}
}
