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
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Test-only seeded synthetic world: a square grid of single-region chunks, SPELL anywhere-edges and random nodes. */
final class SyntheticWorld
{
	private static final int ORIGIN = 10;

	final List<TravelNode> nodes;
	final TravelContext ctx;
	final ChunkGraph graph;
	final TransportModel transport;

	private SyntheticWorld(List<TravelNode> nodes, TravelContext ctx, ChunkGraph graph, TransportModel transport)
	{
		this.nodes = nodes;
		this.ctx = ctx;
		this.graph = graph;
		this.transport = transport;
	}

	private static int rid(int gx, int gy)
	{
		return ((ORIGIN + gx) << 8) | (ORIGIN + gy);
	}

	private static Tile randomTile(Random r, int gridSize)
	{
		int gx = r.nextInt(gridSize);
		int gy = r.nextInt(gridSize);
		return new Tile((ORIGIN + gx) * 64 + r.nextInt(64), (ORIGIN + gy) * 64 + r.nextInt(64), 0, false, null);
	}

	static SyntheticWorld create(Random r, int gridSize, int edgeCount, int nodeCount)
	{
		List<ChunkNode> chunks = new ArrayList<>();
		PlayerState.Builder b = PlayerState.builder();
		for (int gx = 0; gx < gridSize; gx++)
		{
			for (int gy = 0; gy < gridSize; gy++)
			{
				List<Integer> nb = new ArrayList<>();
				if (gx > 0)
				{
					nb.add(rid(gx - 1, gy));
				}
				if (gx < gridSize - 1)
				{
					nb.add(rid(gx + 1, gy));
				}
				if (gy > 0)
				{
					nb.add(rid(gx, gy - 1));
				}
				if (gy < gridSize - 1)
				{
					nb.add(rid(gx, gy + 1));
				}
				chunks.add(new ChunkNode(Collections.singletonList(rid(gx, gy)), nb, false, Collections.<Integer>emptyList()));
				b.chunk(rid(gx, gy));
			}
		}
		StringBuilder edges = new StringBuilder("{\"schema\":1,\"edges\":[");
		for (int i = 0; i < edgeCount; i++)
		{
			Tile to = randomTile(r, gridSize);
			if (i > 0)
			{
				edges.append(',');
			}
			edges.append("{\"id\":\"e").append(i).append("\",\"kind\":\"SPELL\",\"name\":\"e").append(i)
				.append("\",\"from\":null,\"to\":{\"x\":").append(to.getX()).append(",\"y\":").append(to.getY())
				.append(",\"plane\":0},\"ticks\":").append(3 + r.nextInt(10))
				.append(",\"requires\":{\"skills\":{},\"quests\":[],\"items\":[]},\"consumesItem\":false}");
		}
		edges.append("]}");
		List<TravelNode> nodes = new ArrayList<>();
		for (int i = 0; i < nodeCount; i++)
		{
			nodes.add(new TravelNode("n" + i, randomTile(r, gridSize)));
		}
		GpsData d = GpsTestFixtures.dataWith(edges.toString(), "{\"schema\":1,\"items\":[]}", "{\"schema\":1,\"entries\":[]}");
		TransportModel tm = new TransportModel(d, chunks, QuestCatalog.build(d, Collections.emptyMap()));
		return new SyntheticWorld(nodes, new TravelContext(b.build(), Collections.<String>emptySet(), false), ChunkGraph.build(chunks), tm);
	}
}
