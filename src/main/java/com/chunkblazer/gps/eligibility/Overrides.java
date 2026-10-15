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
package com.chunkblazer.gps.eligibility;

import com.chunkblazer.gps.model.ItemNeed;
import com.chunkblazer.gps.model.Requirement;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-authored requirement overrides, keyed by task id. An override replaces the derived requirement.
 * Read from {@code <dir>/overrides/requirement_overrides.json}; a missing or malformed file yields no overrides.
 */
public final class Overrides
{
	private static final Type MAP_TYPE = new TypeToken<Map<String, OverrideDto>>()
	{
	}.getType();

	private final Map<String, Requirement> byTask;

	private Overrides(Map<String, Requirement> byTask)
	{
		this.byTask = byTask;
	}

	public static Overrides empty()
	{
		return new Overrides(Collections.emptyMap());
	}

	public static Overrides load(Path dir)
	{
		if (dir == null)
		{
			return empty();
		}
		Path file = dir.resolve("overrides").resolve("requirement_overrides.json");
		if (!Files.isRegularFile(file))
		{
			return empty();
		}
		try
		{
			String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			Map<String, OverrideDto> dtos = new Gson().fromJson(json, MAP_TYPE);
			if (dtos == null)
			{
				return empty();
			}
			Map<String, Requirement> out = new HashMap<>();
			for (Map.Entry<String, OverrideDto> e : dtos.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null)
				{
					out.put(e.getKey(), e.getValue().toRequirement());
				}
			}
			return new Overrides(out);
		}
		catch (IOException | RuntimeException ex)
		{
			return empty();
		}
	}

	public Optional<Requirement> get(String taskId)
	{
		return Optional.ofNullable(taskId == null ? null : byTask.get(taskId));
	}

	private static final class OverrideDto
	{
		Map<String, Integer> skills;
		List<String> quests;
		List<ItemDto> items;
		Integer minMaxHit;
		List<String> unknown;

		Requirement toRequirement()
		{
			Requirement.Builder b = Requirement.builder();
			if (skills != null)
			{
				for (Map.Entry<String, Integer> e : skills.entrySet())
				{
					if (e.getKey() != null && e.getValue() != null)
					{
						b.skill(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
					}
				}
			}
			if (quests != null)
			{
				for (String q : quests)
				{
					if (q != null)
					{
						b.quest(q);
					}
				}
			}
			if (items != null)
			{
				for (ItemDto i : items)
				{
					if (i != null && i.ids != null)
					{
						b.item(new ItemNeed(new LinkedHashSet<>(i.ids), i.qty == null ? 1 : i.qty, i.name));
					}
				}
			}
			if (minMaxHit != null)
			{
				b.minMaxHit(minMaxHit);
			}
			if (unknown != null)
			{
				for (String u : unknown)
				{
					if (u != null)
					{
						b.unknown(u);
					}
				}
			}
			return b.build();
		}
	}

	private static final class ItemDto
	{
		List<Integer> ids;
		Integer qty;
		String name;
	}
}
