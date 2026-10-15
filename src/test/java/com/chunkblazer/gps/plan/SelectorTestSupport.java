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
package com.chunkblazer.gps.plan;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.travel.ChunkGraph;
import com.chunkblazer.gps.travel.LocationResolver;
import com.chunkblazer.gps.travel.TransportModel;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only builder of small synthetic worlds for Selector tests. */
final class SelectorTestSupport
{
	private final List<TaskRef> tasks = new ArrayList<>();
	private final List<String> trainingMethods = new ArrayList<>();
	private final Set<String> completed = new LinkedHashSet<>();
	private String questsJson;
	private String itemsJson;
	private String locationsJson;
	private PlayerState state = PlayerState.freshAccount();
	private GoalMode goal = GoalMode.maxPoints();
	private int maxNodes = 300;

	static PlayerState unlocked(int... regions)
	{
		PlayerState.Builder b = PlayerState.builder();
		for (int r : regions)
		{
			b.chunk(r);
		}
		return b.build();
	}

	static PlanNode node(String id)
	{
		return PlanNode.builder().id(id).type(NodeType.TASK).label(id).build();
	}

	/** Pairs (k, v) become k -> [v]; repeated keys accumulate. */
	static Map<String, List<String>> map(String... kv)
	{
		Map<String, List<String>> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1]);
		}
		return m;
	}

	SelectorTestSupport withTask(String id, String type, int level, int points, int region)
	{
		return withRawTask("{\"name\":\"Task " + id + "\",\"taskID\":\"" + id + "\",\"completion_type\":\"" + type
			+ "\",\"level\":" + level + ",\"base_points\":" + points + "}", region);
	}

	SelectorTestSupport withQuestCheckTask(String id, String quest, int points, int region)
	{
		return withRawTask("{\"name\":\"Task " + id + "\",\"taskID\":\"" + id + "\",\"completion_type\":\"QUEST_CHECK\","
			+ "\"base_points\":" + points + ",\"constraints\":{\"quest\":\"" + quest + "\"}}", region);
	}

	SelectorTestSupport withRawTask(String json, int region)
	{
		tasks.add(new TaskRef(new Gson().fromJson(json, NuzlockeTask.class), region, 0));
		return this;
	}

	/** Registers a dummy task homed in the region that carries the chunk's unlock cost. */
	SelectorTestSupport withLockedChunkCost(int region, int cost)
	{
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"Locked " + region + "\",\"taskID\":\"locked" + region
			+ "\",\"completion_type\":\"COOKING\",\"level\":1,\"base_points\":1}", NuzlockeTask.class);
		tasks.add(new TaskRef(t, region, cost));
		return this;
	}

	SelectorTestSupport withTrainingMethod(String skill, String name, int minLevel, int maxLevel, int xpPerHour)
	{
		trainingMethods.add("{\"skill\":\"" + skill + "\",\"name\":\"" + name + "\",\"minLevel\":" + minLevel
			+ ",\"maxLevel\":" + maxLevel + ",\"xpPerHour\":" + xpPerHour
			+ ",\"location\":null,\"items\":[],\"approximate\":true}");
		return this;
	}

	SelectorTestSupport withQuests(String questsJson)
	{
		this.questsJson = questsJson;
		return this;
	}

	SelectorTestSupport withItems(String itemsJson)
	{
		this.itemsJson = itemsJson;
		return this;
	}

	SelectorTestSupport withLocations(String locationsJson)
	{
		this.locationsJson = locationsJson;
		return this;
	}

	SelectorTestSupport completed(String... taskIds)
	{
		completed.addAll(Arrays.asList(taskIds));
		return this;
	}

	SelectorTestSupport state(PlayerState state)
	{
		this.state = state;
		return this;
	}

	SelectorTestSupport goal(GoalMode goal)
	{
		this.goal = goal;
		return this;
	}

	SelectorTestSupport maxNodes(int maxNodes)
	{
		this.maxNodes = maxNodes;
		return this;
	}

	PlanRequest request()
	{
		String training = trainingMethods.isEmpty() ? null
			: "{\"schema\":1,\"methods\":[" + String.join(",", trainingMethods) + "]}";
		GpsData data = questsJson == null && itemsJson == null && locationsJson == null && training == null
			? GpsData.empty(null)
			: GpsTestFixtures.dataWithAll(null, itemsJson, locationsJson, questsJson, training);
		QuestCatalog quests = QuestCatalog.build(data, Collections.emptyMap());
		return PlanRequest.builder()
			.state(state)
			.goal(goal)
			.tasks(tasks)
			.completedTaskIds(completed)
			.data(data)
			.quests(quests)
			.deriver(new RequirementDeriver(quests, Overrides.empty(), data))
			.graph(ChunkGraph.build(Collections.emptyList()))
			.transport(new TransportModel(data, Collections.emptyList(), quests))
			.maxNodes(maxNodes)
			.build();
	}

	NodeFactory factory(PlanRequest r)
	{
		return new NodeFactory(r.getData(), r.getQuests(), r.getDeriver(), new LocationResolver(r.getData(), r.getQuests()),
			r.getDoneNodeIds(), r.getState(), r.getStart());
	}

	PrecedenceGraph select()
	{
		PlanRequest r = request();
		return new Selector(r, factory(r)).select();
	}
}
