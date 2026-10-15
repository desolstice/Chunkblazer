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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;

@Value
public class GpsData
{
	List<MonsterDef> monsters;
	List<ItemDef> items;
	List<QuestDef> quests;
	List<TransportEdge> transport;
	List<LocationEntry> locations;
	List<TrainingMethod> training;
	String dataVersion;
	GpsDataStatus status;

	public static GpsData empty(GpsDataStatus status)
	{
		return new GpsData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
			new ArrayList<>(), new ArrayList<>(), null, status);
	}

	/** Test/support use: data holding only the given monsters (other lists empty, no version, null status). */
	public static GpsData withMonsters(List<MonsterDef> monsters)
	{
		return new GpsData(new ArrayList<>(monsters), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
			new ArrayList<>(), new ArrayList<>(), null, null);
	}

	public List<MonsterDef> getMonsters()
	{
		return monsters == null ? Collections.emptyList() : monsters;
	}

	public List<ItemDef> getItems()
	{
		return items == null ? Collections.emptyList() : items;
	}

	public List<QuestDef> getQuests()
	{
		return quests == null ? Collections.emptyList() : quests;
	}

	public List<TransportEdge> getTransport()
	{
		return transport == null ? Collections.emptyList() : transport;
	}

	public List<LocationEntry> getLocations()
	{
		return locations == null ? Collections.emptyList() : locations;
	}

	public List<TrainingMethod> getTraining()
	{
		return training == null ? Collections.emptyList() : training;
	}
}
