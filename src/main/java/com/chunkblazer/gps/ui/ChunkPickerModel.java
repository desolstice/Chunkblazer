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
package com.chunkblazer.gps.ui;

import com.chunkblazer.NuzlockeChunk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Filterable, selectable list of regions for the simulate-mode chunk picker. Swing-free. */
public class ChunkPickerModel
{
	public static final String ALL_AREAS = "All";

	private final Map<Integer, ChunkEntry> entries;
	private final Set<Integer> selected = new LinkedHashSet<>();

	private ChunkPickerModel(Map<Integer, ChunkEntry> entries)
	{
		this.entries = entries;
	}

	/** Builds a model; entries with a repeated region id collapse to the first one. */
	public static ChunkPickerModel of(List<ChunkEntry> entries)
	{
		Map<Integer, ChunkEntry> byId = new LinkedHashMap<>();
		for (ChunkEntry e : entries)
		{
			byId.putIfAbsent(e.getRegionId(), e);
		}
		return new ChunkPickerModel(byId);
	}

	/** One entry per region id of each chunk; name is the friendly name or "Region &lt;id&gt;". */
	public static ChunkPickerModel fromChunks(Collection<NuzlockeChunk> chunks)
	{
		List<ChunkEntry> list = new ArrayList<>();
		for (NuzlockeChunk chunk : chunks)
		{
			if (chunk == null || chunk.getRegionIds() == null)
			{
				continue;
			}
			String friendly = chunk.getFriendlyName();
			if (friendly == null || friendly.isEmpty())
			{
				friendly = chunk.getFriendlyNameAlt();
			}
			for (Integer id : chunk.getRegionIds())
			{
				if (id == null)
				{
					continue;
				}
				String name = friendly != null && !friendly.isEmpty() ? friendly : "Region " + id;
				list.add(new ChunkEntry(id, name, chunk.getArea()));
			}
		}
		return of(list);
	}

	/** Case-insensitive substring match on name or region id; a null or "All" area matches any. */
	public List<ChunkEntry> filter(String text, String area)
	{
		String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		boolean anyArea = area == null || ALL_AREAS.equals(area);
		List<ChunkEntry> out = new ArrayList<>();
		for (ChunkEntry e : entries.values())
		{
			if (!anyArea && !area.equals(e.getArea()))
			{
				continue;
			}
			if (!needle.isEmpty()
				&& !(e.getName() != null && e.getName().toLowerCase(Locale.ROOT).contains(needle))
				&& !String.valueOf(e.getRegionId()).contains(needle))
			{
				continue;
			}
			out.add(e);
		}
		return out;
	}

	/** Sorted, distinct, non-null areas. */
	public List<String> areas()
	{
		Set<String> areas = new TreeSet<>();
		for (ChunkEntry e : entries.values())
		{
			if (e.getArea() != null)
			{
				areas.add(e.getArea());
			}
		}
		return new ArrayList<>(areas);
	}

	public Set<Integer> selected()
	{
		return new LinkedHashSet<>(selected);
	}

	public void set(int regionId, boolean on)
	{
		if (on)
		{
			if (entries.containsKey(regionId))
			{
				selected.add(regionId);
			}
		}
		else
		{
			selected.remove(regionId);
		}
	}

	public void setAll(List<ChunkEntry> visible, boolean on)
	{
		for (ChunkEntry e : visible)
		{
			set(e.getRegionId(), on);
		}
	}

	/** Replaces the selection with the given real unlocked set, ignoring regions not in the picker. */
	public void selectAreaFrom(Set<Integer> real)
	{
		selected.clear();
		for (Integer id : real)
		{
			if (id != null && entries.containsKey(id))
			{
				selected.add(id);
			}
		}
	}

	public int count()
	{
		return entries.size();
	}
}
