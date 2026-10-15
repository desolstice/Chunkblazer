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

import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CostMatrixPerfTest
{
	@Test
	void fiveHundredNodesWithTwoHundredHubsBuildsQuickly()
	{
		SyntheticWorld w = SyntheticWorld.create(new Random(42), 60, 200, 500);
		long t0 = System.nanoTime();
		CostMatrix m = CostMatrix.build(w.nodes, w.ctx, w.graph, w.transport);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertEquals(500, m.size());
		assertTrue(ms < 3000, "matrix took " + ms + " ms");
	}

	@Test
	void sameInputGivesIdenticalMatrix()
	{
		SyntheticWorld w1 = SyntheticWorld.create(new Random(7), 20, 50, 100);
		SyntheticWorld w2 = SyntheticWorld.create(new Random(7), 20, 50, 100);
		CostMatrix a = CostMatrix.build(w1.nodes, w1.ctx, w1.graph, w1.transport);
		CostMatrix b = CostMatrix.build(w2.nodes, w2.ctx, w2.graph, w2.transport);
		for (int i = 0; i < a.size(); i++)
		{
			for (int j = 0; j < a.size(); j++)
			{
				assertEquals(a.cost(i, j), b.cost(i, j));
			}
		}
	}
}
