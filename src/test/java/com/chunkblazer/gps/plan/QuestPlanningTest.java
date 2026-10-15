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

import static com.chunkblazer.gps.plan.PlanWorld.region;
import static com.chunkblazer.gps.plan.PlanWorld.requiresQuest;
import static com.chunkblazer.gps.plan.PlanWorld.step;
import static com.chunkblazer.gps.plan.PlanWorld.stepNoLocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class QuestPlanningTest
{
	private static final int R50 = region(3200, 3200);
	private static final int R51 = region(3300, 3200);

	private static List<String> labelsOf(Plan p)
	{
		return p.getSteps().stream().map(PlanStep::getLabel).collect(Collectors.toList());
	}

	private static int indexContaining(List<String> labels, String text)
	{
		for (int i = 0; i < labels.size(); i++)
		{
			if (labels.get(i).contains(text))
			{
				return i;
			}
		}
		return -1;
	}

	@Test
	void questStepsInterleaveWithNearbyTaskInFullPlan()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50, R51)
			.quest("Q", step("go A", 3200, 3200), step("go C", 3300, 3200))
			.questTask("Q", 5, R50)
			.task("tee", 1, 3250, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> labels = labelsOf(p);
		int i1 = indexContaining(labels, "go A");
		int i2 = indexContaining(labels, "go C");
		int it = indexContaining(labels, "tee");
		assertTrue(i1 >= 0 && i1 < it && it < i2, labels.toString());
	}

	@Test
	void questXpFeedsLaterRequirementAndReplacesTraining()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", 15000, "COOKING", step("do quest", 3210, 3210))
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Far grind", 1, 99, 30000, 3200 + 600, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> labels = labelsOf(p);
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getLabel().contains("do quest")), labels.toString());
		assertTrue(p.getSteps().stream().noneMatch(s -> s.getType() == NodeType.TRAINING), labels.toString());
		assertTrue(indexContaining(labels, "do quest") < indexContaining(labels, "cook"), labels.toString());
		PlanStep quest = p.getSteps().get(indexContaining(labels, "do quest"));
		assertTrue(quest.getReason().contains("Quest step 1/1: Q"), quest.getReason());
		assertTrue(quest.getReason().contains("Cooking 15000 xp"), quest.getReason());
		assertTrue(quest.getReason().contains("Needed for: cook"), quest.getReason());
	}

	@Test
	void choiceXpCountsOnceAndIsAppliedInTheProjectedState()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.questWithChoice("Lamp", 15000, "COOKING", step("claim lamp", 3210, 3210))
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Far grind", 1, 99, 30000, 3200 + 600, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getLabel().contains("claim lamp")), labelsOf(p).toString());
		assertTrue(p.getSteps().stream().noneMatch(s -> s.getType() == NodeType.TRAINING));
		assertTrue(p.getUnplannable().isEmpty(), p.getUnplannable().toString());
		assertReplayHolds(w, p);
	}

	@Test
	void trainingUsedWhenNoQuestCoversShortfall()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING));
	}

	@Test
	void trainingBeatsAFarAwayQuestThatCoversTheShortfall()
	{
		// the quest chain is ~250 ticks away; training is next to the player: 1 tick + the 200 tick grind penalty
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", 15000, "COOKING", step("far quest", 3700, 3200))
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3202, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING), labelsOf(p).toString());
		assertEquals(-1, indexContaining(labelsOf(p), "far quest"));
	}

	@Test
	void prerequisiteQuestIsPlannedBeforeDependentQuest()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("A", step("A step", 3205, 3205))
			.quest("B", requiresQuest("A"), step("B step", 3206, 3206))
			.questTask("B", 3, R50)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> l = labelsOf(p);
		assertTrue(indexContaining(l, "A step") >= 0, l.toString());
		assertTrue(indexContaining(l, "A step") < indexContaining(l, "B step"), l.toString());
	}

	@Test
	void unknownLocationStepsAreFlaggedApproximate()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", stepNoLocation("somewhere"), step("known", 3205, 3205))
			.questTask("Q", 2, R50)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(2, p.getSteps().size());
		PlanStep first = p.getSteps().get(0);
		assertTrue(first.getLabel().contains("somewhere"));
		assertTrue(first.isApproximate());
		assertNotNull(first.getLocation());
		assertEquals(0, first.getTravelTicks());
		assertTrue(!p.getSteps().get(1).isApproximate());
	}

	@Test
	void emptyWorldGivesEmptyPlan()
	{
		Plan p = new Planner(PlanWorld.builder().unlocked(R50).start(3200, 3200).build().request()).plan();
		assertTrue(p.getSteps().isEmpty());
		assertEquals(0, p.getTotalTravelTicks());
	}

	@Test
	void replayVerifiesRequirementsHoldAlongTheOrder()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(2, p.getSteps().size());
		assertReplayHolds(w, p);
	}

	@Test
	void questCheckTaskWithAllStepsDoneIsTreatedAsCompleted()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", step("one", 3205, 3205), step("two", 3206, 3206))
			.questTask("Q", 5, R50)
			.start(3200, 3200).build();
		java.util.Set<String> done = new java.util.HashSet<>(Arrays.asList("quest:Q:0", "quest:Q:1"));
		Plan p = new Planner(w.request(r -> r.doneNodeIds(done))).plan();
		assertTrue(p.getSteps().isEmpty(), labelsOf(p).toString());
		assertTrue(p.getUnplannable().isEmpty(), p.getUnplannable().toString());
	}

	private static void assertReplayHolds(PlanWorld w, Plan p)
	{
		PlayerState s = w.request().getState();
		for (PlanStep step : p.getSteps())
		{
			PlanNode n = w.nodeFor(step);
			assertNotNull(n, step.getLabel());
			assertTrue(n.getRequirement().missing(s).isEmpty(), step.getLabel());
			s = n.getEffects().apply(s);
		}
	}

	@Test
	void nodesAdjacentAcrossARegionBorderAreNotDeferredOrForcedApart()
	{
		// All on the region-centre row y=3232. a/b sit one tile apart on either side of the x=3264 region border; if the
		// a->b leg were priced through the region centres (~127 ticks) the solver would prefer d,a,c,b style orders or
		// pay for the detour. The correct order d,a,b,c walks ~156 tiles; any other order is strictly longer.
		PlanWorld w = PlanWorld.builder().unlocked(R50, R51)
			.task("d", 1, 3200, 3232)
			.task("a", 1, 3263, 3232)
			.task("b", 1, 3264, 3232)
			.task("c", 1, 3320, 3232)
			.start(3232, 3232).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(Arrays.asList("d", "a", "b", "c"), labelsOf(p), p.getSteps().stream().map(s -> s.getLabel() + ":" + s.getTravelTicks()).collect(Collectors.toList()).toString());
		assertTrue(p.getUnplannable().isEmpty());
		PlanStep b = p.getSteps().get(2);
		assertTrue(b.getTravelTicks() <= 6, "a->b crossing the border must cost about one tile, was " + b.getTravelTicks());
	}

	@Test
	void stepsCarryStableNodeIds()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50).task("x1", 1, 3210, 3200).task("x2", 1, 3212, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(2, p.getSteps().size());
		assertNotNull(p.getSteps().get(0).getNodeId());
		assertTrue(!p.getSteps().get(0).getNodeId().equals(p.getSteps().get(1).getNodeId()));
		for (PlanStep s : p.getSteps())
		{
			assertNotNull(w.nodeFor(s), s.getLabel());
		}
	}

	@Test
	void providersOfADroppedGoalAreNotLeftInThePlan()
	{
		// Q's second step is in a locked region, so Q's chain and the cook task that depended on it are unplannable;
		// the reachable first step of Q is then an orphan provider and must not be planned.
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", 15000, "COOKING", step("near", 3205, 3205), step("locked away", 3300, 3200))
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().isEmpty(), labelsOf(p).toString());
		assertTrue(p.getUnplannable().stream().anyMatch(u -> u.getLabel().contains("locked away") && u.getId() != null),
			p.getUnplannable().toString());
	}

	@Test
	void questChainWithoutKnownLocationsDoesNotBeatTraining()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", 15000, "COOKING", stepNoLocation("talk"), stepNoLocation("talk more"))
			.task("cook", "COOKING", 30, 5, 3215, 3215)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3205, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING), labelsOf(p).toString());
		assertTrue(p.getSteps().stream().noneMatch(s -> s.getType() == NodeType.QUEST_STEP), labelsOf(p).toString());
	}

	@Test
	void unroutableLegsAreClampedSoTheyCannotSaturateTheTotal()
	{
		assertEquals(0, Planner.clampLeg(com.chunkblazer.gps.travel.TravelConfig.INF));
		assertEquals(0, Planner.clampLeg(Integer.MAX_VALUE));
		assertEquals(7, Planner.clampLeg(7));
	}

	@Test
	void unreachableNodesAndTheirDependantsAreReportedNotPlanned()
	{
		// step 2 of Q lies in a region that is not unlocked; B needs Q, so B's step is blocked as well
		PlanWorld w = PlanWorld.builder().unlocked(R50)
			.quest("Q", step("near", 3205, 3205), step("locked away", 3300, 3200))
			.quest("B", requiresQuest("Q"), step("B step", 3206, 3206))
			.questTask("Q", 2, R50)
			.questTask("B", 2, R50)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> labels = labelsOf(p);
		assertEquals(Arrays.asList("Q: near"), labels);
		Plan.Unplannable locked = p.getUnplannable().stream().filter(u -> u.getLabel().contains("locked away"))
			.findFirst().orElseThrow(AssertionError::new);
		assertEquals(Missing.Kind.CHUNK, locked.getMissing().get(0).getKind());
		assertTrue(locked.getMissing().get(0).getText().contains("unreachable"));
		assertTrue(p.getUnplannable().stream().anyMatch(u -> u.getLabel().contains("B step")),
			p.getUnplannable().toString());
	}

	@Test
	void sameRequestGivesTheSamePlan()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50, R51).deadlineMillis(20_000)
			.quest("Q", step("go A", 3200, 3200), step("go C", 3300, 3200))
			.questTask("Q", 5, R50)
			.task("t1", 1, 3250, 3200).task("t2", 1, 3280, 3210).task("t3", 1, 3205, 3230)
			.start(3200, 3200).build();
		Plan a = new Planner(w.request()).plan();
		Plan b = new Planner(w.request()).plan();
		assertEquals(labelsOf(a), labelsOf(b));
		assertEquals(a.getTotalTravelTicks(), b.getTotalTravelTicks());
	}

	@Test
	void totalTravelIsTheSumOfStepTravel()
	{
		PlanWorld w = PlanWorld.builder().unlocked(R50, R51)
			.task("t1", 1, 3250, 3200).task("t2", 1, 3280, 3210)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(p.getSteps().stream().mapToInt(PlanStep::getTravelTicks).sum(), p.getTotalTravelTicks());
		assertTrue(p.getTotalTravelTicks() > 0);
		assertEquals(Arrays.asList(0, 1), p.getSteps().stream().map(PlanStep::getOrder).collect(Collectors.toList()));
	}
}
