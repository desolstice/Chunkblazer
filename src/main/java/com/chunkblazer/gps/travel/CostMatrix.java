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
package com.chunkblazer.gps.travel;

import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.data.TransportEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * All-pairs travel cost (game ticks) between nodes, using walking plus the transport edges usable in a
 * {@link TravelContext}. Built by hub decomposition: shortest paths are computed once over the (few) transport
 * endpoint tiles plus virtual vertices, then each node is attached to the hubs by walking. Costs are directed (one-way
 * transport is honoured), saturate at {@link TravelConfig#INF}, and are never negative. Immutable once built and
 * thread-safe.
 *
 * <p>Virtual vertices: one per network kind in {@link TravelConfig#NETWORK_KINDS} (every endpoint of an edge of that
 * kind enters it for free and leaves it for that edge's ticks) and a single ANYWHERE vertex that every location
 * reaches for free and that leads to the destination of each non-network edge without a {@code from} tile.
 *
 * <p>A node whose tile is null or unknown, or whose region is not accessible, cannot be left or entered: its row and
 * column are INF (the diagonal stays 0).
 */
public final class CostMatrix
{
	private static final int INF = TravelConfig.INF;

	private final int n;
	private final int[][] cost;
	private final int hubCount;
	private final int vertexCount;
	/** Index of the ANYWHERE vertex, or -1 when no anywhere edge exists. */
	private final int anywhere;
	private final int[][] hubDist;
	private final int[][] next;
	private final String[][] arcId;
	private final int[][] nodeToHub;
	private final int[][] hubToNode;
	private final boolean[] valid;
	private final int[][] walkDirect;

	private CostMatrix(int n, int[][] cost, int hubCount, int vertexCount, int anywhere, int[][] hubDist, int[][] next,
		String[][] arcId, int[][] nodeToHub, int[][] hubToNode, boolean[] valid, int[][] walkDirect)
	{
		this.n = n;
		this.cost = cost;
		this.hubCount = hubCount;
		this.vertexCount = vertexCount;
		this.anywhere = anywhere;
		this.hubDist = hubDist;
		this.next = next;
		this.arcId = arcId;
		this.nodeToHub = nodeToHub;
		this.hubToNode = hubToNode;
		this.valid = valid;
		this.walkDirect = walkDirect;
	}

	private static int add(int a, int b)
	{
		return a >= INF || b >= INF ? INF : Math.min(INF, a + b);
	}

	private static String key(Tile t)
	{
		return t.getX() + "," + t.getY() + "," + t.getPlane();
	}

	public static CostMatrix build(List<TravelNode> nodes, TravelContext ctx, ChunkGraph graph, TransportModel transport)
	{
		TravelContext context = ctx == null ? new TravelContext(null, null, false) : ctx;
		ChunkGraph g = graph == null ? ChunkGraph.build(Collections.<ChunkNode>emptyList()) : graph;
		WalkModel walk = new WalkModel(g.accessible(context.getAccessibleRegions()));
		List<UsableEdge> edges = transport == null
			? Collections.<UsableEdge>emptyList()
			: transport.usable(context.getState(), context.getAcquiredEdgeIds(), context.isIncludeAcquirable());

		int n = nodes == null ? 0 : nodes.size();
		Tile[] tiles = new Tile[n];
		boolean[] valid = new boolean[n];
		for (int i = 0; i < n; i++)
		{
			TravelNode node = nodes.get(i);
			Tile t = node == null ? null : node.getTile();
			tiles[i] = t;
			valid[i] = t != null && t.isKnown()
				&& context.getAccessibleRegions().contains(TileMath.regionId(t.getX(), t.getY()));
		}

		// Hubs are the distinct endpoint tiles, ordered by key so the result never depends on input order.
		Map<String, Tile> hubTiles = new TreeMap<>();
		TreeSet<String> kinds = new TreeSet<>();
		boolean anyAnywhere = false;
		List<UsableEdge> usable = new ArrayList<>();
		for (UsableEdge u : edges)
		{
			TransportEdge e = u.getEdge();
			if (e == null || e.getTo() == null || !e.getTo().isKnown()
				|| (e.getFrom() != null && !e.getFrom().isKnown()))
			{
				continue;
			}
			usable.add(u);
			hubTiles.putIfAbsent(key(e.getTo()), e.getTo());
			if (e.getFrom() != null)
			{
				hubTiles.putIfAbsent(key(e.getFrom()), e.getFrom());
			}
			String kind = e.getKind() == null ? "" : e.getKind();
			if (TravelConfig.NETWORK_KINDS.contains(kind))
			{
				kinds.add(kind);
			}
			else if (e.getFrom() == null)
			{
				anyAnywhere = true;
			}
		}
		int h = hubTiles.size();
		Map<String, Integer> hubIndex = new HashMap<>();
		Tile[] hubTile = new Tile[h];
		int idx = 0;
		for (Map.Entry<String, Tile> en : hubTiles.entrySet())
		{
			hubIndex.put(en.getKey(), idx);
			hubTile[idx++] = en.getValue();
		}
		Map<String, Integer> kindIndex = new HashMap<>();
		int v = h;
		for (String kind : kinds)
		{
			kindIndex.put(kind, v++);
		}
		int any = anyAnywhere ? v++ : -1;

		int[][] t = new int[v][v];
		int[][] nxt = new int[v][v];
		String[][] ids = new String[v][v];
		for (int a = 0; a < v; a++)
		{
			Arrays.fill(t[a], INF);
			Arrays.fill(nxt[a], -1);
			t[a][a] = 0;
		}
		for (int a = 0; a < h; a++)
		{
			for (int b = 0; b < h; b++)
			{
				if (a != b)
				{
					int w = walk.ticks(hubTile[a], hubTile[b]);
					if (w < INF)
					{
						t[a][b] = w;
						nxt[a][b] = b;
					}
				}
			}
		}
		for (UsableEdge u : usable)
		{
			TransportEdge e = u.getEdge();
			int ticks = Math.max(0, Math.min(u.getTicks(), INF));
			int to = hubIndex.get(key(e.getTo()));
			String kind = e.getKind() == null ? "" : e.getKind();
			if (TravelConfig.NETWORK_KINDS.contains(kind))
			{
				int hub = kindIndex.get(kind);
				relax(t, nxt, ids, to, hub, 0, null);
				if (e.getFrom() != null)
				{
					relax(t, nxt, ids, hubIndex.get(key(e.getFrom())), hub, 0, null);
				}
				relax(t, nxt, ids, hub, to, ticks, e.getId());
			}
			else if (e.getFrom() == null)
			{
				relax(t, nxt, ids, any, to, ticks, e.getId());
			}
			else
			{
				relax(t, nxt, ids, hubIndex.get(key(e.getFrom())), to, ticks, e.getId());
			}
		}
		if (any >= 0)
		{
			for (int a = 0; a < v; a++)
			{
				relax(t, nxt, ids, a, any, 0, null);
			}
		}
		for (int k = 0; k < v; k++)
		{
			for (int a = 0; a < v; a++)
			{
				if (t[a][k] >= INF)
				{
					continue;
				}
				for (int b = 0; b < v; b++)
				{
					int via = add(t[a][k], t[k][b]);
					if (via < t[a][b])
					{
						t[a][b] = via;
						nxt[a][b] = nxt[a][k];
					}
				}
			}
		}

		int[][] toHub = new int[n][h];
		int[][] fromHub = new int[h][n];
		for (int i = 0; i < n; i++)
		{
			for (int k = 0; k < h; k++)
			{
				toHub[i][k] = valid[i] ? walk.ticks(tiles[i], hubTile[k]) : INF;
				fromHub[k][i] = valid[i] ? walk.ticks(hubTile[k], tiles[i]) : INF;
			}
		}
		int[][] cost = new int[n][n];
		int[][] direct = new int[n][n];
		int[] dA = new int[h];
		for (int i = 0; i < n; i++)
		{
			Arrays.fill(cost[i], INF);
			Arrays.fill(direct[i], INF);
			cost[i][i] = 0;
			if (!valid[i])
			{
				continue;
			}
			for (int b = 0; b < h; b++)
			{
				int best = any >= 0 ? t[any][b] : INF;
				for (int s = 0; s < h; s++)
				{
					best = Math.min(best, add(toHub[i][s], t[s][b]));
				}
				dA[b] = best;
			}
			for (int j = 0; j < n; j++)
			{
				if (j == i || !valid[j])
				{
					continue;
				}
				int best = walk.ticks(tiles[i], tiles[j]);
				direct[i][j] = best;
				for (int b = 0; b < h; b++)
				{
					best = Math.min(best, add(dA[b], fromHub[b][j]));
				}
				cost[i][j] = best;
			}
		}
		return new CostMatrix(n, cost, h, v, any, t, nxt, ids, toHub, fromHub, valid, direct);
	}

	/** Keeps the cheaper arc between two vertices; ties keep the first (edges arrive sorted by id). */
	private static void relax(int[][] t, int[][] nxt, String[][] ids, int a, int b, int w, String id)
	{
		if (a == b || a < 0 || b < 0)
		{
			return;
		}
		if (w < t[a][b])
		{
			t[a][b] = w;
			nxt[a][b] = b;
			ids[a][b] = id;
		}
	}

	public int size()
	{
		return n;
	}

	/** Ticks to travel from node i to node j; INF when unreachable. Throws on out-of-range indices. */
	public int cost(int i, int j)
	{
		return cost[i][j];
	}

	/**
	 * Ids of the transport edges on the cheapest i to j trip, in travel order. Empty when walking is best, when i == j
	 * or when j is unreachable. Network entries are implicit: only the edge used to leave a network is listed.
	 */
	public List<String> route(int i, int j)
	{
		List<String> out = new ArrayList<>();
		if (i == j || cost[i][j] >= INF || !valid[i] || !valid[j])
		{
			return out;
		}
		int bestS = -1;
		int bestT = -1;
		int best = INF;
		// Sources: real hubs first, then ANYWHERE (reached for free). Strict improvement keeps ties deterministic.
		int sources = hubCount + (anywhere >= 0 ? 1 : 0);
		for (int si = 0; si < sources; si++)
		{
			int s = si < hubCount ? si : anywhere;
			int w = si < hubCount ? nodeToHub[i][si] : 0;
			for (int b = 0; b < hubCount; b++)
			{
				int c = add(add(w, hubDist[s][b]), hubToNode[b][j]);
				if (c < best)
				{
					best = c;
					bestS = s;
					bestT = b;
				}
			}
		}
		if (bestS < 0 || walkDirect[i][j] <= best)
		{
			// Walking directly is at least as cheap.
			return out;
		}
		int u = bestS;
		int guard = vertexCount;
		while (u != bestT && guard-- > 0)
		{
			int nx = next[u][bestT];
			if (nx < 0)
			{
				break;
			}
			String id = arcId[u][nx];
			if (id != null)
			{
				out.add(id);
			}
			u = nx;
		}
		return out;
	}
}
