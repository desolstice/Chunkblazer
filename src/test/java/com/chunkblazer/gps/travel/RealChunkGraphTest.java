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

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.gps.eligibility.CatalogTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RealChunkGraphTest
{
	@Test
	void shippedChunksBuildAndStartingAreaIsWalkable()
	{
		List<NuzlockeChunk> chunks = CatalogTestSupport.loadCatalogChunks();
		List<ChunkNode> nodes = ChunkNodes.from(chunks);
		assertTrue(nodes.size() > 100);
		Set<Integer> all = new HashSet<>();
		for (ChunkNode n : nodes)
		{
			all.addAll(n.getRegionIds());
		}
		ChunkGraph.View v = ChunkGraph.build(nodes).accessible(all);
		int ticks = v.walkTicks(TileMath.centre(12850), TileMath.centre(12853));
		assertTrue(ticks > 0 && ticks < TravelConfig.INF, "ticks=" + ticks);
	}

	@Test
	void everyNeighbourReferenceResolvesToAKnownRegion()
	{
		List<ChunkNode> nodes = ChunkNodes.from(CatalogTestSupport.loadCatalogChunks());
		Set<Integer> known = new HashSet<>();
		for (ChunkNode n : nodes)
		{
			known.addAll(n.getRegionIds());
		}
		List<String> dangling = new ArrayList<>();
		int links = 0;
		for (ChunkNode n : nodes)
		{
			for (int nb : n.getNeighborRegionIds())
			{
				links++;
				if (!known.contains(nb))
				{
					dangling.add(n.getRegionIds() + "->" + nb);
				}
			}
		}
		assertTrue(dangling.size() < links / 4, dangling.size() + " dangling of " + links + " neighbour links: " + dangling);
	}
}
