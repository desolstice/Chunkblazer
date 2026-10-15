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

import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.travel.CostMatrix;
import com.chunkblazer.gps.travel.LocationResolver;
import com.chunkblazer.gps.travel.TravelConfig;
import com.chunkblazer.gps.travel.TravelContext;
import com.chunkblazer.gps.travel.TravelNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Facade that turns a {@link PlanRequest} into an ordered {@link Plan}: select nodes and providers, build the travel
 * cost matrix, order the nodes with the {@link Solver}, verify the order by replaying effects, and describe each step.
 *
 * <p>Assumptions:
 * <ul>
 * <li>Determinism: the same request (including the seed) gives the same plan <em>only when the solver finishes before
 * its deadline</em>. If {@code deadlineMillis} elapses mid-optimisation the best valid order found so far is returned,
 * and how far the search got depends on machine speed. Selection and the cost matrix are deterministic regardless of
 * timing.</li>
 * <li>Travel costs may be asymmetric; the solver never assumes {@code cost(i, j) == cost(j, i)} (the transport model
 * currently treats fairy ring, spirit tree and glider networks as symmetric hubs, which is unverified).</li>
 * <li>Walking between regions overestimates near region borders (region-centre routing); nodes on either side of a
 * border are never excluded or forced apart because of it.</li>
 * <li>A node that no other point can reach (locked chunk, unknown tile) is reported in {@link Plan#getUnplannable()}
 * together with everything that depends on it, never in the steps.</li>
 * <li>Only buying (shop-sourced acquisitions) is planned; making teleport items is not modelled.</li>
 * <li>A node without a tile takes the tile of its predecessor in its {@code after} chain, or the start tile, and its
 * step is flagged approximate. A first leg from an unusable start tile is reported as 0 ticks.</li>
 * <li>Pure and safe to call off the client thread: all state is local to one call of {@link #plan()}.</li>
 * </ul>
 */
public final class Planner
{
	private final PlanRequest req;

	public Planner(PlanRequest req)
	{
		this.req = req;
	}

	public Plan plan()
	{
		NodeFactory factory = new NodeFactory(req.getData(), req.getQuests(), req.getDeriver(),
			new LocationResolver(req.getData(), req.getQuests()), req.getDoneNodeIds());
		PrecedenceGraph graph = new Selector(req, factory).select();
		List<Plan.Unplannable> unplannable = new ArrayList<>(graph.getUnplannable());
		String version = req.getData() == null ? null : req.getData().getDataVersion();

		List<PlanNode> nodes = new ArrayList<>(graph.getNodes());
		Map<String, Tile> tiles = new HashMap<>();
		Set<String> carried = new LinkedHashSet<>();
		CostMatrix matrix;
		Set<String> acquiredEdges = Collections.emptySet();
		while (true)
		{
			if (nodes.isEmpty())
			{
				return new Plan(Collections.emptyList(), 0, unplannable, version);
			}
			resolveTiles(nodes, tiles, carried);
			matrix = buildMatrix(req, nodes, tiles, Collections.emptySet(), null);
			Set<Integer> blocked = unreachable(matrix, nodes.size());
			if (blocked.isEmpty())
			{
				break;
			}
			nodes = dropBlocked(nodes, blocked, graph, unplannable);
		}

		int n = nodes.size();
		Map<String, List<String>> predMap = new LinkedHashMap<>(graph.getPreds());
		final CostMatrix base = matrix;
		int[] order = Solver.solve(n, base::cost, intPreds(nodes, predMap), req.getSeed(), req.getDeadlineMillis());

		if (req.isAllowTeleportAcquisition())
		{
			TeleportAcquirer.AcquireResult r = new TeleportAcquirer(req, factory).apply(nodes, tiles, predMap, order);
			nodes = r.getNodes();
			tiles = r.getTiles();
			predMap = r.getPreds();
			order = r.getOrder();
			matrix = r.getMatrix();
			acquiredEdges = r.getAcquiredEdgeIds();
		}
		final CostMatrix m = matrix;

		// Defensive replay: every planned node's requirement must hold in the projected state.
		List<Integer> planned = new ArrayList<>();
		PlayerState projected = req.getState();
		for (int idx : order)
		{
			PlanNode node = nodes.get(idx - 1);
			List<Missing> missing = node.getRequirement().missing(projected);
			if (!missing.isEmpty())
			{
				unplannable.add(new Plan.Unplannable(node.getLabel(), missing, node.getId()));
				continue;
			}
			projected = node.getEffects().apply(projected);
			planned.add(idx);
		}

		Set<String> plannedIds = new LinkedHashSet<>();
		for (int idx : planned)
		{
			plannedIds.add(nodes.get(idx - 1).getId());
		}
		Map<String, PlanNode> firstDependent = firstDependents(nodes, predMap, plannedIds);
		List<PlanStep> steps = new ArrayList<>();
		long total = 0;
		Set<String> bought = new LinkedHashSet<>();
		int prev = 0;
		for (int idx : planned)
		{
			PlanNode node = nodes.get(idx - 1);
			int ticks = m.cost(prev, idx);
			boolean unknownLeg = ticks >= TravelConfig.INF;
			ticks = clampLeg(ticks); // unusable start tile or unroutable mid-plan leg: unknown, not infinite
			total += ticks;
			boolean approximate = unknownLeg || carried.contains(node.getId()) || node.getLocation() == null
				|| node.getLocation().isApproximate();
			steps.add(PlanStep.builder()
				.order(steps.size())
				.nodeId(node.getId())
				.type(node.getType())
				.label(node.getLabel())
				.location(tiles.get(node.getId()))
				.approximate(approximate)
				.travelTicks(ticks)
				.reason(reason(node, firstDependent.get(node.getId())))
				.missingRequirements(Collections.emptyList())
				.questId(node.getQuestId())
				.stepIndex(node.getStepIndex())
				.viaTransportEdgeIds(viaBought(m.route(prev, idx), acquiredEdges, bought))
				.build());
			String edge = TeleportAcquirer.acquiredEdgeOf(node);
			if (edge != null)
			{
				bought.add(edge);
			}
			prev = idx;
		}
		return new Plan(steps, (int) Math.min(total, Integer.MAX_VALUE), unplannable, version);
	}

	/** Drops acquired edges that are not yet bought at this point of the plan (the replay may have skipped a node). */
	static List<String> viaBought(List<String> route, Set<String> acquired, Set<String> bought)
	{
		if (acquired.isEmpty())
		{
			return route;
		}
		List<String> out = new ArrayList<>();
		for (String e : route)
		{
			if (!acquired.contains(e) || bought.contains(e))
			{
				out.add(e);
			}
		}
		return out;
	}

	// ---- travel ----

	/** A leg the cost model cannot route (INF) is reported as 0 ticks so it cannot saturate the total. */
	static int clampLeg(int ticks)
	{
		return ticks >= TravelConfig.INF ? 0 : ticks;
	}

	/** The tile used for routing each node: its own, else its predecessor's, else the start (flagged carried). */
	private void resolveTiles(List<PlanNode> nodes, Map<String, Tile> tiles, Set<String> carried)
	{
		tiles.clear();
		carried.clear();
		Map<String, PlanNode> byId = new HashMap<>();
		nodes.forEach(n -> byId.put(n.getId(), n));
		for (PlanNode n : nodes)
		{
			tiles.put(n.getId(), tileOf(n, byId, carried, 0));
		}
	}

	private Tile tileOf(PlanNode n, Map<String, PlanNode> byId, Set<String> carried, int depth)
	{
		Tile own = n.getLocation() == null ? null : n.getLocation().getTile();
		if (own != null && own.isKnown())
		{
			return own;
		}
		carried.add(n.getId());
		if (depth < byId.size())
		{
			for (String a : n.getAfter())
			{
				PlanNode p = byId.get(a);
				if (p != null)
				{
					return tileOf(p, byId, new LinkedHashSet<>(), depth + 1);
				}
			}
		}
		return req.getStart();
	}

	/**
	 * START plus the nodes (plus an optional extra tile, used by the acquirer for a candidate shop). Acquirable edges
	 * are never assumed: only {@code acquired} ones are usable besides what the player already has.
	 */
	static CostMatrix buildMatrix(PlanRequest req, List<PlanNode> nodes, Map<String, Tile> tiles, Set<String> acquired,
		Tile extra)
	{
		List<TravelNode> travel = new ArrayList<>();
		travel.add(new TravelNode("START", req.getStart()));
		for (PlanNode n : nodes)
		{
			travel.add(new TravelNode(n.getId(), tiles.get(n.getId())));
		}
		if (extra != null)
		{
			travel.add(new TravelNode("EXTRA", extra));
		}
		TravelContext ctx = new TravelContext(req.getState(), acquired, false);
		return CostMatrix.build(travel, ctx, req.getGraph(), req.getTransport());
	}

	/** Precedence as 1-based node indices (index 0 unused); predecessors outside the node list are ignored. */
	static int[][] intPreds(List<PlanNode> nodes, Map<String, List<String>> predMap)
	{
		int n = nodes.size();
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++)
		{
			index.put(nodes.get(i).getId(), i + 1);
		}
		int[][] preds = new int[n + 1][];
		preds[0] = new int[0];
		for (int i = 0; i < n; i++)
		{
			List<Integer> ps = new ArrayList<>();
			for (String id : predMap.getOrDefault(nodes.get(i).getId(), Collections.emptyList()))
			{
				Integer p = index.get(id);
				if (p != null && p != i + 1)
				{
					ps.add(p);
				}
			}
			preds[i + 1] = ps.stream().mapToInt(Integer::intValue).toArray();
		}
		return preds;
	}

	/** 1-based node indices that no other point (start included) can reach. */
	private static Set<Integer> unreachable(CostMatrix matrix, int n)
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (int j = 1; j <= n; j++)
		{
			boolean reachable = false;
			for (int i = 0; i <= n && !reachable; i++)
			{
				reachable = i != j && matrix.cost(i, j) < TravelConfig.INF;
			}
			if (!reachable)
			{
				out.add(j);
			}
		}
		return out;
	}

	/** Moves blocked nodes and everything that (transitively) needs them to unplannable; returns the survivors. */
	private static List<PlanNode> dropBlocked(List<PlanNode> nodes, Set<Integer> blocked, PrecedenceGraph graph,
		List<Plan.Unplannable> unplannable)
	{
		Map<String, PlanNode> root = new LinkedHashMap<>(); // dropped id -> the blocked node it traces back to
		for (int j : blocked)
		{
			PlanNode b = nodes.get(j - 1);
			root.put(b.getId(), b);
			unplannable.add(new Plan.Unplannable(b.getLabel(), Collections.singletonList(
				new Missing(Missing.Kind.CHUNK, "unreachable: " + b.getLabel() + " is in a locked or unknown location")),
				b.getId()));
		}
		boolean grew = true;
		while (grew)
		{
			grew = false;
			for (PlanNode n : nodes)
			{
				if (root.containsKey(n.getId()))
				{
					continue;
				}
				for (String p : graph.getPreds().getOrDefault(n.getId(), Collections.emptyList()))
				{
					PlanNode r = root.get(p);
					if (r != null)
					{
						root.put(n.getId(), r);
						unplannable.add(new Plan.Unplannable(n.getLabel(), Collections.singletonList(
							new Missing(Missing.Kind.UNKNOWN, "requires unreachable " + r.getLabel())), n.getId()));
						grew = true;
						break;
					}
				}
			}
		}
		List<PlanNode> kept = new ArrayList<>();
		for (PlanNode n : nodes)
		{
			if (!root.containsKey(n.getId()))
			{
				kept.add(n);
			}
		}
		return pruneOrphans(kept, graph);
	}

	/** Drops non-goal nodes that no surviving goal (transitively) needs, e.g. providers of a dropped goal. */
	private static List<PlanNode> pruneOrphans(List<PlanNode> kept, PrecedenceGraph graph)
	{
		Set<String> keep = new LinkedHashSet<>();
		Set<String> present = new LinkedHashSet<>();
		kept.forEach(n -> present.add(n.getId()));
		java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
		for (PlanNode n : kept)
		{
			if (graph.getGoals().contains(n.getId()) && keep.add(n.getId()))
			{
				queue.add(n.getId());
			}
		}
		while (!queue.isEmpty())
		{
			for (String p : graph.getPreds().getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (present.contains(p) && keep.add(p))
				{
					queue.add(p);
				}
			}
		}
		List<PlanNode> out = new ArrayList<>();
		for (PlanNode n : kept)
		{
			if (keep.contains(n.getId()))
			{
				out.add(n);
			}
		}
		return out;
	}

	// ---- output ----

	/** For each planned node, the first planned node (in graph order) that lists it as a precedence predecessor. */
	private static Map<String, PlanNode> firstDependents(List<PlanNode> nodes, Map<String, List<String>> predMap, Set<String> planned)
	{
		Map<String, PlanNode> out = new HashMap<>();
		Map<String, PlanNode> byId = new HashMap<>();
		nodes.forEach(x -> byId.put(x.getId(), x));
		for (PlanNode n : nodes)
		{
			if (!planned.contains(n.getId()))
			{
				continue;
			}
			for (String p : predMap.getOrDefault(n.getId(), Collections.emptyList()))
			{
				PlanNode pn = byId.get(p);
				if (pn != null && pn.getQuestId() != null && pn.getQuestId().equals(n.getQuestId()))
				{
					continue; // same-quest successor: the "Quest step i/n" text already says it
				}
				out.putIfAbsent(p, n);
			}
		}
		return out;
	}

	private String reason(PlanNode node, PlanNode dependent)
	{
		List<String> parts = new ArrayList<>();
		if (node.getType() == NodeType.QUEST_STEP && node.getQuestId() != null)
		{
			Optional<QuestModel> q = req.getQuests() == null ? Optional.empty() : req.getQuests().get(node.getQuestId());
			int total = q.isPresent() && q.get().getSteps() != null ? Math.max(1, q.get().getSteps().size()) : 1;
			int idx = node.getStepIndex() == null ? 0 : node.getStepIndex();
			String name = q.isPresent() && q.get().getName() != null ? q.get().getName() : node.getQuestId();
			String text = "Quest step " + (idx + 1) + "/" + total + ": " + name;
			if (!node.getEffects().getXp().isEmpty())
			{
				List<String> gains = new ArrayList<>();
				node.getEffects().getXp().forEach((skill, xp) -> gains.add(capitalise(skill) + " " + xp + " xp"));
				text += " (unlocks " + String.join(", ", gains) + ")";
			}
			parts.add(text);
		}
		if (dependent != null)
		{
			parts.add("Needed for: " + dependent.getLabel());
		}
		if (node.getPoints() > 0)
		{
			parts.add(node.getPoints() + " task points");
		}
		return parts.isEmpty() ? node.getLabel() : String.join("; ", parts);
	}

	private static String capitalise(String s)
	{
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
