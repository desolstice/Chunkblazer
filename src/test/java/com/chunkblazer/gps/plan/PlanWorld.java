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
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.travel.ChunkGraph;
import com.chunkblazer.gps.travel.ChunkNode;
import com.chunkblazer.gps.travel.CostMatrix;
import com.chunkblazer.gps.travel.LocationResolver;
import com.chunkblazer.gps.travel.TileMath;
import com.chunkblazer.gps.travel.TransportModel;
import com.chunkblazer.gps.travel.TravelContext;
import com.chunkblazer.gps.travel.TravelNode;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Test-only fixture: a small synthetic world (tasks, quests, training, locations, chunk graph without transports)
 * from which a {@link PlanRequest} can be built. Shared by the planner tests.
 */
final class PlanWorld
{
	private final PlanRequest request;
	private final NodeFactory factory;

	private PlanWorld(PlanRequest request, NodeFactory factory)
	{
		this.request = request;
		this.factory = factory;
	}

	static Builder builder()
	{
		return new Builder();
	}

	/** A fragment of a quest definition. */
	static final class QuestPart
	{
		final String stepJson;
		final String requiredQuest;

		private QuestPart(String stepJson, String requiredQuest)
		{
			this.stepJson = stepJson;
			this.requiredQuest = requiredQuest;
		}
	}

	static QuestPart step(String text, int x, int y)
	{
		return new QuestPart("{\"text\":\"" + text + "\",\"kind\":\"OTHER\",\"location\":{\"x\":" + x + ",\"y\":" + y
			+ ",\"plane\":0,\"approximate\":false,\"source\":\"TEST\"},\"items\":[]}", null);
	}

	static QuestPart stepNoLocation(String text)
	{
		return new QuestPart("{\"text\":\"" + text + "\",\"kind\":\"OTHER\",\"location\":null,\"items\":[]}", null);
	}

	static QuestPart requiresQuest(String questId)
	{
		return new QuestPart(null, questId);
	}

	static int region(int x, int y)
	{
		return TileMath.regionId(x, y);
	}

	Tile tile(int x, int y)
	{
		return new Tile(x, y, 0, false, "TEST");
	}

	/** Plain walking ticks between two tiles, as the cost model sees them. */
	int walkTicks(Tile a, Tile b)
	{
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", a), new TravelNode("B", b));
		return CostMatrix.build(nodes, new TravelContext(request.getState(), Collections.emptySet(), false),
			request.getGraph(), request.getTransport()).cost(0, 1);
	}

	PlanWorld maxNodes(int n)
	{
		return new PlanWorld(request.toBuilder().maxNodes(n).build(), factory);
	}

	/**
	 * Deterministic big world: a 20x20 grid of unlocked regions, {@code taskCount} level-1 tasks, 30 quests of 3-6
	 * steps (each completed through a QUEST_CHECK task) and 40 free spell teleports; no acquirables.
	 */
	static PlanWorld random(long seed, int taskCount)
	{
		return random(seed, taskCount, 0);
	}

	/** As {@link #random(long, int)} plus {@code acquirable} item-gated teleports each sold by a shop, with acquisition allowed. */
	static PlanWorld random(long seed, int taskCount, int acquirable)
	{
		Random rnd = new Random(seed);
		int base = 3000;
		int size = 20 * 64;
		Builder b = builder().start(base + size / 2, base + size / 2);
		for (int rx = 0; rx < 20; rx++)
		{
			for (int ry = 0; ry < 20; ry++)
			{
				b.unlocked(TileMath.regionId(base + rx * 64, base + ry * 64));
			}
		}
		for (int i = 0; i < taskCount; i++)
		{
			b.task("t" + i, 1, base + rnd.nextInt(size), base + rnd.nextInt(size));
		}
		for (int q = 0; q < 30; q++)
		{
			int steps = 3 + rnd.nextInt(4);
			QuestPart[] parts = new QuestPart[steps];
			for (int s = 0; s < steps; s++)
			{
				parts[s] = step("q" + q + "s" + s, base + rnd.nextInt(size), base + rnd.nextInt(size));
			}
			b.quest("Q" + q, parts);
			b.questTask("Q" + q, 1, TileMath.regionId(base, base));
		}
		for (int e = 0; e < 40; e++)
		{
			b.freeTeleport("tp" + e, base + rnd.nextInt(size), base + rnd.nextInt(size));
		}
		for (int e = 0; e < acquirable; e++)
		{
			b.itemTeleport("atp" + e, 600 + e, base + rnd.nextInt(size), base + rnd.nextInt(size));
			b.shop("Shop" + e, 600 + e, base + rnd.nextInt(size));
		}
		b.allowAcquisition(acquirable > 0);
		return b.build();
	}

