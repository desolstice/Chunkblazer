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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.eligibility.CatalogTestSupport;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.travel.ChunkGraph;
import com.chunkblazer.gps.travel.ChunkNode;
import com.chunkblazer.gps.travel.ChunkNodes;
import com.chunkblazer.gps.travel.TileMath;
import com.chunkblazer.gps.travel.TransportModel;
import com.chunkblazer.gps.travel.TravelConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Whole-pipeline smoke tests: synthetic fixture data, and the shipped catalog with no GPS data at all. */
class GpsEndToEndTest
{
	private static final int LUMBRIDGE = 12850;
	private static final List<GoalMode> GOALS = Arrays.asList(GoalMode.maxPoints(), GoalMode.unlockNextChunk(),
		GoalMode.trainSkill("COOKING", 30), GoalMode.maxGear());

	/** The shipped catalog (tasks and chunk graph) with empty GPS data and a fresh account in Lumbridge. */
	private static PlanRequest catalogRequest(GoalMode goal)
	{
		List<NuzlockeChunk> chunks = CatalogTestSupport.loadCatalogChunks();
		List<TaskRef> tasks = new ArrayList<>();
		for (NuzlockeChunk c : chunks)
		{
			if (c.getRegionIds() == null || c.getRegionIds().isEmpty() || c.getTasks() == null)
			{
				continue;
			}
			c.getTasks().forEach(t -> tasks.add(new TaskRef(t, c.getRegionIds().get(0), 0)));
		}
		List<ChunkNode> nodes = ChunkNodes.from(chunks);
		GpsData data = GpsData.empty(null);
		QuestCatalog catalog = QuestCatalog.build(data, Collections.emptyMap());
		return PlanRequest.builder()
			.state(PlayerState.freshAccount().withUnlockedChunk(LUMBRIDGE))
			.start(TileMath.centre(LUMBRIDGE))
			.goal(goal)
			.tasks(tasks)
			.data(data)
			.quests(catalog)
			.deriver(new RequirementDeriver(catalog, Overrides.empty(), data))
			.graph(ChunkGraph.build(nodes))
			.transport(new TransportModel(data, nodes, catalog))
			.deadlineMillis(500)
			.build();
	}

	@Test
	void freshAccountWithFixtureDataPlansAValidOrder()
	{
		PlanWorld w = PlanWorld.random(11, 80);
		Plan p = new Planner(w.request()).plan();
		assertNotNull(p);
		assertTrue(!p.getSteps().isEmpty());
		assertTrue(p.getTotalTravelTicks() < TravelConfig.INF);
		// valid order: replaying the steps, every step's requirement holds in the state the earlier steps produced
		PlayerState s = w.request().getState();
		for (PlanStep step : p.getSteps())
		{
			PlanNode n = w.nodeFor(step);
			assertNotNull(n, step.getLabel());
			assertTrue(n.getRequirement().missing(s).isEmpty(), step.getLabel());
			s = n.getEffects().apply(s);
		}
	}

	@Test
	void emptyDataStillPlansFromTheShippedCatalog()
	{
		Plan p = assertDoesNotThrow(() -> new Planner(catalogRequest(GoalMode.maxPoints())).plan());
		assertNotNull(p);
		assertNotNull(p.getSteps());
		assertNotNull(p.getUnplannable());
	}

	@Test
	void everyGoalModeRunsWithoutExceptionOnTheShippedCatalog()
	{
		for (GoalMode g : GOALS)
		{
			Plan p = assertDoesNotThrow(() -> new Planner(catalogRequest(g)).plan(), g.toString());
			assertNotNull(p, g.toString());
		}
	}

	@Test
	void everyGoalModeRunsOnFixtureData()
	{
		for (GoalMode g : GOALS)
		{
			PlanWorld w = PlanWorld.random(5, 40);
			Plan p = assertDoesNotThrow(() -> new Planner(w.request(r -> r.goal(g))).plan(), g.toString());
			assertNotNull(p, g.toString());
		}
	}
}
