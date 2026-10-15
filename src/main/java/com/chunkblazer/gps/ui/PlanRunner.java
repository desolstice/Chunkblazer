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
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.plan.GoalMode;
import com.chunkblazer.gps.plan.Plan;
import com.chunkblazer.gps.plan.PlanRequest;
import com.chunkblazer.gps.plan.Planner;
import com.chunkblazer.gps.plan.TaskRef;
import com.chunkblazer.gps.travel.ChunkGraph;
import com.chunkblazer.gps.travel.ChunkNode;
import com.chunkblazer.gps.travel.ChunkNodes;
import com.chunkblazer.gps.travel.TransportModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import lombok.Value;

/**
 * Swing free glue between the plan panel and the planner: turns a client snapshot plus the panel's goal into a
 * {@link PlanRequest}, runs the planner, and refuses to plan a logged-out real account.
 */
public final class PlanRunner
{
	public static final String LOGGED_OUT_MESSAGE = "Log in to plan from your real account, or switch to Simulate.";
	/** Start tile used when the player's position is unknown (simulate mode). */
	static final Tile UNKNOWN_START = new Tile(null, null, 0, true, "NONE");

	/** The goals offered by the goal combo. */
	public enum GoalChoice
	{
		MAX_POINTS("Max points"),
		UNLOCK_NEXT_CHUNK("Unlock next chunk"),
		TRAIN_SKILL("Train skill"),
		MAX_GEAR("Max gear");

		private final String label;

		GoalChoice(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Everything read from the client (or simulation) for one plan run. */
	@Value
	public static class Inputs
	{
		PlayerState state;
		/** Player's world tile, or null when unknown. */
		Tile position;
		List<TaskRef> tasks;
		Set<String> completedTaskIds;
		/** Chunk catalog, used once to build the travel graph. */
		List<NuzlockeChunk> chunks;
	}

	/** Exactly one of plan and message is set. */
	@Value
	public static class Result
	{
		Plan plan;
		String message;
	}

	@Value
	static class Travel
	{
		ChunkGraph graph;
		TransportModel transport;
	}

	private final GpsData data;
	private final QuestCatalog quests;
	private final RequirementDeriver deriver;
	private final Function<PlanRequest, Plan> planner;
	private final Function<List<NuzlockeChunk>, Travel> travelFactory;
	private volatile Travel travel;

	public PlanRunner(GpsData data, QuestCatalog quests, RequirementDeriver deriver)
	{
		this(data, quests, deriver, req -> new Planner(req).plan(), chunks ->
		{
			List<ChunkNode> nodes = ChunkNodes.from(chunks);
			return new Travel(ChunkGraph.build(nodes), new TransportModel(data, nodes, quests));
		});
	}

	PlanRunner(GpsData data, QuestCatalog quests, RequirementDeriver deriver, Function<PlanRequest, Plan> planner,
		Function<List<NuzlockeChunk>, Travel> travelFactory)
	{
		this.data = data;
		this.quests = quests;
		this.deriver = deriver;
		this.planner = planner;
		this.travelFactory = travelFactory;
	}

	/** Maps the combo selection to a goal; a missing skill for TRAIN_SKILL falls back to max points. */
	public static GoalMode goalFor(GoalChoice choice, String skill, int level)
	{
		if (choice == null)
		{
			return GoalMode.maxPoints();
		}
		switch (choice)
		{
			case UNLOCK_NEXT_CHUNK:
				return GoalMode.unlockNextChunk();
			case TRAIN_SKILL:
				return skill == null || skill.trim().isEmpty()
					? GoalMode.maxPoints()
					: GoalMode.trainSkill(skill.trim().toUpperCase(Locale.ROOT), Math.max(1, Math.min(99, level)));
			case MAX_GEAR:
				return GoalMode.maxGear();
			default:
				return GoalMode.maxPoints();
		}
	}

	/**
	 * One {@link TaskRef} per distinct task id: chunk tasks use their defining chunk's first region (preferring a chunk
	 * the player has unlocked); global tasks have no chunk and use any region the player holds, since they are free.
	 */
	public static List<TaskRef> taskRefs(List<NuzlockeChunk> chunks, List<NuzlockeTask> globals, PlayerState state,
		IntUnaryOperator unlockCost)
	{
		Map<String, TaskRef> byId = new LinkedHashMap<>();
		if (chunks != null)
		{
			for (NuzlockeChunk c : chunks)
			{
				if (c == null || c.getTasks() == null || c.getRegionIds() == null || c.getRegionIds().isEmpty())
				{
					continue;
				}
				int region = c.getRegionIds().get(0);
				for (NuzlockeTask t : c.getTasks())
				{
					if (t == null || t.getTaskId() == null)
					{
						continue;
					}
					TaskRef have = byId.get(t.getTaskId());
					if (have == null || (!state.hasUnlockedChunk(have.getHomeRegionId()) && state.hasUnlockedChunk(region)))
					{
						byId.put(t.getTaskId(), new TaskRef(t, region, unlockCost.applyAsInt(region)));
					}
				}
			}
		}
		int globalHome = state.getUnlockedChunks().isEmpty() ? 0 : state.getUnlockedChunks().iterator().next();
		if (globals != null)
		{
			for (NuzlockeTask t : globals)
			{
				if (t != null && t.getTaskId() != null)
				{
					byId.putIfAbsent(t.getTaskId(), new TaskRef(t, globalHome, 0));
				}
			}
		}
		return new ArrayList<>(byId.values());
	}

	/**
	 * Plans for the goal. A real (non simulated) account that is logged out gets the log-in message and the planner is
	 * not called, so a bogus all-level-1 account is never planned. Off the client thread.
	 */
	public Result run(boolean simulate, boolean loggedIn, Inputs in, GoalMode goal)
	{
		if (!simulate && (!loggedIn || in == null || in.getState() == null))
		{
			return new Result(null, LOGGED_OUT_MESSAGE);
		}
		Travel t = travel;
		if (t == null)
		{
			t = travelFactory.apply(in.getChunks() == null ? Collections.<NuzlockeChunk>emptyList() : in.getChunks());
			travel = t;
		}
		PlanRequest req = PlanRequest.builder()
			.state(in.getState())
			.start(in.getPosition() == null ? UNKNOWN_START : in.getPosition())
			.goal(goal == null ? GoalMode.maxPoints() : goal)
			.tasks(in.getTasks())
			.completedTaskIds(in.getCompletedTaskIds())
			.data(data)
			.quests(quests)
			.deriver(deriver)
			.graph(t.getGraph())
			.transport(t.getTransport())
			.build();
		return new Result(planner.apply(req), null);
	}
}
