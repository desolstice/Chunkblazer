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
package com.chunkblazer.gps;

import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class GpsTestFixtures
{
	private GpsTestFixtures()
	{
	}

	/** Resolves a fixture directory under /com/chunkblazer/gps/data/. */
	public static Path dir(String name)
	{
		try
		{
			return Paths.get(GpsTestFixtures.class.getResource("/com/chunkblazer/gps/data/" + name).toURI());
		}
		catch (URISyntaxException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Loads a temp data dir holding only locations.json with the given JSON (other files missing). */
	public static GpsData dataWithLocations(String locationsJson)
	{
		return dataWith(null, null, locationsJson);
	}

	/** Loads a temp data dir with transport.json, items.json and locations.json; null arguments skip the file. */
	public static GpsData dataWith(String transportJson, String itemsJson, String locationsJson)
	{
		try
		{
			Path d = Files.createTempDirectory("gps-fixture");
			d.toFile().deleteOnExit();
			write(d, "transport.json", transportJson);
			write(d, "items.json", itemsJson);
			write(d, "locations.json", locationsJson);
			return GpsDataStore.load(d);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Loads a temp data dir holding only training_methods.json with the given JSON. */
	public static GpsData dataWithTraining(String trainingJson)
	{
		return dataWithAll(null, null, null, null, trainingJson);
	}

	/** Loads a temp data dir with transport, items, locations, quests and training JSON; null skips the file. */
	public static GpsData dataWithAll(String transportJson, String itemsJson, String locationsJson, String questsJson,
		String trainingJson)
	{
		try
		{
			Path d = Files.createTempDirectory("gps-fixture");
			d.toFile().deleteOnExit();
			write(d, "transport.json", transportJson);
			write(d, "items.json", itemsJson);
			write(d, "locations.json", locationsJson);
			write(d, "quests.json", questsJson);
			write(d, "training_methods.json", trainingJson);
			return GpsDataStore.load(d);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void write(Path dir, String file, String json) throws IOException
	{
		if (json != null)
		{
			Path p = dir.resolve(file);
			Files.write(p, json.getBytes(StandardCharsets.UTF_8));
			p.toFile().deleteOnExit();
		}
	}
}
