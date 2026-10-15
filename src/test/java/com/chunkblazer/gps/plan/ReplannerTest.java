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
package com.chunkblazer.gps.plan;

import static com.chunkblazer.gps.plan.PlanWorld.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.model.PlayerState;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReplannerTest
{
	private static List<String> labelsOf(Plan p)
	{
		return p.getSteps().stream().map(PlanStep::getLabel).collect(Collectors.toList());
	}

	@Test
	void completedTaskDisappearsAndStartMoves()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850, PlanWorld.region(3300, 3200)).task("a", 1, 3210, 3200).task("b", 1, 3300, 3200).start(3200, 3200).build();
		Plan p1 = new Planner(w.request()).plan();
		String firstId = p1.getSteps().get(0).getNodeId();
		String first = p1.getSteps().get(0).getLabel();
		Plan p2 = Replanner.replan(p1, w.request(), w.request().getState(), p1.getSteps().get(0).getLocation(), Collections.singleton(firstId));
		assertTrue(p2.getSteps().stream().noneMatch(s -> s.getLabel().equals(first)));
		assertEquals(Collections.emptyList(), p2.getUnplannable());
		assertEquals(w.walkTicks(p1.getSteps().get(0).getLocation(), p2.getSteps().get(0).getLocation()), p2.getSteps().get(0).getTravelTicks());
	}

	@Test
	void partiallyDoneQuestResumesAtNextStep()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).quest("Q", step("s0", 3201, 3200), step("s1", 3202, 3200), step("s2", 3203, 3200)).questTask("Q", 1, 12850).start(3200, 3200).build();
		Plan p = Replanner.replan(null, w.request(), w.request().getState(), w.tile(3201, 3200), Collections.singleton("quest:Q:0"));
		List<String> labels = labelsOf(p);
		assertEquals(2, labels.size());
		assertTrue(labels.get(0).contains("s1") && labels.get(1).contains("s2"));
	}

	@Test
	void levelUpFromStateChangeRemovesTrainingStep()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220).start(3200, 3200).build();
		Plan before = new Planner(w.request()).plan();
		assertTrue(before.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING));
		PlayerState leveled = w.request().getState().withXp("COOKING", 14000);
		Plan after = Replanner.replan(before, w.request(), leveled, w.tile(3200, 3200), Collections.emptySet());
		assertTrue(after.getSteps().stream().noneMatch(s -> s.getType() == NodeType.TRAINING));
	}

	@Test
	void sameInputSamePlan()
	{
		List<String> a = labelsOf(new Planner(PlanWorld.random(42, 200).request()).plan());
		List<String> b = labelsOf(new Planner(PlanWorld.random(42, 200).request()).plan());
		assertEquals(a, b);
	}
}