	PlanRequest requestWithAcquisition(boolean allow)
	{
		return request.toBuilder().allowTeleportAcquisition(allow).build();
	}

	PlanRequest request()
	{
		return request;
	}

	/** Same world with a different goal / tweaks applied to the request. */
	PlanRequest request(java.util.function.UnaryOperator<PlanRequest.PlanRequestBuilder> tweak)
	{
		return tweak.apply(request.toBuilder()).build();
	}

	/** The node the selector produced for this step (matched by label), or null. */
	PlanNode nodeFor(PlanStep step)
	{
		for (PlanNode n : new Selector(request, factory).select().getNodes())
		{
			if (n.getId().equals(step.getNodeId()))
			{
				return n;
			}
		}
		return null;
	}

	static final class Builder
	{
		private final Set<Integer> unlocked = new LinkedHashSet<>();
		private final List<TaskRef> tasks = new ArrayList<>();
		private final List<String> quests = new ArrayList<>();
		private final List<String> training = new ArrayList<>();
		private final List<String> locations = new ArrayList<>();
		private PlayerState.Builder state = PlayerState.builder();
		private Tile start = new Tile(3200, 3200, 0, false, "START");
		private int nextNpcId = 9000;
		private GoalMode goal = GoalMode.maxPoints();
		private long deadlineMillis = 800;
		private final List<String> edges = new ArrayList<>();
		private final List<String> items = new ArrayList<>();
		private boolean allowAcquisition;

		Builder unlocked(int... regions)
		{
			for (int r : regions)
			{
				unlocked.add(r);
			}
			return this;
		}

		/** Unlocks every region along y between the two x tiles (inclusive), so walking is possible but long. */
		Builder unlockedRange(int fromX, int toX, int y)
		{
			for (int x = fromX; x <= toX + 63; x += 64)
			{
				unlocked.add(TileMath.regionId(x, y));
			}
			return this;
		}

		/** A SPELL teleport usable from anywhere to (toX, 3200) that needs one of item {@code itemId}. */
		Builder spellTeleport(String id, int itemId, int toX)
		{
			edges.add("{\"id\":\"" + id + "\",\"kind\":\"SPELL\",\"name\":\"" + id + "\",\"from\":null,\"to\":{\"x\":" + toX
				+ ",\"y\":3200,\"plane\":0},\"ticks\":5,\"requires\":{\"skills\":{},\"quests\":[],\"items\":[{\"ids\":[" + itemId
				+ "],\"qty\":1}]},\"consumesItem\":true}");
			return this;
		}

		/** A SPELL teleport usable from anywhere to (toX, toY) that needs one of item {@code itemId}. */
		Builder itemTeleport(String id, int itemId, int toX, int toY)
		{
			edges.add("{\"id\":\"" + id + "\",\"kind\":\"SPELL\",\"name\":\"" + id + "\",\"from\":null,\"to\":{\"x\":" + toX
				+ ",\"y\":" + toY + ",\"plane\":0},\"ticks\":5,\"requires\":{\"skills\":{},\"quests\":[],\"items\":[{\"ids\":[" + itemId
				+ "],\"qty\":1}]},\"consumesItem\":true}");
			return this;
		}

