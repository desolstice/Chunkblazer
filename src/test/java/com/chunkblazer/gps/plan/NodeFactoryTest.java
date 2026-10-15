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
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.PlayerState;
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
		assertEquals("CARRY", chain.get(1).getLocation().getSource());
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

	@Test
	void trainingTieOnXpPerHourPrefersLowestMinLevel()
	{
		GpsData d = GpsTestFixtures.dataWithTraining("{\"schema\":1,\"methods\":[{\"skill\":\"COOKING\",\"name\":\"High\",\"minLevel\":10,\"maxLevel\":99,\"xpPerHour\":5000,\"location\":null,\"items\":[],\"approximate\":true},"
			+ "{\"skill\":\"COOKING\",\"name\":\"Low\",\"minLevel\":1,\"maxLevel\":99,\"xpPerHour\":5000,\"location\":null,\"items\":[],\"approximate\":true}]}");
		assertTrue(factory(d, Collections.emptyMap()).trainingNode("COOKING", 40).get().getLabel().contains("Low"));
	}

	@Test
	void acquireItemPicksCheapestLocatedShop()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			"{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Dear Shop\",\"price\":20},{\"type\":\"SHOP\",\"shop\":\"Cheap Shop\",\"price\":10}],\"teleport\":null}]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Dear Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3000,\"y\":3000,\"plane\":0},{\"name\":\"Cheap Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}");
		PlanNode n = factory(d, Collections.emptyMap()).acquireItem(554, 1).get();
		assertTrue(n.getLabel().contains("Cheap Shop"));
		assertEquals(3253, (int) n.getLocation().getTile().getX());
	}

	private static final String ITEMS_HEAD = "{\"schema\":1,\"items\":[";

	private static String item(int id, String name, String sources)
	{
		return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},\"sources\":["
			+ sources + "],\"teleport\":null}";
	}

	private NodeFactory located(GpsData d, int... unlockedRegions)
	{
		QuestCatalog q = QuestCatalog.build(d, Collections.emptyMap());
		PlayerState.Builder b = PlayerState.builder();
		for (int r : unlockedRegions)
		{
			b.chunk(r);
		}
		return new NodeFactory(d, q, new RequirementDeriver(q, Overrides.empty(), d), new LocationResolver(d, q),
			Collections.emptySet(), b.build(), new Tile(3222, 3218, 0, false, null));
	}

	@Test
	void acquireItemIgnoresShopsInLockedChunks()
	{
		// Cheap Shop (region 13150: x 3253,y 3401) is locked; Dear Shop (region 12850) is open
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(554, "Fire rune", "{\"type\":\"SHOP\",\"shop\":\"Dear Shop\",\"price\":20},{\"type\":\"SHOP\",\"shop\":\"Cheap Shop\",\"price\":10}") + "]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Dear Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3222,\"y\":3218,\"plane\":0},{\"name\":\"Cheap Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}");
		PlanNode n = located(d, 12850).acquireItem(554, 1).get();
		assertTrue(n.getLabel().contains("Dear Shop"), n.getLabel());
	}

	@Test
	void acquireItemIsEmptyWhenEveryShopIsInALockedChunk()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(554, "Fire rune", "{\"type\":\"SHOP\",\"shop\":\"Far Shop\",\"price\":10}") + "]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Far Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}");
		assertFalse(located(d, 12850).acquireItem(554, 1).isPresent());
	}

	@Test
	void shopChoiceUsesTheNearestDuplicateToTheStartNotTheWorldOrigin()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(554, "Fire rune", "{\"type\":\"SHOP\",\"shop\":\"General Store\",\"price\":10}") + "]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"General Store\",\"kind\":\"SHOP\",\"ids\":[],\"x\":100,\"y\":100,\"plane\":0},{\"name\":\"General Store\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3230,\"y\":3220,\"plane\":0}]}");
		PlanNode n = located(d, 12850, 100 / 64 * 256 + 100 / 64).acquireItem(554, 1).get();
		assertEquals(3230, (int) n.getLocation().getTile().getX());
	}

	@Test
	void logsAreGatheredFromATreeInAnOpenChunk()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(1511, "Logs", "{\"type\":\"CRAFT\",\"skill\":\"FIREMAKING\",\"level\":1},{\"type\":\"DROP\",\"npc\":\"Man\"},{\"type\":\"DROP\",\"npc\":\"Tree\"}") + "]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Tree (Varrock)\",\"kind\":\"OBJECT\",\"ids\":[1],\"x\":3226,\"y\":3222,\"plane\":0},{\"name\":\"Tree (Far)\",\"kind\":\"OBJECT\",\"ids\":[2],\"x\":3212,\"y\":3481,\"plane\":0}]}");
		PlanNode n = located(d, 12850).acquireItem(1511, 25).get();
		assertEquals(NodeType.ACQUIRE_ITEM, n.getType());
		assertTrue(n.getLabel().startsWith("Gather 25 x Logs"), n.getLabel());
		assertEquals(25, (int) n.getEffects().getItems().get(1511));
		assertEquals(3226, (int) n.getLocation().getTile().getX());
		assertTrue(n.getLocation().isApproximate());
		assertTrue(n.getRequirement().getSkills().isEmpty());
	}

	@Test
	void gatherWithNoKnownOpenLocationIsApproximateAndUnplaced()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(1511, "Logs", "{\"type\":\"DROP\",\"npc\":\"Tree\"}") + "]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Tree (Far)\",\"kind\":\"OBJECT\",\"ids\":[2],\"x\":3212,\"y\":3481,\"plane\":0}]}");
		PlanNode n = located(d, 12850).acquireItem(1511, 1).get();
		assertNull(n.getLocation().getTile());
		assertTrue(n.getLocation().isApproximate());
	}

	@Test
	void higherTierLogsNeedWoodcuttingLevel()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(1519, "Willow logs", "{\"type\":\"DROP\",\"npc\":\"Willow tree\"},{\"type\":\"DROP\",\"npc\":\"Oak tree (Farming)\"}") + "]}", null);
		PlanNode n = located(d, 12850).acquireItem(1519, 1).get();
		assertEquals(30, (int) n.getRequirement().getSkills().get("WOODCUTTING"));
	}

	@Test
	void gemRocksNeedMiningAndBoatSpotsAreNotGatherable()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(1623, "Uncut sapphire", "{\"type\":\"DROP\",\"npc\":\"Gem rocks\"}") + ","
				+ item(317, "Raw shrimps", "{\"type\":\"DROP\",\"npc\":\"Boat fishing spot (small net, bait)\"}") + "]}", null);
		NodeFactory f = located(d, 12850);
		assertEquals(40, (int) f.acquireItem(1623, 1).get().getRequirement().getSkills().get("MINING"));
		assertFalse(f.acquireItem(317, 1).isPresent());
	}

	@Test
	void monsterDropsAreNotTreatedAsGatherable()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			ITEMS_HEAD + item(526, "Bones", "{\"type\":\"DROP\",\"npc\":\"Cow\"},{\"type\":\"DROP\",\"npc\":\"Rock Crab\"}") + "]}", null);
		assertFalse(located(d, 12850).acquireItem(526, 1).isPresent());
	}

	@Test
	void questChunksBecomeChunkRequirementsOnTheFirstStep()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full"));
		NodeFactory f = factory(d, Collections.singletonMap("TEST_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, null)));
		List<PlanNode> chain = f.questChain("TEST_QUEST", 1);
		assertTrue(chain.get(0).getRequirement().getChunks().contains(12850), chain.get(0).getRequirement().toString());
		assertEquals(10, (int) chain.get(0).getRequirement().getSkills().get("COOKING")); // merged, not replaced
		assertTrue(chain.get(1).getRequirement().getChunks().isEmpty());
	}

	@Test
	void mixedCaseRewardSkillIsNormalisedToUpperCase()
	{
		PlanWorld w = PlanWorld.builder().quest("Q", 500, "Cooking", PlanWorld.step("s", 3200, 3200)).build();
		PlanRequest r = w.request();
		NodeFactory f = new NodeFactory(r.getData(), r.getQuests(), r.getDeriver(), new LocationResolver(r.getData(), r.getQuests()));
		PlanNode last = f.questChain("Q", 0).get(0);
		assertEquals(500L, (long) last.getEffects().getXp().get("COOKING"));
		assertNull(last.getEffects().getXp().get("Cooking"));
	}
}
