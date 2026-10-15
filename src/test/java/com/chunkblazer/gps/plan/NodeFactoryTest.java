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

import static org.junit.jupiter.api.Assertions.*;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataStore;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.chunkblazer.gps.travel.LocationResolver;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeFactoryTest
{
	private NodeFactory factory(GpsData d, Map<String, QuestHelperEntry> helper)
	{
		QuestCatalog q = QuestCatalog.build(d, helper);
		return new NodeFactory(d, q, new RequirementDeriver(q, Overrides.empty(), d), new LocationResolver(d, q));
	}

	@Test
	void taskNodeCarriesPointsAndRequirement()
	{
		GpsData d = GpsData.empty(null);
		NodeFactory f = factory(d, Collections.emptyMap());
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30,\"base_points\":3}", NuzlockeTask.class);
		PlanNode n = f.forTask(new TaskRef(t, 12850, 0));
		assertEquals("task:c", n.getId());
		assertEquals(NodeType.TASK, n.getType());
		assertEquals(3, n.getPoints());
		assertEquals(3, n.getEffects().getTaskPoints());
		assertEquals(30, (int) n.getRequirement().getSkills().get("COOKING"));
		assertTrue(n.getLocation().isApproximate());
		assertEquals(0, n.getDurationTicks());
	}

	@Test
	void taskDurationComesFromConstraintTicks()
	{
		NodeFactory f = factory(GpsData.empty(null), Collections.emptyMap());
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"Fast kill\",\"taskID\":\"k\",\"completion_type\":\"KILL\",\"base_points\":1,\"constraints\":{\"time_in_ticks\":25}}", NuzlockeTask.class);
		assertEquals(25, f.forTask(new TaskRef(t, 12850, 0)).getDurationTicks());
	}

	@Test
	void questChainHasOrderedStepsRewardsOnLastStepRequirementOnFirst()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // TEST_QUEST: 2 steps, 300 cooking xp, requires COOKING 10
		NodeFactory f = factory(d, Collections.singletonMap("TEST_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, null)));
		List<PlanNode> chain = f.questChain("TEST_QUEST", 1);
		assertEquals(2, chain.size());
		assertEquals("quest:TEST_QUEST:0", chain.get(0).getId());
		assertEquals(Collections.emptyList(), chain.get(0).getAfter());
		assertEquals(Collections.singletonList(chain.get(0).getId()), chain.get(1).getAfter());
		assertEquals(10, (int) chain.get(0).getRequirement().getSkills().get("COOKING"));
		assertTrue(chain.get(1).getRequirement().getSkills().isEmpty());
		assertTrue(chain.get(0).getEffects().getQuestsCompleted().isEmpty());
		assertTrue(chain.get(1).getEffects().getQuestsCompleted().containsKey("TEST_QUEST"));
		assertEquals(1, (int) chain.get(1).getEffects().getQuestsCompleted().get("TEST_QUEST"));
		assertEquals(300L, (long) chain.get(1).getEffects().getXp().get("COOKING"));
		assertEquals(1, chain.get(1).getEffects().getTaskPoints());
		assertEquals(1, chain.get(1).getPoints());
		assertEquals(0, chain.get(0).getPoints());
	}

	@Test
	void unknownStepLocationCarriesPreviousAndIsApproximate()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // step 1 has no location
		NodeFactory f = factory(d, Collections.emptyMap());
		List<PlanNode> chain = f.questChain("TEST_QUEST", 1);
		assertNotNull(chain.get(0).getLocation().getTile());
		assertEquals(chain.get(0).getLocation().getTile(), chain.get(1).getLocation().getTile());
		assertTrue(chain.get(1).getLocation().isApproximate());
	}

	@Test
	void unknownFirstStepFallsBackToChunkCentreAndThenNone()
	{
		String quests = "{\"schema\":1,\"quests\":[{\"id\":\"Q\",\"name\":\"Q\",\"requirements\":{},\"rewards\":{\"unlocks\":[{\"type\":\"TELEPORT\",\"name\":\"Foo\"}]},\"steps\":[{\"index\":0,\"text\":\"a\",\"kind\":\"OTHER\",\"location\":null,\"items\":[]}]}]}";
		GpsData d = GpsTestFixtures.dataWithAll(null, null, null, quests, null);
		PlanNode withChunk = factory(d, Collections.singletonMap("Q", new QuestHelperEntry(Arrays.asList(12850), null, null))).questChain("Q", 2).get(0);
		assertNotNull(withChunk.getLocation().getTile());
		assertTrue(withChunk.getLocation().isApproximate());
		assertTrue(withChunk.getEffects().getUnlocks().contains("TELEPORT:Foo"));
		PlanNode none = factory(d, Collections.emptyMap()).questChain("Q", 2).get(0);
		assertNull(none.getLocation().getTile());
		assertEquals("NONE", none.getLocation().getSource());
	}

	@Test
	void unknownQuestYieldsEmptyChain()
	{
		assertTrue(factory(GpsData.empty(null), Collections.emptyMap()).questChain("NOPE", 1).isEmpty());
	}

	@Test
	void helperOnlyQuestIsSingleAtomicNode()
	{
		GpsData d = GpsData.empty(null);
		NodeFactory f = factory(d, Collections.singletonMap("OLD_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, Collections.singletonMap("MINING", 5))));
		List<PlanNode> chain = f.questChain("OLD_QUEST", 1);
		assertEquals(1, chain.size());
		assertTrue(chain.get(0).getEffects().getQuestsCompleted().containsKey("OLD_QUEST"));
		assertEquals(5, (int) chain.get(0).getRequirement().getSkills().get("MINING"));
		assertEquals(1, chain.get(0).getPoints());
	}

	@Test
	void trainingNodePicksBestMethodAndEmptyWithoutData()
	{
		GpsData d = GpsTestFixtures.dataWithTraining("{\"schema\":1,\"methods\":[{\"skill\":\"COOKING\",\"name\":\"Slow\",\"minLevel\":1,\"maxLevel\":99,\"xpPerHour\":1000,\"location\":null,\"items\":[],\"approximate\":true},"
			+ "{\"name\":\"Fast\",\"skill\":\"COOKING\",\"minLevel\":1,\"maxLevel\":99,\"xpPerHour\":50000,\"location\":null,\"items\":[],\"approximate\":true}]}");
		Optional<PlanNode> n = factory(d, Collections.emptyMap()).trainingNode("COOKING", 40);
		assertTrue(n.isPresent());
		assertEquals("train:COOKING:40", n.get().getId());
		assertTrue(n.get().getLabel().contains("Fast"));
		assertEquals(40, (int) n.get().getEffects().getReachLevel().get("COOKING"));
		assertNull(n.get().getLocation().getTile());
		assertTrue(n.get().getLocation().isApproximate());
		assertTrue(n.get().getDurationTicks() > 0);
		assertFalse(factory(GpsData.empty(null), Collections.emptyMap()).trainingNode("COOKING", 40).isPresent());
	}

	@Test
	void trainingNodeSkipsMethodsNotCoveringTheLevel()
	{
		GpsData d = GpsTestFixtures.dataWithTraining("{\"schema\":1,\"methods\":[{\"skill\":\"COOKING\",\"name\":\"Low\",\"minLevel\":1,\"maxLevel\":30,\"xpPerHour\":90000,\"location\":null,\"items\":[],\"approximate\":true}]}");
		assertFalse(factory(d, Collections.emptyMap()).trainingNode("COOKING", 40).isPresent());
		assertTrue(factory(d, Collections.emptyMap()).trainingNode("COOKING", 20).isPresent());
	}

	@Test
	void acquireItemNeedsAShopSource()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			"{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Aubury's Rune Shop\",\"price\":17}],\"teleport\":null}]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Aubury's Rune Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}");
		NodeFactory f = factory(d, Collections.emptyMap());
		Optional<PlanNode> n = f.acquireItem(554, 10);
		assertTrue(n.isPresent());
		assertEquals(NodeType.ACQUIRE_ITEM, n.get().getType());
		assertEquals(10, (int) n.get().getEffects().getItems().get(554));
		assertEquals(3253, (int) n.get().getLocation().getTile().getX());
		assertFalse(f.acquireItem(999, 1).isPresent());
		assertNotNull(f.acquireItemNode(554, 10));
		assertNull(f.acquireItemNode(999, 1));
	}

	@Test
	void acquireItemWithUnlocatedShopIsEmpty()
	{
		GpsData d = GpsTestFixtures.dataWithAll(null,
			"{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Nowhere Shop\",\"price\":17},{\"type\":\"DROP\",\"npc\":\"Goblin\"}],\"teleport\":null}]}",
			null, null, null);
		assertFalse(factory(d, Collections.emptyMap()).acquireItem(554, 1).isPresent());
	}
}
