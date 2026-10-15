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

import com.chunkblazer.gps.data.GpsDataStatus;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.plan.NodeType;
import com.chunkblazer.gps.plan.Plan;
import com.chunkblazer.gps.plan.PlanStep;
import com.chunkblazer.gps.travel.TravelConfig;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PlanViewModelTest
{
	private static PlanStep step(int order, NodeType t, String label, int ticks, String questId, Integer idx, boolean approx)
	{
		return PlanStep.builder().order(order).nodeId("n" + order).type(t).label(label)
			.location(new Tile(3200, 3200, 0, approx, null)).approximate(approx).travelTicks(ticks)
			.reason("reason").missingRequirements(Collections.emptyList()).questId(questId).stepIndex(idx)
			.viaTransportEdgeIds(Collections.emptyList()).build();
	}

	@Test
	void formatTicks()
	{
		assertEquals("0s", PlanViewModel.formatTicks(0));
		assertEquals("12s", PlanViewModel.formatTicks(20));      // 20 * 0.6
		assertEquals("1m 05s", PlanViewModel.formatTicks(108));   // 64.8 -> 65s
		assertEquals("1h 02m", PlanViewModel.formatTicks(6200));  // 3720s
		assertEquals("unreachable", PlanViewModel.formatTicks(TravelConfig.INF));
	}

	@Test
	void interleavedQuestStepsShareABadgeWithProgress()
	{
		Plan p = new Plan(Arrays.asList(
			step(1, NodeType.QUEST_STEP, "Talk to Cook", 10, "COOKS_ASSISTANT", 0, false),
			step(2, NodeType.TASK, "Cook shrimp", 5, null, null, false),
			step(3, NodeType.QUEST_STEP, "Hand in egg", 8, "COOKS_ASSISTANT", 1, false)), 23, Collections.emptyList(), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(3, v.getRows().size());
		assertEquals(v.getRows().get(0).getBadge(), v.getRows().get(2).getBadge());
		assertTrue(v.getRows().get(0).getBadge().contains("2"));
		assertEquals("Cooks Assistant 2/2", v.getRows().get(0).getBadge());
		assertNull(v.getRows().get(1).getBadge());
		assertEquals(PlanViewModel.Kind.QUEST_STEP, v.getRows().get(0).getKind());
		assertEquals(PlanViewModel.Kind.STEP, v.getRows().get(1).getKind());
	}

	@Test
	void subtitleShowsReasonAndApproximateSuffix()
	{
		Plan p = new Plan(Arrays.asList(step(1, NodeType.TASK, "x", 5, null, null, true)), 5, Collections.emptyList(), "v1");
		PlanViewModel.Row r = PlanViewModel.build(p, null, "v1").getRows().get(0);
		assertTrue(r.isApproximate());
		assertTrue(r.getSubtitle().startsWith("reason"));
		assertTrue(r.getSubtitle().contains("approximate"));
		Plan q = new Plan(Arrays.asList(step(1, NodeType.TASK, "x", 5, null, null, false)), 5, Collections.emptyList(), "v1");
		assertEquals("reason", PlanViewModel.build(q, null, "v1").getRows().get(0).getSubtitle());
	}

	@Test
	void kindsAndHeader()
	{
		Plan p = new Plan(Arrays.asList(
			step(1, NodeType.TRAINING, "t", 0, null, null, false),
			step(2, NodeType.ACQUIRE_ITEM, "i", 20, null, null, false),
			step(3, NodeType.ACQUIRE_TELEPORT, "p", 20, null, null, false)), 40, Collections.emptyList(), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(PlanViewModel.Kind.TRAINING, v.getRows().get(0).getKind());
		assertEquals(PlanViewModel.Kind.ACQUIRE, v.getRows().get(1).getKind());
		assertEquals(PlanViewModel.Kind.ACQUIRE, v.getRows().get(2).getKind());
		assertEquals("3 steps, travel ~24s", v.getHeader());
		assertEquals("GPS data v1", v.getFooter());
		assertEquals("12s", v.getRows().get(1).getTravelText());
	}

	@Test
	void emptyPlanExplainsItself()
	{
		PlanViewModel.View v = PlanViewModel.build(new Plan(Collections.emptyList(), 0, Collections.emptyList(), "v1"), null, "v1");
		assertEquals(1, v.getRows().size());
		assertEquals(PlanViewModel.Kind.INFO, v.getRows().get(0).getKind());
		assertFalse(v.getRows().get(0).getTitle().isEmpty());
	}

	@Test
	void onlyUnplannableShowsInfoThenBlockedRows()
	{
		Plan p = new Plan(Collections.emptyList(), 0,
			Arrays.asList(new Plan.Unplannable("Task A", Arrays.asList(
				new Missing(Missing.Kind.SKILL, "Cooking 30 (you have 1)"),
				new Missing(Missing.Kind.QUEST, "Quest: Foo")))), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(PlanViewModel.Kind.INFO, v.getRows().get(0).getKind());
		assertEquals(PlanViewModel.Kind.UNPLANNABLE, v.getRows().get(1).getKind());
		assertTrue(v.getRows().get(1).isUnplannable());
		assertTrue(v.getRows().get(1).getSubtitle().contains("Cooking 30"));
		assertTrue(v.getRows().get(1).getSubtitle().contains("Quest: Foo"));
	}

	@Test
	void unplannableAfterStepsHasNoInfoRow()
	{
		Plan p = new Plan(Arrays.asList(step(1, NodeType.TASK, "x", 5, null, null, false)), 5,
			Arrays.asList(new Plan.Unplannable("Task A", Collections.emptyList())), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(2, v.getRows().size());
		assertEquals(PlanViewModel.Kind.STEP, v.getRows().get(0).getKind());
		assertEquals(PlanViewModel.Kind.UNPLANNABLE, v.getRows().get(1).getKind());
	}

	@Test
	void footerShowsStatusWhenDataMissing()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), false, new ArrayList<>(), new ArrayList<>(), null);
		assertTrue(PlanViewModel.build(new Plan(Collections.emptyList(), 0, Collections.emptyList(), null), st, null).getFooter().contains("not found"));
	}
}
