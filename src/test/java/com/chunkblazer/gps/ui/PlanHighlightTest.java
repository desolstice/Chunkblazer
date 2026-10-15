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
package com.chunkblazer.gps.ui;

import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.plan.NodeType;
import com.chunkblazer.gps.plan.PlanStep;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlanHighlightTest
{
	private static PlanStep step(Tile tile)
	{
		return PlanStep.builder()
			.order(1)
			.nodeId("n")
			.type(NodeType.TASK)
			.label("x")
			.location(tile)
			.reason("")
			.missingRequirements(Collections.emptyList())
			.viaTransportEdgeIds(Collections.emptyList())
			.build();
	}

	@Test
	void setClearAndRead()
	{
		PlanHighlight h = new PlanHighlight();
		assertFalse(h.regionId().isPresent());
		assertNull(h.label());
		h.set(12850, "Cook's Assistant 1/3");
		assertEquals(12850, h.regionId().getAsInt());
		assertEquals("Cook's Assistant 1/3", h.label());
		h.clear();
		assertFalse(h.regionId().isPresent());
		assertNull(h.label());
	}

	@Test
	void snapshotIsNullWhenClearedAndConsistentWhenSet()
	{
		PlanHighlight h = new PlanHighlight();
		assertNull(h.snapshot());
		h.set(5, "x");
		PlanHighlight.State s = h.snapshot();
		h.clear();
		assertEquals(5, s.getRegionId());
		assertEquals("x", s.getLabel());
	}

	@Test
	void regionOfStepUsesTile()
	{
		PlanStep s = step(new Tile(3222, 3218, 0, false, null));
		assertEquals(12850, PlanHighlight.regionOf(s).getAsInt());
	}

	@Test
	void unknownTileHasNoRegion()
	{
		assertFalse(PlanHighlight.regionOf(step(new Tile(null, null, 0, true, "NONE"))).isPresent());
		assertFalse(PlanHighlight.regionOf(step(null)).isPresent());
		assertFalse(PlanHighlight.regionOf(null).isPresent());
	}
}