		/** A SPELL teleport usable from anywhere with no item requirement. */
		Builder freeTeleport(String id, int toX, int toY)
		{
			edges.add("{\"id\":\"" + id + "\",\"kind\":\"SPELL\",\"name\":\"" + id + "\",\"from\":null,\"to\":{\"x\":" + toX
				+ ",\"y\":" + toY + ",\"plane\":0},\"ticks\":5,\"requires\":{\"skills\":{},\"quests\":[],\"items\":[]},"
				+ "\"consumesItem\":false}");
			return this;
		}

		/** A shop at (x, 3200) selling the item. */
		Builder shop(String name, int itemId, int x)
		{
			items.add("{\"id\":" + itemId + ",\"name\":\"Rune " + itemId + "\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"" + name
				+ "\",\"price\":17}]}");
			locations.add("{\"name\":\"" + name + "\",\"kind\":\"SHOP\",\"ids\":[],\"x\":" + x + ",\"y\":3200,\"plane\":0}");
			return this;
		}

		Builder ownItem(int itemId, int qty)
		{
			state.item(itemId, qty);
			return this;
		}

		Builder allowAcquisition(boolean allow)
		{
			this.allowAcquisition = allow;
			return this;
		}

		Builder start(int x, int y)
		{
			start = new Tile(x, y, 0, false, "START");
			return this;
		}

		Builder goal(GoalMode goal)
		{
			this.goal = goal;
			return this;
		}

		Builder deadlineMillis(long ms)
		{
			this.deadlineMillis = ms;
			return this;
		}

		/** Adds xp to the starting player state. */
		Builder xp(String skill, long xp)
		{
			state.xp(skill, xp);
			return this;
		}

		/** A task placed at (x, y), homed in the region holding that tile. */
		Builder task(String id, String type, int level, int points, int x, int y)
		{
			int npc = nextNpcId++;
			locations.add("{\"name\":\"Loc " + id + "\",\"kind\":\"NPC\",\"ids\":[" + npc + "],\"x\":" + x + ",\"y\":" + y
				+ ",\"plane\":0}");
			String json = "{\"name\":\"" + id + "\",\"taskID\":\"" + id + "\",\"completion_type\":\"" + type + "\",\"level\":"
				+ level + ",\"base_points\":" + points + ",\"target_npc\":{\"name\":\"Loc " + id + "\",\"npc_ids\":[" + npc
				+ "]}}";
			tasks.add(new TaskRef(new Gson().fromJson(json, NuzlockeTask.class), TileMath.regionId(x, y), 0));
			return this;
		}

		/** A level-1 task with no skill demand. */
		Builder task(String id, int points, int x, int y)
		{
			return task(id, "FISHING", 1, points, x, y);
		}

		/** A QUEST_CHECK task that makes the planner do the whole quest. */
		Builder questTask(String questId, int points, int homeRegion)
		{
			String json = "{\"name\":\"Complete " + questId + "\",\"taskID\":\"qc_" + questId
				+ "\",\"completion_type\":\"QUEST_CHECK\",\"base_points\":" + points + ",\"constraints\":{\"quest\":\""
				+ questId + "\"}}";
			tasks.add(new TaskRef(new Gson().fromJson(json, NuzlockeTask.class), homeRegion, 0));
			return this;
		}

		Builder quest(String id, QuestPart... parts)
		{
			return quest(id, 0, null, parts);
		}

		/** A quest rewarding {@code xp} in {@code skill}. */
		Builder quest(String id, long xp, String skill, QuestPart... parts)
		{
			return questJson(id, xp, skill, null, parts);
		}

