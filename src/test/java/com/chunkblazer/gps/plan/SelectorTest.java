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

import static com.chunkblazer.gps.plan.SelectorTestSupport.map;
import static com.chunkblazer.gps.plan.SelectorTestSupport.node;
import static com.chunkblazer.gps.plan.SelectorTestSupport.unlocked;
import static org.junit.jupiter.api.Assertions.*;

import com.chunkblazer.gps.model.Missing;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SelectorTest
{
	private static String quest(String id, String requiredQuest, String extraReq)
	{
		return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"requirements\":{\"skills\":{},\"quests\":["
			+ (requiredQuest == null ? "" : "\"" + requiredQuest + "\"") + "],\"questPoints\":0,\"combatLevel\":0,\"items\":["
			+ (extraReq == null ? "" : extraReq) + "]},\"rewards\":{\"xp\":{},\"choiceXp\":[],\"questPoints\":1,\"unlocks\":[]},"
			+ "\"steps\":[{\"index\":0,\"text\":\"a\",\"kind\":\"OTHER\",\"location\":null,\"items\":[]},"
			+ "{\"index\":1,\"text\":\"b\",\"kind\":\"OTHER\",\"location\":null,\"items\":[]}]}";
	}

	private static String quests(String... defs)
	{
		return "{\"schema\":1,\"quests\":[" + String.join(",", defs) + "]}";
	}

	private static String item(int id, String slot, String shop, int price, String skills)
	{
		return "{\"id\":" + id + ",\"name\":\"Item" + id + "\",\"members\":false,\"slot\":" + (slot == null ? "null" : "\"" + slot + "\"")
			+ ",\"requirements\":{\"skills\":{" + skills + "},\"quests\":[]},\"sources\":[{\"type\":\"SHOP\",\"shop\":\"" + shop
			+ "\",\"price\":" + price + "}],\"teleport\":null}";
	}

	private static String items(String... defs)
	{
		return "{\"schema\":1,\"items\":[" + String.join(",", defs) + "]}";
	}

	private static final String SHOP_LOCATION = "{\"schema\":1,\"entries\":[{\"name\":\"S\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}";

	@Test
	void readyTaskSelectedWithNoProviders()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12850).state(unlocked(12850)).select();
		assertEquals(1, g.getNodes().size());
		assertTrue(g.getPreds().get("task:a").isEmpty());
	}

	@Test
	void missingSkillAddsTrainingProviderAndPrecedence()
	{
		SelectorTestSupport s = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).state(unlocked(12850))
			.withTrainingMethod("COOKING", "Fast", 1, 99, 50000);
		PrecedenceGraph g = s.select();
		assertEquals(2, g.getNodes().size());
		assertEquals(Collections.singletonList("train:COOKING:30"), g.getPreds().get("task:a"));
	}

	@Test
	void sharedTrainingProviderRaisedToHighestLevelNeeded()
	{
		SelectorTestSupport s = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).withTask("b", "COOKING", 50, 5, 12850)
			.state(unlocked(12850)).withTrainingMethod("COOKING", "Fast", 1, 99, 50000);
		PrecedenceGraph g = s.select();
		long trains = g.getNodes().stream().filter(n -> n.getType() == NodeType.TRAINING).count();
		assertEquals(1, trains);
		assertEquals("train:COOKING:50", g.getNodes().stream().filter(n -> n.getType() == NodeType.TRAINING).findFirst().get().getId());
		assertEquals(Collections.singletonList("train:COOKING:50"), g.getPreds().get("task:a"));
		assertEquals(Collections.singletonList("train:COOKING:50"), g.getPreds().get("task:b"));
	}

	@Test
	void noTrainingDataMakesTaskUnplannableWithMissingSkill()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(1, g.getUnplannable().size());
		assertEquals(Missing.Kind.SKILL, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void unknownRequirementIsUnplannableNotAssumedMet()
	{
		PrecedenceGraph g = new SelectorTestSupport().withRawTask("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\",\"base_points\":5}", 12850)
			.state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.UNKNOWN, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void lockedChunkTaskIsUnplannableWithChunkMissing()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12851).state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.CHUNK, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void completedTasksAreSkipped()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12850).completed("a").state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertTrue(g.getUnplannable().isEmpty());
	}

	@Test
	void maxNodesCapsSelectionKeepingBestScores()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).maxNodes(2);
		for (int i = 0; i < 5; i++)
		{
			s.withTask("t" + i, "COOKING", 1, i + 1, 12850);
		}
		PrecedenceGraph g = s.select();
		assertEquals(2, g.getNodes().size());
		assertTrue(g.getNodes().stream().anyMatch(n -> n.getId().equals("task:t4")));
		assertTrue(g.getNodes().stream().anyMatch(n -> n.getId().equals("task:t3")));
	}

	@Test
	void maxNodesCountsProvidersAndSkipsCandidatesThatDoNotFit()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).maxNodes(2)
			.withTrainingMethod("COOKING", "Fast", 1, 99, 50000)
			.withTask("big", "COOKING", 30, 10, 12850) // needs a training provider: 2 nodes
			.withTask("small", "FISHING", 1, 1, 12850);
		PrecedenceGraph g = s.select();
		assertEquals(2, g.getNodes().size());
		assertTrue(g.getNodes().stream().anyMatch(n -> n.getId().equals("train:COOKING:30")));
	}

	@Test
	void scoreDiscountsTasksNeedingProviders()
	{
		// 10 points with one provider scores 5; a ready 6 point task scores 6 and wins the single slot.
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).maxNodes(1)
			.withTrainingMethod("COOKING", "Fast", 1, 99, 50000)
			.withTask("needs", "COOKING", 30, 10, 12850)
			.withTask("ready", "FISHING", 1, 6, 12850);
		PrecedenceGraph g = s.select();
		assertEquals(1, g.getNodes().size());
		assertEquals("task:ready", g.getNodes().get(0).getId());
	}

	@Test
	void unlockNextChunkGoalStopsAtTargetPoints()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850).withTaskPoints(1)).goal(GoalMode.unlockNextChunk())
			.withLockedChunkCost(12851, 6);
		for (int i = 0; i < 6; i++)
		{
			s.withTask("t" + i, "COOKING", 1, 2, 12850);
		}
		PrecedenceGraph g = s.select();
		int pts = g.getNodes().stream().mapToInt(PlanNode::getPoints).sum();
		assertTrue(pts >= 5 && pts <= 6, "points " + pts); // need 6 - 1 = 5, tasks give 2 each -> 3 tasks = 6
	}

	@Test
	void unlockNextChunkGoalWithNoLockedChunkSelectsNothing()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).goal(GoalMode.unlockNextChunk())
			.withTask("a", "COOKING", 1, 2, 12850).select();
		assertTrue(g.getNodes().isEmpty());
	}

	@Test
	void unlockNextChunkGoalUsesCheapestLockedChunk()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).goal(GoalMode.unlockNextChunk())
			.withLockedChunkCost(12851, 9).withLockedChunkCost(12852, 3);
		for (int i = 0; i < 6; i++)
		{
			s.withTask("t" + i, "COOKING", 1, 2, 12850);
		}
		assertEquals(2, s.select().getNodes().size()); // target 3 -> two 2-point tasks
	}

	@Test
	void trainSkillGoalSelectsOnlyTheTrainingNode()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).goal(GoalMode.trainSkill("cooking", 30))
			.withTrainingMethod("COOKING", "Fast", 1, 99, 50000).withTask("a", "FISHING", 1, 5, 12850);
		PrecedenceGraph g = s.select();
		assertEquals(1, g.getNodes().size());
		assertEquals("train:COOKING:30", g.getNodes().get(0).getId());
	}

	@Test
	void trainSkillGoalWithoutMethodIsUnplannable()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).goal(GoalMode.trainSkill("COOKING", 30)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.SKILL, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void trainSkillGoalAlreadyMetSelectsNothing()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850).toBuilder().level("COOKING", 40).build())
			.goal(GoalMode.trainSkill("COOKING", 30)).withTrainingMethod("COOKING", "Fast", 1, 99, 50000).select();
		assertTrue(g.getNodes().isEmpty());
		assertTrue(g.getUnplannable().isEmpty());
	}

	@Test
	void maxGearGoalBuysAffordableEquippablesCheapestFirst()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).goal(GoalMode.maxGear())
			.withLocations(SHOP_LOCATION)
			.withItems(items(item(1, "weapon", "S", 26, ""), item(2, "body", "S", 10, ""), item(3, null, "S", 1, ""),
				item(4, "head", "S", 5, "\"ATTACK\":40")));
		PrecedenceGraph g = s.select();
		assertEquals(Arrays.asList("acquire:2:1", "acquire:1:1"),
			g.getNodes().stream().map(PlanNode::getId).collect(Collectors.toList()));
		assertEquals(1, g.getNodes().get(0).getPoints());
	}

	@Test
	void questCheckTaskExpandsToChainWithIntraChainPrecedence()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).withQuests(quests(quest("Q1", null, null)))
			.withQuestCheckTask("tq", "Q1", 4, 12850).select();
		assertEquals(Arrays.asList("quest:Q1:0", "quest:Q1:1"), g.getNodes().stream().map(PlanNode::getId).collect(Collectors.toList()));
		assertEquals(Collections.singletonList("quest:Q1:0"), g.getPreds().get("quest:Q1:1"));
		assertEquals(4, g.getNodes().stream().mapToInt(PlanNode::getPoints).sum());
	}

	@Test
	void questCheckForCompletedQuestUsesPlainTaskNode()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850).toBuilder().quest("Q1").build())
			.withQuests(quests(quest("Q1", null, null))).withQuestCheckTask("tq", "Q1", 4, 12850).select();
		assertEquals(1, g.getNodes().size());
		assertEquals("task:tq", g.getNodes().get(0).getId());
	}

	@Test
	void missingPrerequisiteQuestAddsChainDependingOnItsLastStep()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850))
			.withQuests(quests(quest("Q1", null, null), quest("Q2", "Q1", null)))
			.withQuestCheckTask("t2", "Q2", 3, 12850).select();
		assertEquals(4, g.getNodes().size());
		assertTrue(g.getPreds().get("quest:Q2:0").contains("quest:Q1:1"));
		assertEquals(3, g.getNodes().stream().mapToInt(PlanNode::getPoints).sum());
	}

	@Test
	void goalQuestAlsoNeededAsPrerequisiteIsNotDuplicated()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850))
			.withQuests(quests(quest("Q1", null, null), quest("Q2", "Q1", null)))
			.withQuestCheckTask("t1", "Q1", 2, 12850).withQuestCheckTask("t2", "Q2", 3, 12850).select();
		assertEquals(4, g.getNodes().size());
		assertEquals(5, g.getNodes().stream().mapToInt(PlanNode::getPoints).sum());
		assertTrue(g.getPreds().get("quest:Q2:0").contains("quest:Q1:1"));
		assertFalse(g.hasCycle());
	}

	@Test
	void missingItemAddsCheapestAcquisitionProvider()
	{
		String egg = "{\"name\":\"Egg\",\"ids\":[1,2],\"qty\":3}";
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).withLocations(SHOP_LOCATION)
			.withItems(items(item(1, null, "S", 10, ""), item(2, null, "S", 5, "")))
			.withQuests(quests(quest("Q1", null, egg))).withQuestCheckTask("tq", "Q1", 1, 12850).select();
		assertEquals(Collections.singletonList("acquire:2:3"), g.getPreds().get("quest:Q1:0"));
		assertEquals(3, g.getNodes().size()); // 2 quest steps + acquisition
	}

	@Test
	void unbuyableItemMakesTaskUnplannableWithItemMissing()
	{
		String egg = "{\"name\":\"Egg\",\"ids\":[1],\"qty\":1}";
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).withQuests(quests(quest("Q1", null, egg)))
			.withQuestCheckTask("tq", "Q1", 1, 12850).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.ITEM, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void questPointRequirementHasNoProvider()
	{
		String req = "{\"id\":\"QP\",\"name\":\"QP\",\"requirements\":{\"skills\":{},\"quests\":[],\"questPoints\":50,\"combatLevel\":0,\"items\":[]},"
			+ "\"rewards\":{\"xp\":{},\"choiceXp\":[],\"questPoints\":1,\"unlocks\":[]},\"steps\":[]}";
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).withQuests(quests(req))
			.withQuestCheckTask("tq", "QP", 1, 12850).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.QUEST_POINTS, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void blockedProviderDropsCandidateButKeepsOthers()
	{
		String egg = "{\"name\":\"Egg\",\"ids\":[1],\"qty\":1}";
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850)).withQuests(quests(quest("Q1", null, egg)))
			.withQuestCheckTask("tq", "Q1", 1, 12850).withTask("ok", "FISHING", 1, 1, 12850).select();
		assertEquals(1, g.getNodes().size());
		assertEquals("task:ok", g.getNodes().get(0).getId());
		assertEquals(1, g.getUnplannable().size());
	}

	@Test
	void mutualQuestPrerequisitesAreReportedAsCycleNotHung()
	{
		PrecedenceGraph g = new SelectorTestSupport().state(unlocked(12850))
			.withQuests(quests(quest("Q1", "Q2", null), quest("Q2", "Q1", null)))
			.withQuestCheckTask("t1", "Q1", 1, 12850).select();
		assertTrue(g.getNodes().isEmpty());
		assertFalse(g.getUnplannable().isEmpty());
		Missing m = g.getUnplannable().get(0).getMissing().get(0);
		assertEquals(Missing.Kind.UNKNOWN, m.getKind());
		assertTrue(m.getText().startsWith("precedence cycle: "), m.getText());
	}

	@Test
	void selectionIsDeterministic()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).withTrainingMethod("COOKING", "Fast", 1, 99, 50000);
		for (int i = 0; i < 8; i++)
		{
			s.withTask("t" + i, i % 2 == 0 ? "COOKING" : "FISHING", 20 + i, 1 + i % 3, 12850);
		}
		List<String> a = s.select().getNodes().stream().map(PlanNode::getId).collect(Collectors.toList());
		List<String> b = s.select().getNodes().stream().map(PlanNode::getId).collect(Collectors.toList());
		assertEquals(a, b);
	}

	@Test
	void precedenceCycleIsReportedNotHung()
	{
		PrecedenceGraph g = PrecedenceGraph.of(
			Arrays.asList(node("x"), node("y")),
			map("x", "y", "y", "x"));
		assertEquals(new HashSet<>(Arrays.asList("x", "y")), new HashSet<>(g.findCycle()));
		assertTrue(g.hasCycle());
	}

	@Test
	void acyclicGraphHasNoCycle()
	{
		PrecedenceGraph g = PrecedenceGraph.of(Arrays.asList(node("x"), node("y"), node("z")), map("z", "y", "y", "x"));
		assertTrue(g.findCycle().isEmpty());
		assertFalse(g.hasCycle());
	}
}
