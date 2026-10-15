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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Undirected region graph built from chunk nodes; {@link #accessible} yields a thread-safe walking-cost view. */
public final class ChunkGraph
{
	private final Map<Integer, Set<Integer>> adj;

	private ChunkGraph(Map<Integer, Set<Integer>> adj)
	{
		this.adj = adj;
	}

	public static ChunkGraph build(List<ChunkNode> nodes)
	{
		Map<Integer, Set<Integer>> adj = new HashMap<>();
		if (nodes != null)
		{
			for (ChunkNode n : nodes)
			{
				if (n == null)
				{
					continue;
				}
				List<Integer> regions = nonNull(n.getRegionIds());
				List<Integer> neighbors = nonNull(n.getNeighborRegionIds());
				for (Integer r : regions)
				{
					adj.computeIfAbsent(r, k -> new HashSet<>());
				}
				for (Integer a : regions)
				{
					for (Integer b : regions)
					{
						if (!a.equals(b))
						{
							adj.get(a).add(b);
						}
					}
					for (Integer nb : neighbors)
					{
						if (nb == null || nb.equals(a))
						{
							continue;
						}
						adj.get(a).add(nb);
						adj.computeIfAbsent(nb, k -> new HashSet<>()).add(a);
					}
				}
			}
		}
		return new ChunkGraph(adj);
	}

	private static List<Integer> nonNull(List<Integer> in)
	{
		List<Integer> out = new ArrayList<>();
		if (in != null)
		{
			for (Integer i : in)
			{
				if (i != null)
				{
					out.add(i);
				}
			}
		}
		return out;
	}

	public View accessible(Set<Integer> regions)
	{
		return new View(regions == null ? new HashSet<>() : new HashSet<>(regions));
	}

	public final class View
	{
		private final Set<Integer> accessible;
		/** Per source region: region id to path distance in tiles between region centres. */
		private final Map<Integer, Map<Integer, Integer>> fromSource = new ConcurrentHashMap<>();

		private View(Set<Integer> accessible)
		{
			this.accessible = accessible;
		}

		public boolean isAccessible(int regionId)
		{
			return accessible.contains(regionId);
		}

		public int walkTicks(Tile a, Tile b)
		{
			if (a == null || b == null || !a.isKnown() || !b.isKnown())
			{
				return TravelConfig.INF;
			}
			int ra = TileMath.regionId(a.getX(), a.getY());
			int rb = TileMath.regionId(b.getX(), b.getY());
			if (!isAccessible(ra) || !isAccessible(rb))
			{
				return TravelConfig.INF;
			}
			double tiles;
			if (ra == rb)
			{
				tiles = TileMath.tileDistance(a, b);
				return (int) Math.ceil(tiles * TravelConfig.WALK_TICKS_PER_TILE);
			}
			Map<Integer, Integer> dist = fromSource.get(ra);
				if (dist == null)
				{
					Map<Integer, Integer> computed = dijkstra(ra);
					dist = fromSource.putIfAbsent(ra, computed);
					if (dist == null)
					{
						dist = computed;
					}
				}
				Integer path = dist.get(rb);
			if (path == null)
			{
				return TravelConfig.INF;
			}
			tiles = (long) TileMath.tileDistance(a, TileMath.centre(ra)) + path + TileMath.tileDistance(TileMath.centre(rb), b);
			long ticks = (long) Math.ceil(tiles * TravelConfig.WALK_TICKS_PER_TILE * TravelConfig.CHUNK_DETOUR);
			return (int) Math.min(ticks, TravelConfig.INF);
		}

		private Map<Integer, Integer> dijkstra(int source)
		{
			Map<Integer, Integer> dist = new HashMap<>();
			Set<Integer> done = new HashSet<>();
			PriorityQueue<int[]> pq = new PriorityQueue<>((x, y) -> Integer.compare(x[1], y[1]));
			dist.put(source, 0);
			pq.add(new int[]{source, 0});
			while (!pq.isEmpty())
			{
				int[] cur = pq.poll();
				if (!done.add(cur[0]))
				{
					continue;
				}
				Set<Integer> next = adj.get(cur[0]);
				if (next == null)
				{
					continue;
				}
				Tile c = TileMath.centre(cur[0]);
				for (Integer nb : next)
				{
					if (!accessible.contains(nb) || done.contains(nb))
					{
						continue;
					}
					int nd = cur[1] + TileMath.tileDistance(c, TileMath.centre(nb));
					Integer old = dist.get(nb);
					if (old == null || nd < old)
					{
						dist.put(nb, nd);
						pq.add(new int[]{nb, nd});
					}
				}
			}
			return dist;
		}
	}
}