		/** A quest with a lamp-style choice reward valid for the given skills (empty = any). */
		Builder questWithChoice(String id, int amount, String choiceSkill, QuestPart... parts)
		{
			return questJson(id, 0, null, "{\"amount\":" + amount + ",\"count\":1,\"skills\":[\"" + choiceSkill + "\"]}",
				parts);
		}

		private Builder questJson(String id, long xp, String skill, String choice, QuestPart... parts)
		{
			List<String> steps = new ArrayList<>();
			String required = "";
			for (QuestPart p : parts)
			{
				if (p.stepJson != null)
				{
					steps.add(p.stepJson.replaceFirst("\\{", "{\"index\":" + steps.size() + ","));
				}
				else
				{
					required = "\"" + p.requiredQuest + "\"";
				}
			}
			quests.add("{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"requirements\":{\"skills\":{},\"quests\":["
				+ required + "],\"questPoints\":0,\"combatLevel\":0,\"items\":[]},\"rewards\":{\"xp\":{"
				+ (skill == null ? "" : "\"" + skill + "\":" + xp) + "},\"choiceXp\":[" + (choice == null ? "" : choice)
				+ "],\"questPoints\":1,\"unlocks\":[]},\"steps\":[" + String.join(",", steps) + "]}");
			return this;
		}

		Builder trainingMethod(String skill, String name, int minLevel, int maxLevel, int xpPerHour, int x, int y)
		{
			training.add("{\"skill\":\"" + skill + "\",\"name\":\"" + name + "\",\"minLevel\":" + minLevel + ",\"maxLevel\":"
				+ maxLevel + ",\"xpPerHour\":" + xpPerHour + ",\"location\":{\"x\":" + x + ",\"y\":" + y
				+ ",\"plane\":0,\"approximate\":false,\"source\":\"TEST\"},\"items\":[],\"approximate\":false}");
			return this;
		}

		PlanWorld build()
		{
			for (int r : unlocked)
			{
				state.chunk(r);
			}
			PlayerState playerState = state.build();
			GpsData data = GpsTestFixtures.dataWithAll(
				edges.isEmpty() ? null : "{\"schema\":1,\"edges\":[" + String.join(",", edges) + "]}",
				items.isEmpty() ? null : "{\"schema\":1,\"items\":[" + String.join(",", items) + "]}",
				locations.isEmpty() ? null : "{\"schema\":1,\"entries\":[" + String.join(",", locations) + "]}",
				quests.isEmpty() ? null : "{\"schema\":1,\"quests\":[" + String.join(",", quests) + "]}",
				training.isEmpty() ? null : "{\"schema\":1,\"methods\":[" + String.join(",", training) + "]}");
			QuestCatalog catalog = QuestCatalog.build(data, Collections.emptyMap());
			List<ChunkNode> chunks = new ArrayList<>();
			for (int r : unlocked)
			{
				List<Integer> neighbours = new ArrayList<>();
				for (int o : unlocked)
				{
					if (o != r && Math.abs(TileMath.regionX(o) - TileMath.regionX(r)) <= 1
						&& Math.abs(TileMath.regionY(o) - TileMath.regionY(r)) <= 1)
					{
						neighbours.add(o);
					}
				}
				chunks.add(new ChunkNode(Collections.singletonList(r), neighbours, false, Collections.emptyList()));
			}
			PlanRequest request = PlanRequest.builder()
				.state(playerState)
				.start(start)
				.goal(goal)
				.tasks(tasks)
				.data(data)
				.quests(catalog)
				.deriver(new RequirementDeriver(catalog, Overrides.empty(), data))
				.graph(ChunkGraph.build(chunks))
				.transport(new TransportModel(data, chunks, catalog))
				.deadlineMillis(deadlineMillis)
				.allowTeleportAcquisition(allowAcquisition)
				.build();
			NodeFactory factory = new NodeFactory(data, catalog, request.getDeriver(), new LocationResolver(data, catalog));
			return new PlanWorld(request, factory);
		}
	}
}
