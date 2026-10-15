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
		int accepted = 0;
		for (UsableEdge u : candidates)
		{
			if (accepted >= MAX_ACQUISITIONS)
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
				.id("acquire-teleport:" + edgeId)
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

			int[][] ip = Planner.intPreds(newNodes, newPreds);
			int[] resolved = Solver.solve(n + 1, m1::cost, ip, req.getSeed(), req.getDeadlineMillis());
			int[] chosen = inserted;
			if (Solver.valid(resolved, ip) && Solver.pathCost(resolved, m1::cost) < Solver.pathCost(inserted, m1::cost))
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
