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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChunkNodesTest
{
	private static NuzlockeChunk.TravelConnection conn(int dest)
	{
		NuzlockeChunk.TravelConnection c = new NuzlockeChunk.TravelConnection();
		c.setDestinationId(dest);
		c.setTravelName("x");
		return c;
	}

	@Test
	void nullListsBecomeEmpty()
	{
		NuzlockeChunk chunk = new NuzlockeChunk();
		chunk.setRegionIds(Arrays.asList(1, 2));
		List<ChunkNode> nodes = ChunkNodes.from(Collections.singletonList(chunk));
		assertEquals(1, nodes.size());
		ChunkNode n = nodes.get(0);
		assertEquals(Arrays.asList(1, 2), n.getRegionIds());
		assertTrue(n.getNeighborRegionIds().isEmpty());
		assertFalse(n.isCharter());
		assertTrue(n.getCharterDestinationIds().isEmpty());
	}

	@Test
	void charterAndConnectionsCopied()
	{
		NuzlockeChunk chunk = new NuzlockeChunk();
		chunk.setRegionIds(Arrays.asList(5));
		chunk.setNeighborIds(Arrays.asList(6));
		chunk.setChunkType("CHARTER");
		chunk.setTravelConnections(Arrays.asList(conn(9), conn(10)));
		ChunkNode n = ChunkNodes.from(Collections.singletonList(chunk)).get(0);
		assertTrue(n.isCharter());
		assertEquals(Arrays.asList(6), n.getNeighborRegionIds());
		assertEquals(Arrays.asList(9, 10), n.getCharterDestinationIds());
	}

	@Test
	void nullChunkListAndNullRegionsHandled()
	{
		assertTrue(ChunkNodes.from(null).isEmpty());
		assertTrue(ChunkNodes.from(Collections.singletonList(new NuzlockeChunk())).get(0).getRegionIds().isEmpty());
	}
}
