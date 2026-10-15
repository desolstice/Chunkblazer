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
package com.chunkblazer.gps.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.RuneLite;

/** Loads the processed GPS data files. Never throws; problems are reported in {@link GpsDataStatus}. */
public final class GpsDataStore
{
	private static final Gson GSON = new Gson();
	private static final String[] FILES = {"monsters.json", "items.json", "quests.json", "transport.json",
		"locations.json", "training_methods.json"};

	private GpsDataStore()
	{
	}

	public static Path defaultDir()
	{
		return new File(RuneLite.RUNELITE_DIR, "chunkblazer/gps-data").toPath();
	}

	public static GpsData load(Path dir)
	{
		List<String> missing = new ArrayList<>();
		List<String> invalid = new ArrayList<>();
		if (dir == null || !Files.isDirectory(dir))
		{
			return GpsData.empty(new GpsDataStatus(dir, false, new ArrayList<>(Arrays.asList(FILES)), invalid));
		}
		List<MonsterDef> monsters = read(dir, "monsters.json", "monsters",
			new TypeToken<List<MonsterDef>>()
			{
			}, missing, invalid);
		List<ItemDef> items = read(dir, "items.json", "items",
			new TypeToken<List<ItemDef>>()
			{
			}, missing, invalid);
		List<QuestDef> quests = read(dir, "quests.json", "quests",
			new TypeToken<List<QuestDef>>()
			{
			}, missing, invalid);
		List<TransportEdge> transport = read(dir, "transport.json", "edges",
			new TypeToken<List<TransportEdge>>()
			{
			}, missing, invalid);
		List<LocationEntry> locations = read(dir, "locations.json", "entries",
			new TypeToken<List<LocationEntry>>()
			{
			}, missing, invalid);
		List<TrainingMethod> training = read(dir, "training_methods.json", "methods",
			new TypeToken<List<TrainingMethod>>()
			{
			}, missing, invalid);
		return new GpsData(monsters, items, quests, transport, locations, training, readVersion(dir),
			new GpsDataStatus(dir, true, missing, invalid));
	}

	private static <T> List<T> read(Path dir, String file, String key, TypeToken<List<T>> type,
		List<String> missing, List<String> invalid)
	{
		Path p = dir.resolve(file);
		if (!Files.isRegularFile(p))
		{
			missing.add(file);
			return new ArrayList<>();
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8))
		{
			JsonElement root = new JsonParser().parse(r);
			if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has(key))
			{
				invalid.add(file);
				return new ArrayList<>();
			}
			List<T> out = GSON.fromJson(root.getAsJsonObject().get(key), type.getType());
			return out == null ? new ArrayList<>() : out;
		}
		catch (IOException | JsonParseException | ClassCastException | IllegalStateException e)
		{
			invalid.add(file);
			return new ArrayList<>();
		}
	}

	/** Informational only: any failure yields null and is not reported as missing or invalid. */
	private static String readVersion(Path dir)
	{
		Path p = dir.resolve("data_version.json");
		if (!Files.isRegularFile(p))
		{
			return null;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8))
		{
			JsonElement root = new JsonParser().parse(r);
			if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("data_version"))
			{
				return null;
			}
			return root.getAsJsonObject().get("data_version").getAsString();
		}
		catch (IOException | JsonParseException | ClassCastException | IllegalStateException e)
		{
			return null;
		}
	}
}
