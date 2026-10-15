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
		/** Region id to dense index, covering only accessible regions that appear in the graph. */
		private final Map<Integer, Integer> index = new HashMap<>();
		private final int[] regionOf;
		/** Per dense index: accessible neighbours (dense indices) and the centre-to-centre tile distance to each. */
		private final int[][] nbr;
		private final int[][] nbrCost;
		/** Per source region: dense-index array of path distance in tiles between region centres (-1 unreachable). */
		private final Map<Integer, int[]> fromSource = new ConcurrentHashMap<>();

		private View(Set<Integer> accessible)
		{
			this.accessible = accessible;
			List<Integer> ids = new ArrayList<>();
			for (Integer r : adj.keySet())
			{
				if (accessible.contains(r))
				{
					ids.add(r);
				}
			}
			int n = ids.size();
			regionOf = new int[n];
			for (int i = 0; i < n; i++)
			{
				regionOf[i] = ids.get(i);
				index.put(ids.get(i), i);
			}
			nbr = new int[n][];
			nbrCost = new int[n][];
			for (int i = 0; i < n; i++)
			{
				Tile c = TileMath.centre(regionOf[i]);
				List<Integer> to = new ArrayList<>();
				for (Integer nb : adj.get(regionOf[i]))
				{
					Integer j = index.get(nb);
					if (j != null)
					{
						to.add(j);
					}
				}
				nbr[i] = new int[to.size()];
				nbrCost[i] = new int[to.size()];
				for (int k = 0; k < nbr[i].length; k++)
				{
					nbr[i][k] = to.get(k);
					nbrCost[i][k] = TileMath.tileDistance(c, TileMath.centre(regionOf[nbr[i][k]]));
				}
			}
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
			Integer src = index.get(ra);
			Integer dst = index.get(rb);
			if (src == null || dst == null)
			{
				return TravelConfig.INF;
			}
			int[] dist = fromSource.get(ra);
			if (dist == null)
			{
				int[] computed = dijkstra(src);
				dist = fromSource.putIfAbsent(ra, computed);
				if (dist == null)
				{
					dist = computed;
				}
			}
			int path = dist[dst];
			if (path < 0)
			{
				return TravelConfig.INF;
			}
			tiles = (long) TileMath.tileDistance(a, TileMath.centre(ra)) + path + TileMath.tileDistance(TileMath.centre(rb), b);
			long ticks = (long) Math.ceil(tiles * TravelConfig.WALK_TICKS_PER_TILE * TravelConfig.CHUNK_DETOUR);
			return (int) Math.min(ticks, TravelConfig.INF);
		}

		/** Single-source shortest paths over dense indices; a lazy-deletion binary heap of (distance, vertex) longs. */
		private int[] dijkstra(int source)
		{
			int n = regionOf.length;
			int[] dist = new int[n];
			java.util.Arrays.fill(dist, -1);
			long[] heap = new long[Math.max(16, n)];
			int size = 0;
			dist[source] = 0;
			heap[size++] = (long) source;
			boolean[] done = new boolean[n];
			while (size > 0)
			{
				long top = heap[0];
				heap[0] = heap[--size];
				int pos = 0;
				while (true)
				{
					int l = 2 * pos + 1;
					if (l >= size)
					{
						break;
					}
					int m = l + 1 < size && heap[l + 1] < heap[l] ? l + 1 : l;
					if (heap[m] >= heap[pos])
					{
						break;
					}
					long tmp = heap[m];
					heap[m] = heap[pos];
					heap[pos] = tmp;
					pos = m;
				}
				int u = (int) (top & 0xFFFFFFFFL);
				if (done[u])
				{
					continue;
				}
				done[u] = true;
				int du = dist[u];
				for (int k = 0; k < nbr[u].length; k++)
				{
					int w = nbr[u][k];
					if (done[w])
					{
						continue;
					}
					int nd = du + nbrCost[u][k];
					if (dist[w] < 0 || nd < dist[w])
					{
						dist[w] = nd;
						if (size == heap.length)
						{
							heap = java.util.Arrays.copyOf(heap, size * 2);
						}
						int p = size++;
						long key = ((long) nd << 32) | w;
						while (p > 0)
						{
							int parent = (p - 1) / 2;
							if (heap[parent] <= key)
							{
								break;
							}
							heap[p] = heap[parent];
							p = parent;
						}
						heap[p] = key;
					}
				}
			}
			return dist;
		}
	}
}
