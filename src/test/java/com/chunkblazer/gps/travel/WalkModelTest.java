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
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WalkModelTest
{
	private WalkModel model()
	{
		ChunkGraph g = ChunkGraph.build(Arrays.asList(new ChunkNode(Arrays.asList(12850), Collections.emptyList(), false, Collections.emptyList())));
		return new WalkModel(g.accessible(new HashSet<>(Arrays.asList(12850))));
	}

	@Test
	void planeChangeAddsConstant()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		Tile up = new Tile(3200, 3200, 1, false, null);
		assertEquals(TravelConfig.PLANE_CHANGE_TICKS, model().ticks(a, up));
	}

	@Test
	void unknownOrNullTileIsInfinite()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		assertEquals(TravelConfig.INF, model().ticks(a, new Tile(null, null, 0, true, "NONE")));
		assertEquals(TravelConfig.INF, model().ticks(null, a));
		assertEquals(TravelConfig.INF, model().ticks(a, null));
	}

	@Test
	void sameTileIsZero()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		assertEquals(0, model().ticks(a, a));
	}

	@Test
	void lockedRegionStaysInfiniteEvenWithPlaneChange()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		Tile far = new Tile(50 * 64 + 40, 51 * 64 + 40, 1, false, null);
		assertEquals(TravelConfig.INF, model().ticks(a, far));
	}
}
