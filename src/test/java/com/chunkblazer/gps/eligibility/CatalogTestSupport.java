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

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.NuzlockeTask;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Test support: loads every task from the bundled tasks_catalog.json.gz. */
public final class CatalogTestSupport
{
	private static final String RESOURCE = "/com/chunkblazer/tasks_catalog.json.gz";

	private CatalogTestSupport()
	{
	}

	/**
	 * Catalog shape: top-level map of file name to an object whose values are lists of region objects
	 * ({region_id, Friendly_Name, neighbor_ids, tasks:[...]}). Top-level keys starting with "_" are metadata.
	 */
	public static List<NuzlockeTask> loadCatalogTasks()
	{
		Gson gson = new Gson();
		List<NuzlockeTask> out = new ArrayList<>();
		try (InputStream in = CatalogTestSupport.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			try (Reader r = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))
			{
				JsonObject root = new JsonParser().parse(r).getAsJsonObject();
				for (Map.Entry<String, JsonElement> file : root.entrySet())
				{
					if (file.getKey().startsWith("_") || !file.getValue().isJsonObject())
					{
						continue;
					}
					for (Map.Entry<String, JsonElement> list : file.getValue().getAsJsonObject().entrySet())
					{
						if (!list.getValue().isJsonArray())
						{
							continue;
						}
						for (JsonElement region : list.getValue().getAsJsonArray())
						{
							if (!region.isJsonObject() || !region.getAsJsonObject().has("tasks"))
							{
								continue;
							}
							for (JsonElement t : region.getAsJsonObject().getAsJsonArray("tasks"))
							{
								out.add(gson.fromJson(t, NuzlockeTask.class));
							}
						}
					}
				}
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
		return out;
	}

	/** Same traversal as {@link #loadCatalogTasks()} but returns each region object as a NuzlockeChunk. */
	public static List<NuzlockeChunk> loadCatalogChunks()
	{
		Gson gson = new Gson();
		List<NuzlockeChunk> out = new ArrayList<>();
		try (InputStream in = CatalogTestSupport.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			try (Reader r = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))
			{
				JsonObject root = new JsonParser().parse(r).getAsJsonObject();
				for (Map.Entry<String, JsonElement> file : root.entrySet())
				{
					if (file.getKey().startsWith("_") || !file.getValue().isJsonObject())
					{
						continue;
					}
					for (Map.Entry<String, JsonElement> list : file.getValue().getAsJsonObject().entrySet())
					{
						if (!list.getValue().isJsonArray())
						{
							continue;
						}
						for (JsonElement region : list.getValue().getAsJsonArray())
						{
							if (region.isJsonObject() && region.getAsJsonObject().has("region_id"))
							{
								out.add(gson.fromJson(region, NuzlockeChunk.class));
							}
						}
					}
				}
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
		return out;
	}
}
