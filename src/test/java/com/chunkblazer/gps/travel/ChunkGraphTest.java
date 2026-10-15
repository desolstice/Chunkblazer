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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChunkGraphTest
{
	private static ChunkNode node(List<Integer> regions, List<Integer> neighbors)
	{
		return new ChunkNode(regions, neighbors, false, Collections.emptyList());
	}

	private ChunkGraph graph()
	{
		return ChunkGraph.build(Arrays.asList(
			node(Arrays.asList(12850), Arrays.asList(12851, 13106)),
			node(Arrays.asList(12851), Arrays.asList(12850, 12852)),
			node(Arrays.asList(12852), Arrays.asList(12851)),
			node(Arrays.asList(13106, 13362), Arrays.asList(12850)),
			node(Arrays.asList(20000), Collections.emptyList())));
	}

	private static Tile at(int rid)
	{
		return TileMath.centre(rid);
	}

	@Test
	void sameRegionWalkIsHalfTickPerTile()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850)));
		Tile a = new Tile(3200, 3200, 0, false, null);
		Tile b = new Tile(3220, 3205, 0, false, null);
		assertEquals(10, v.walkTicks(a, b));
		assertEquals(0, v.walkTicks(a, a));
	}

	@Test
	void adjacentChunksWalkWithDetour()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 12851)));
		assertEquals(40, v.walkTicks(at(12850), at(12851)));
	}

	@Test
	void lockedChunkIsInfinite()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(12851)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12851), at(12850)));
	}

	@Test
	void pathMustGoThroughUnlockedChunksOnly()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 12852)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(12852)));
		ChunkGraph.View all = graph().accessible(new HashSet<>(Arrays.asList(12850, 12851, 12852)));
		assertEquals(80, all.walkTicks(at(12850), at(12852)));
	}

	@Test
	void regionsOfOneChunkConnectAndNeighbourOnAnyRegionConnectsWholeChunk()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 13106, 13362)));
		assertTrue(v.walkTicks(at(13106), at(13362)) < TravelConfig.INF);
		assertTrue(v.walkTicks(at(12850), at(13362)) < TravelConfig.INF);
	}

	@Test
	void unknownRegionAndIslandHandled()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 20000, 99999)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(20000)));
		assertEquals(2, v.walkTicks(new Tile(78 * 64 + 1, 32 * 64 + 1, 0, false, null), new Tile(78 * 64 + 4, 32 * 64 + 1, 0, false, null)));
		assertTrue(v.isAccessible(99999));
		assertFalse(v.isAccessible(12851));
	}

	@Test
	void tileInLockedRegionIsInfiniteBothDirections()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 12852)));
		Tile locked = new Tile(50 * 64 + 40, 51 * 64 + 40, 0, false, null);
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), locked));
		assertEquals(TravelConfig.INF, v.walkTicks(locked, at(12850)));
		assertEquals(TravelConfig.INF, v.walkTicks(locked, locked));
	}

	@Test
	void accessibleRegionMissingFromGraphIsSingleton()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 99999)));
		Tile a = new Tile(TileMath.regionX(99999) * 64 + 1, TileMath.regionY(99999) * 64 + 1, 0, false, null);
		Tile b = new Tile(TileMath.regionX(99999) * 64 + 11, TileMath.regionY(99999) * 64 + 1, 0, false, null);
		assertEquals(5, v.walkTicks(a, b));
		assertEquals(TravelConfig.INF, v.walkTicks(a, at(12850)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), a));
	}

	@Test
	void unknownTileIsInfinite()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), new Tile(null, null, 0, false, null)));
	}
}
