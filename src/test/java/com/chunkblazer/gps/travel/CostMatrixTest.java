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

import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.data.TransportEdge;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CostMatrixTest
{
	private static Tile t(int x, int y)
	{
		return new Tile(x, y, 0, false, null);
	}

	private static final String NO_ITEMS = "{\"schema\":1,\"items\":[]}";
	private static final String NO_LOCS = "{\"schema\":1,\"entries\":[]}";

	private static String tileJson(int x, int y)
	{
		return "{\"x\":" + x + ",\"y\":" + y + ",\"plane\":0}";
	}

	private static String edgeJson(String id, String kind, String from, String to, long ticks)
	{
		return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"name\":\"" + id + "\",\"from\":" + from + ",\"to\":" + to
			+ ",\"ticks\":" + ticks + ",\"requires\":{\"skills\":{},\"quests\":[],\"items\":[]},\"consumesItem\":false}";
	}

	private static String edgesJson(String... es)
	{
		return "{\"schema\":1,\"edges\":[" + String.join(",", es) + "]}";
	}

	private static TransportModel model(String transport, List<ChunkNode> chunks)
	{
		GpsData d = transport == null ? GpsData.empty(null) : GpsTestFixtures.dataWith(transport, NO_ITEMS, NO_LOCS);
		return new TransportModel(d, chunks, QuestCatalog.build(d, Collections.emptyMap()));
	}

	private static final int RA = TileMath.regionId(3200, 3200);
	private static final int MID = TileMath.regionId(3300, 3200);
	private static final int RB = TileMath.regionId(3400, 3200);

	private static List<ChunkNode> line()
	{
		return Arrays.asList(
			new ChunkNode(Arrays.asList(RA), Arrays.asList(MID), false, Collections.emptyList()),
			new ChunkNode(Arrays.asList(MID), Arrays.asList(RA, RB), false, Collections.emptyList()),
			new ChunkNode(Arrays.asList(RB), Arrays.asList(MID), false, Collections.emptyList()));
	}

	private static final String TELE = edgesJson(edgeJson("tele", "SPELL", "null", tileJson(3300, 3200), 10));

	private CostMatrix build(String transport, boolean unlockB)
	{
		PlayerState.Builder b = PlayerState.builder().chunk(RA).chunk(MID);
		if (unlockB)
		{
			b.chunk(RB);
		}
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)));
		return CostMatrix.build(nodes, new TravelContext(b.build(), Collections.emptySet(), false),
			ChunkGraph.build(line()), model(transport, line()));
	}

	@Test
	void diagonalIsZero()
	{
		CostMatrix m = build(null, true);
		assertEquals(0, m.cost(0, 0));
		assertEquals(0, m.cost(1, 1));
		assertEquals(2, m.size());
	}

	@Test
	void walkingOnlyThroughUnlockedChunks()
	{
		CostMatrix m = build(null, true);
		assertTrue(m.cost(0, 1) > 0 && m.cost(0, 1) < TravelConfig.INF);
		assertTrue(m.route(0, 1).isEmpty());
	}

	@Test
	void lockedDestinationIsInfinite()
	{
		CostMatrix m = build(null, false);
		assertEquals(TravelConfig.INF, m.cost(0, 1));
		assertEquals(TravelConfig.INF, m.cost(1, 0));
		assertTrue(m.route(0, 1).isEmpty());
	}

	@Test
	void teleportShortensTheTripAndRouteNamesIt()
	{
		CostMatrix walk = build(null, true);
		CostMatrix tele = build(TELE, true);
		assertTrue(tele.cost(0, 1) < walk.cost(0, 1));
		assertEquals(Collections.singletonList("tele"), tele.route(0, 1));
	}

	@Test
	void routeEdgesIndexOfAndReachabilityHelpers()
	{
		CostMatrix tele = build(TELE, true);
		assertEquals(1, tele.indexOf("B"));
		assertEquals(-1, tele.indexOf("nope"));
		assertEquals(-1, tele.indexOf(null));
		assertEquals(1, tele.routeEdges(0, 1).size());
		assertEquals("tele", tele.routeEdges(0, 1).get(0).getEdge().getId());
		assertTrue(tele.isReachable(0, 1));
		assertTrue(!build(null, false).isReachable(0, 1));
	}

	@Test
	void unknownTileNodeIsUnroutable()
	{
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("X", null));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(PlayerState.freshAccount(), Collections.emptySet(), false),
			ChunkGraph.build(Collections.emptyList()), model(null, Collections.emptyList()));
		assertEquals(TravelConfig.INF, m.cost(0, 1));
		assertEquals(TravelConfig.INF, m.cost(1, 0));
		assertEquals(0, m.cost(1, 1));
	}

	@Test
	void unknownAndNullTilesNeverLeakThroughAnywhereEdges()
	{
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("U", new Tile(null, null, 0, false, "NONE")),
			new TravelNode("N", null), null, new TravelNode("B", t(3300, 3200)));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).build();
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(TELE, line()));
		for (int k : new int[]{1, 2, 3})
		{
			for (int o = 0; o < nodes.size(); o++)
			{
				if (o != k)
				{
					assertEquals(TravelConfig.INF, m.cost(k, o));
					assertEquals(TravelConfig.INF, m.cost(o, k));
				}
			}
		}
		assertTrue(m.cost(0, 4) < TravelConfig.INF);
	}

	@Test
	void nodeInLockedRegionCannotTeleportOut()
	{
		// C sits in the locked region RB even though the teleport lands in an unlocked region.
		List<TravelNode> nodes = Arrays.asList(new TravelNode("C", t(3400, 3200)), new TravelNode("B", t(3300, 3200)));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).build();
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(TELE, line()));
		assertEquals(TravelConfig.INF, m.cost(0, 1));
		assertEquals(TravelConfig.INF, m.cost(1, 0));
	}

	@Test
	void emptyOrNullNodeListGivesEmptyMatrix()
	{
		TravelContext ctx = new TravelContext(PlayerState.freshAccount(), null, true);
		assertEquals(0, CostMatrix.build(Collections.emptyList(), ctx, ChunkGraph.build(null), model(TELE, null)).size());
		assertEquals(0, CostMatrix.build(null, null, null, null).size());
	}

	@Test
	void nullCollaboratorsAreTolerated()
	{
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("A2", t(3210, 3200)));
		CostMatrix m = CostMatrix.build(nodes, null, null, null);
		assertEquals(TravelConfig.INF, m.cost(0, 1));
		assertEquals(0, m.cost(0, 0));
	}

	@Test
	void oneWayTransportIsAsymmetric()
	{
		// from A's tile to B's tile only.
		String t = edgesJson(edgeJson("oneway", "TRAPDOOR", tileJson(3200, 3200), tileJson(3400, 3200), 3));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(t, line()));
		assertEquals(3, m.cost(0, 1));
		assertTrue(m.cost(1, 0) > 3);
		assertEquals(Collections.singletonList("oneway"), m.route(0, 1));
		assertTrue(m.route(1, 0).isEmpty());
	}

	@Test
	void networkKindLinksItsEndpoints()
	{
		// Two ring stops: entering at one tile, leaving at the other costs the leaving edge's ticks.
		String t = edgesJson(
			edgeJson("ring_a", "FAIRY_RING", "null", tileJson(3200, 3200), 7),
			edgeJson("ring_b", "FAIRY_RING", "null", tileJson(3400, 3200), 9));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(t, line()));
		assertEquals(9, m.cost(0, 1));
		assertEquals(7, m.cost(1, 0));
		assertEquals(Collections.singletonList("ring_b"), m.route(0, 1));
		assertEquals(Collections.singletonList("ring_a"), m.route(1, 0));
	}

	@Test
	void networkKindDoesNotActAsAnywhere()
	{
		String t = edgesJson(edgeJson("ring_b", "FAIRY_RING", "null", tileJson(3400, 3200), 9));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		// Only one stop exists, so a node far from it cannot use the network to reach it faster than walking.
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(t, line()));
		assertTrue(m.route(0, 1).isEmpty());
	}

	@Test
	void anywhereEdgeReachableFromAnyNode()
	{
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)),
			new TravelNode("M", t(3300, 3200)));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(TELE, line()));
		// Both A and B reach M (the teleport target) for the teleport's 10 ticks.
		assertEquals(10, m.cost(0, 2));
		assertEquals(10, m.cost(1, 2));
		assertEquals(Collections.singletonList("tele"), m.route(1, 2));
		assertEquals(0, m.cost(2, 2));
	}

	@Test
	void hugeTicksSaturateWithoutOverflow()
	{
		String t = edgesJson(
			edgeJson("huge1", "SPELL", "null", tileJson(3300, 3200), Integer.MAX_VALUE),
			edgeJson("huge2", "SPELL", tileJson(3300, 3200), tileJson(3400, 3200), Integer.MAX_VALUE));
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)),
			new TravelNode("M", t(3300, 3200)));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(t, line()));
		assertAllInRange(m);
	}

	private static void assertAllInRange(CostMatrix m)
	{
		for (int i = 0; i < m.size(); i++)
		{
			for (int j = 0; j < m.size(); j++)
			{
				int c = m.cost(i, j);
				assertTrue(c >= 0 && c <= TravelConfig.INF, "cost(" + i + "," + j + ")=" + c);
			}
		}
	}

	@Test
	void buildIsDeterministicAndIndependentOfEdgeOrder()
	{
		String e1 = edgeJson("a", "SPELL", "null", tileJson(3300, 3200), 10);
		String e2 = edgeJson("b", "SPELL", "null", tileJson(3300, 3200), 10);
		String e3 = edgeJson("c", "FAIRY_RING", "null", tileJson(3400, 3200), 4);
		PlayerState s = PlayerState.builder().chunk(RA).chunk(MID).chunk(RB).build();
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)),
			new TravelNode("M", t(3300, 3200)));
		CostMatrix x = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(edgesJson(e1, e2, e3), line()));
		CostMatrix y = CostMatrix.build(nodes, new TravelContext(s, null, false), ChunkGraph.build(line()), model(edgesJson(e3, e2, e1), line()));
		for (int i = 0; i < 3; i++)
		{
			for (int j = 0; j < 3; j++)
			{
				assertEquals(x.cost(i, j), y.cost(i, j));
				assertEquals(x.route(i, j), y.route(i, j));
			}
		}
		// Equal-cost duplicate edges resolve to the lowest id.
		assertEquals(Collections.singletonList("a"), x.route(0, 2));
	}

	// ---- brute-force cross-check ----

	/** One big Floyd-Warshall over src/sink copies of every node plus hubs and virtual vertices. */
	private static int[][] bruteForce(List<TravelNode> nodes, TravelContext ctx, ChunkGraph graph, TransportModel tm)
	{
		final int inf = TravelConfig.INF;
		WalkModel walk = new WalkModel(graph.accessible(ctx.getAccessibleRegions()));
		List<UsableEdge> edges = tm.usable(ctx.getState(), ctx.getAcquiredEdgeIds(), ctx.isIncludeAcquirable());
		int n = nodes.size();
		List<Tile> hubs = new ArrayList<>();
		for (UsableEdge u : edges)
		{
			TransportEdge e = u.getEdge();
			for (Tile x : new Tile[]{e.getFrom(), e.getTo()})
			{
				if (x != null && !hubs.contains(x))
				{
					hubs.add(x);
				}
			}
		}
		int h = hubs.size();
		List<String> kinds = new ArrayList<>(TravelConfig.NETWORK_KINDS);
		Collections.sort(kinds);
		// layout: src 0..n-1, sink n..2n-1, hubs 2n.., kinds, anywhere
		int hubBase = 2 * n;
		int kindBase = hubBase + h;
		int any = kindBase + kinds.size();
		int v = any + 1;
		int[][] d = new int[v][v];
		for (int[] row : d)
		{
			Arrays.fill(row, inf);
		}
		for (int a = 0; a < v; a++)
		{
			d[a][a] = 0;
		}
		for (int i = 0; i < n; i++)
		{
			Tile ti = nodes.get(i).getTile();
			boolean ok = ti != null && ti.isKnown()
				&& ctx.getAccessibleRegions().contains(TileMath.regionId(ti.getX(), ti.getY()));
			if (!ok)
			{
				continue;
			}
			for (int j = 0; j < n; j++)
			{
				Tile tj = nodes.get(j).getTile();
				boolean okj = tj != null && tj.isKnown()
					&& ctx.getAccessibleRegions().contains(TileMath.regionId(tj.getX(), tj.getY()));
				if (i != j && okj)
				{
					d[i][n + j] = Math.min(d[i][n + j], walk.ticks(ti, tj));
				}
			}
			for (int k = 0; k < h; k++)
			{
				d[i][hubBase + k] = Math.min(d[i][hubBase + k], walk.ticks(ti, hubs.get(k)));
				d[hubBase + k][n + i] = Math.min(d[hubBase + k][n + i], walk.ticks(hubs.get(k), ti));
			}
			d[i][any] = 0;
		}
		for (int a = 0; a < h; a++)
		{
			for (int b = 0; b < h; b++)
			{
				if (a != b)
				{
					d[hubBase + a][hubBase + b] = Math.min(d[hubBase + a][hubBase + b], walk.ticks(hubs.get(a), hubs.get(b)));
				}
			}
			d[hubBase + a][any] = 0;
		}
		for (int k = 0; k < kinds.size(); k++)
		{
			d[kindBase + k][any] = 0;
		}
		for (UsableEdge u : edges)
		{
			TransportEdge e = u.getEdge();
			int to = hubBase + hubs.indexOf(e.getTo());
			int from = e.getFrom() == null ? -1 : hubBase + hubs.indexOf(e.getFrom());
			int kind = kinds.indexOf(e.getKind());
			int w = u.getTicks();
			if (kind >= 0)
			{
				d[to][kindBase + kind] = 0;
				if (from >= 0)
				{
					d[from][kindBase + kind] = 0;
				}
				d[kindBase + kind][to] = Math.min(d[kindBase + kind][to], w);
			}
			else if (from < 0)
			{
				d[any][to] = Math.min(d[any][to], w);
			}
			else
			{
				d[from][to] = Math.min(d[from][to], w);
			}
		}
		for (int k = 0; k < v; k++)
		{
			for (int a = 0; a < v; a++)
			{
				for (int b = 0; b < v; b++)
				{
					if (d[a][k] < inf && d[k][b] < inf && d[a][k] + d[k][b] < d[a][b])
					{
						d[a][b] = d[a][k] + d[k][b];
					}
				}
			}
		}
		int[][] out = new int[n][n];
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++)
			{
				out[i][j] = i == j ? 0 : d[i][n + j];
			}
		}
		return out;
	}

	@Test
	void matchesBruteForceFloydWarshallOnRandomWorlds()
	{
		String[] kinds = {"SPELL", "FAIRY_RING", "SPIRIT_TREE", "GLIDER", "TRAPDOOR", "BOAT"};
		for (long seed = 1; seed <= 25; seed++)
		{
			Random rnd = new Random(seed);
			int side = 4;
			List<Integer> regions = new ArrayList<>();
			List<ChunkNode> chunks = new ArrayList<>();
			for (int rx = 50; rx < 50 + side; rx++)
			{
				for (int ry = 50; ry < 50 + side; ry++)
				{
					regions.add((rx << 8) | ry);
				}
			}
			for (int r : regions)
			{
				List<Integer> nb = new ArrayList<>();
				for (int o : regions)
				{
					if (o != r && Math.abs((o >> 8) - (r >> 8)) <= 1 && Math.abs((o & 255) - (r & 255)) <= 1 && rnd.nextInt(3) > 0)
					{
						nb.add(o);
					}
				}
				chunks.add(new ChunkNode(Arrays.asList(r), nb, false, Collections.emptyList()));
			}
			PlayerState.Builder sb = PlayerState.builder();
			for (int r : regions)
			{
				if (rnd.nextInt(4) > 0)
				{
					sb.chunk(r);
				}
			}
			PlayerState state = sb.build();
			List<String> es = new ArrayList<>();
			int ne = 2 + rnd.nextInt(7);
			for (int k = 0; k < ne; k++)
			{
				String from = rnd.nextBoolean() ? "null" : tileJson(3200 + rnd.nextInt(256), 3200 + rnd.nextInt(256));
				es.add(edgeJson("e" + k, kinds[rnd.nextInt(kinds.length)], from,
					tileJson(3200 + rnd.nextInt(256), 3200 + rnd.nextInt(256)), rnd.nextInt(40)));
			}
			TransportModel tm = model(edgesJson(es.toArray(new String[0])), chunks);
			List<TravelNode> nodes = new ArrayList<>();
			int nn = 3 + rnd.nextInt(5);
			for (int k = 0; k < nn; k++)
			{
				nodes.add(new TravelNode("n" + k, rnd.nextInt(10) == 0 ? null : t(3200 + rnd.nextInt(256), 3200 + rnd.nextInt(256))));
			}
			TravelContext ctx = new TravelContext(state, null, false);
			ChunkGraph graph = ChunkGraph.build(chunks);
			CostMatrix m = CostMatrix.build(nodes, ctx, graph, tm);
			int[][] expect = bruteForce(nodes, ctx, graph, tm);
			assertAllInRange(m);
			for (int i = 0; i < nn; i++)
			{
				for (int j = 0; j < nn; j++)
				{
					assertEquals(expect[i][j], m.cost(i, j), "seed " + seed + " cost(" + i + "," + j + ")");
					if (m.cost(i, j) >= TravelConfig.INF || i == j)
					{
						assertTrue(m.route(i, j).isEmpty());
					}
				}
			}
		}
	}
}
