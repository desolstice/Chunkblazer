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
import com.chunkblazer.gps.travel.Acquisition;
import com.chunkblazer.gps.travel.CostMatrix;
import com.chunkblazer.gps.travel.ResolvedLocation;
import com.chunkblazer.gps.travel.TravelConfig;
import com.chunkblazer.gps.travel.UsableEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Inserts "buy teleport items" steps when owning a teleport would save a lot of travel. Greedy: each acquirable
 * transport edge (sorted by id) is evaluated against the current order; it is accepted when the cheapest insertion
 * of the shop visit still saves at least {@link #MIN_NET_SAVING_TICKS}, then the order is re-optimised once and kept
 * only if cheaper. Only shop purchases are modelled (making teleport items is not).
 */
public final class TeleportAcquirer
{
	public static final int MIN_NET_SAVING_TICKS = 100;
	public static final int MAX_ACQUISITIONS = 5;
	public static final int MAX_QTY = 25;
	/** At most this many edges are evaluated per plan (each costs a cost-matrix build). */
	public static final int MAX_CANDIDATES = 12;

	private final PlanRequest req;

	public TeleportAcquirer(PlanRequest req, NodeFactory factory)
	{
		this.req = req;
	}

	/** The (possibly extended) plan inputs. {@code matrix} covers START plus {@code nodes} with the acquired edges usable. */
	@Value
	public static class AcquireResult
	{
		List<PlanNode> nodes;
		Map<String, Tile> tiles;
		Map<String, List<String>> preds;
		int[] order;
		CostMatrix matrix;
		Set<String> acquiredEdgeIds;
	}

	/**
	 * @param nodes nodes 1..n (index 0 of the matrix is START)
	 * @param tiles routing tile per node id
	 * @param preds precedence by node id
	 * @param order visiting order, 1-based node indices
	 */
	public AcquireResult apply(List<PlanNode> nodes, Map<String, Tile> tiles, Map<String, List<String>> preds, int[] order)
	{
		return apply(nodes, tiles, preds, order, System.nanoTime() + Math.max(0L, req.getDeadlineMillis()) * 1_000_000L);
	}

	/** As above, but all work (matrix builds and re-solves) stops at {@code deadlineNanos} ({@link System#nanoTime} scale). */
	public AcquireResult apply(List<PlanNode> nodes, Map<String, Tile> tiles, Map<String, List<String>> preds, int[] order,
		long deadlineNanos)
	{
		List<PlanNode> curNodes = new ArrayList<>(nodes);
		Map<String, Tile> curTiles = new LinkedHashMap<>(tiles);
		Map<String, List<String>> curPreds = new LinkedHashMap<>(preds);
		int[] curOrder = order.clone();
		Set<String> acquired = new LinkedHashSet<>();
		CostMatrix base = Planner.buildMatrix(req, curNodes, curTiles, acquired, null);
		if (!req.isAllowTeleportAcquisition() || req.getTransport() == null || curNodes.isEmpty())
		{
			return new AcquireResult(curNodes, curTiles, curPreds, curOrder, base, acquired);
		}
		List<UsableEdge> candidates = new ArrayList<>();
		for (UsableEdge u : req.getTransport().usable(req.getState(), Collections.<String>emptySet(), true))
		{
			if (u.getAvailability() == UsableEdge.Availability.ACQUIRABLE)
			{
				candidates.add(u);
			}
		}
		candidates = relevant(candidates, curNodes, curTiles, curOrder);
		int accepted = 0;
		for (UsableEdge u : candidates)
		{
			if (accepted >= MAX_ACQUISITIONS || System.nanoTime() - deadlineNanos >= 0)
			{
				break;
			}
			String edgeId = u.getEdge().getId();
			if (u.getAcquisitions().size() != 1 || u.getAcquisition().getShopTile() == null
				|| !u.getAcquisition().getShopTile().isKnown())
			{
				continue; // multi-item edges and shops of unknown location are skipped
			}
			Acquisition acq = u.getAcquisition();
			long c0 = pathCost(curOrder, base);

			Set<String> withEdge = new LinkedHashSet<>(acquired);
			withEdge.add(edgeId);
			int n = curNodes.size();
			CostMatrix m1 = Planner.buildMatrix(req, curNodes, curTiles, withEdge, acq.getShopTile());
			int a = n + 1; // matrix index of the acquisition node
			int uses = 0;
			int firstUse = -1;
			List<Integer> users = new ArrayList<>();
			int prev = 0;
			for (int p = 0; p < curOrder.length; p++)
			{
				if (m1.route(prev, curOrder[p]).contains(edgeId))
				{
					uses++;
					users.add(curOrder[p]);
					if (firstUse < 0)
					{
						firstUse = p;
					}
				}
				prev = curOrder[p];
			}
			if (uses == 0)
			{
				continue;
			}
			long c1 = pathCost(curOrder, m1);
			long bestTotal = Long.MAX_VALUE;
			for (int p = 0; p <= firstUse; p++)
			{
				int before = p == 0 ? 0 : curOrder[p - 1];
				int after = curOrder[p];
				long total = c1 - m1.cost(before, after) + m1.cost(before, a) + m1.cost(a, after);
				if (total < bestTotal)
				{
					bestTotal = total;
				}
			}
			if (c0 - bestTotal < MIN_NET_SAVING_TICKS)
			{
				continue;
			}

			int qty = Math.max(Math.min(uses, MAX_QTY), Math.min(acq.getQty(), MAX_QTY));
			String name = acq.getItemName() == null ? "item " + acq.getItemId() : acq.getItemName();
			PlanNode node = PlanNode.builder()
				.id(ACQUIRE_PREFIX + edgeId)
				.type(NodeType.ACQUIRE_TELEPORT)
				.label("Buy " + name + " x" + qty + " at " + acq.getShop())
				.location(new ResolvedLocation(acq.getShopTile(), false, "NAME"))
				.effects(Effects.builder().item(acq.getItemId(), qty).build())
				.build();
			List<PlanNode> newNodes = new ArrayList<>(curNodes);
			newNodes.add(node);
			Map<String, Tile> newTiles = new LinkedHashMap<>(curTiles);
			newTiles.put(node.getId(), acq.getShopTile());
			Map<String, List<String>> newPreds = new LinkedHashMap<>(curPreds);
			newPreds.put(node.getId(), Collections.<String>emptyList());
			for (int user : users)
			{
				PlanNode un = curNodes.get(user - 1);
				List<String> ps = new ArrayList<>(newPreds.getOrDefault(un.getId(), Collections.<String>emptyList()));
				ps.add(node.getId());
				newPreds.put(un.getId(), ps);
			}
			// Cheapest valid insertion as the fallback order.
			int bestPos = 0;
			long best = Long.MAX_VALUE;
			for (int p = 0; p <= firstUse; p++)
			{
				int before = p == 0 ? 0 : curOrder[p - 1];
				long total = c1 - m1.cost(before, curOrder[p]) + m1.cost(before, a) + m1.cost(a, curOrder[p]);
				if (total < best)
				{
					best = total;
					bestPos = p;
				}
			}
			int[] inserted = new int[curOrder.length + 1];
			System.arraycopy(curOrder, 0, inserted, 0, bestPos);
			inserted[bestPos] = a;
			System.arraycopy(curOrder, bestPos, inserted, bestPos + 1, curOrder.length - bestPos);

			if (usesEdgeBeforeAcquire(inserted, m1, newNodes, withEdge))
			{
				continue; // even the plain insertion would route a leg through an edge not yet bought
			}
			int[][] ip = Planner.intPreds(newNodes, newPreds);
			int[] resolved = Solver.solve(n + 1, m1::cost, ip, req.getSeed(),
				Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L));
			int[] chosen = inserted;
			if (Solver.valid(resolved, ip) && Solver.pathCost(resolved, m1::cost) < Solver.pathCost(inserted, m1::cost)
				&& !usesEdgeBeforeAcquire(resolved, m1, newNodes, withEdge))
			{
				chosen = resolved;
			}
			curNodes = newNodes;
			curTiles = newTiles;
			curPreds = newPreds;
			curOrder = chosen;
			acquired = withEdge;
			base = m1;
			accepted++;
		}
		return new AcquireResult(curNodes, curTiles, curPreds, curOrder, base, acquired);
	}

	/**
	 * Candidates that some leg of the current route would use if every candidate were owned, most-used first, capped at
	 * {@link #MAX_CANDIDATES}. Multi-item edges and shops of unknown location are dropped here too.
	 */
	private List<UsableEdge> relevant(List<UsableEdge> candidates, List<PlanNode> curNodes, Map<String, Tile> curTiles,
		int[] curOrder)
	{
		List<UsableEdge> usable = new ArrayList<>();
		Set<String> all = new LinkedHashSet<>();
		for (UsableEdge u : candidates)
		{
			if (u.getAcquisitions().size() == 1 && u.getAcquisition().getShopTile() != null
				&& u.getAcquisition().getShopTile().isKnown())
			{
				usable.add(u);
				all.add(u.getEdge().getId());
			}
		}
		if (usable.isEmpty())
		{
			return usable;
		}
		CostMatrix m = Planner.buildMatrix(req, curNodes, curTiles, all, null);
		Map<String, Integer> legs = new LinkedHashMap<>();
		int prev = 0;
		for (int idx : curOrder)
		{
			for (String e : m.route(prev, idx))
			{
				if (all.contains(e))
				{
					legs.merge(e, 1, Integer::sum);
				}
			}
			prev = idx;
		}
		List<UsableEdge> out = new ArrayList<>();
		for (UsableEdge u : usable)
		{
			if (legs.containsKey(u.getEdge().getId()))
			{
				out.add(u);
			}
		}
		out.sort((x, y) -> {
			int c = Integer.compare(legs.get(y.getEdge().getId()), legs.get(x.getEdge().getId()));
			return c != 0 ? c : x.getEdge().getId().compareTo(y.getEdge().getId());
		});
		return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
	}

	static final String ACQUIRE_PREFIX = "acquire-teleport:";

	/** The edge id an ACQUIRE_TELEPORT node buys, or null for any other node. */
	static String acquiredEdgeOf(PlanNode node)
	{
		return node.getType() == NodeType.ACQUIRE_TELEPORT && node.getId().startsWith(ACQUIRE_PREFIX)
			? node.getId().substring(ACQUIRE_PREFIX.length()) : null;
	}

	/**
	 * True when some leg of {@code order} routes through an acquired edge before (or on the leg arriving at) that
	 * edge's acquire node. {@code order} holds 1-based indices into {@code nodes}; START is index 0 of the matrix.
	 */
	static boolean usesEdgeBeforeAcquire(int[] order, CostMatrix m, List<PlanNode> nodes, Set<String> acquired)
	{
		Set<String> bought = new LinkedHashSet<>();
		int prev = 0;
		for (int idx : order)
		{
			for (String e : m.route(prev, idx))
			{
				if (acquired.contains(e) && !bought.contains(e))
				{
					return true;
				}
			}
			String edge = acquiredEdgeOf(nodes.get(idx - 1));
			if (edge != null)
			{
				bought.add(edge);
			}
			prev = idx;
		}
		return false;
	}

	private static long pathCost(int[] order, CostMatrix m)
	{
		long total = 0;
		int prev = 0;
		for (int idx : order)
		{
			total += Math.min(m.cost(prev, idx), TravelConfig.INF);
			prev = idx;
		}
		return total;
	}
}
