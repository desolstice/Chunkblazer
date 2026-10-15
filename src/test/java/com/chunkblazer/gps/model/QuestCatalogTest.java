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
package com.chunkblazer.gps.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataStore;
import com.chunkblazer.gps.data.QuestDef;
import com.chunkblazer.gps.data.Requirements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestCatalogTest
{
	private static QuestHelperEntry helper(List<Integer> chunks, List<String> quests, Map<String, Integer> skills)
	{
		return new QuestHelperEntry(chunks, quests, skills);
	}

	@Test
	void helperOnlyQuestHasNoSteps()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("ANIMAL_MAGNETISM", helper(Arrays.asList(11575), Arrays.asList("ERNEST_THE_CHICKEN"), Collections.singletonMap("CRAFTING", 19)));
		QuestCatalog c = QuestCatalog.build(GpsData.empty(null), h);
		QuestModel q = c.get("ANIMAL_MAGNETISM").get();
		assertFalse(q.isFromWikiData());
		assertTrue(q.getSteps().isEmpty());
		assertEquals(19, (int) q.getRequirement().getSkills().get("CRAFTING"));
		assertTrue(q.getRequirement().getQuests().contains("ERNEST_THE_CHICKEN"));
		assertEquals(Arrays.asList(11575), q.getChunks());
	}

	@Test
	void wikiStepsAndRewardsAreUsedWhenPresent()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // TEST_QUEST with 2 steps and 300 cooking xp
		QuestCatalog c = QuestCatalog.build(d, Collections.emptyMap());
		QuestModel q = c.get("TEST_QUEST").get();
		assertTrue(q.isFromWikiData());
		assertEquals(2, q.getSteps().size());
		assertEquals(300, (int) q.getRewards().getXp().get("COOKING"));
		assertTrue(q.getChunks().isEmpty());
	}

	@Test
	void wikiRequirementIsMergedOverHelperStrictestWins()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		Map<String, Integer> skills = new HashMap<>();
		skills.put("COOKING", 20);
		skills.put("CRAFTING", 5);
		h.put("TEST_QUEST", helper(Arrays.asList(1, 2), Arrays.asList("OTHER"), skills));
		QuestCatalog c = QuestCatalog.build(GpsDataStore.load(GpsTestFixtures.dir("full")), h);
		QuestModel q = c.get("TEST_QUEST").get();
		assertTrue(q.isFromWikiData());
		assertEquals(20, (int) q.getRequirement().getSkills().get("COOKING")); // helper 20 > wiki 10
		assertEquals(5, (int) q.getRequirement().getSkills().get("CRAFTING"));
		assertTrue(q.getRequirement().getQuests().contains("OTHER"));
		assertEquals(1, q.getRequirement().getItems().size());
		assertEquals(Arrays.asList(1, 2), q.getChunks());
	}

	@Test
	void wikiQuestPointsAndCombatLevelAreCarried()
	{
		Requirements r = new Requirements(Collections.singletonMap("COOKING", 3), Arrays.asList("A"), 12, 40, null);
		QuestDef def = new QuestDef("Q", "Q", r, null, null);
		GpsData d = new GpsData(null, null, new ArrayList<>(Arrays.asList(def)), null, null, null, null, null);
		QuestModel q = QuestCatalog.build(d, Collections.emptyMap()).get("Q").get();
		assertEquals(12, q.getRequirement().getQuestPoints());
		assertEquals(40, q.getRequirement().getCombatLevel());
		assertEquals(3, (int) q.getRequirement().getSkills().get("COOKING"));
		assertTrue(q.getSteps().isEmpty());
	}

	@Test
	void prerequisiteClosureIsOrderedAndCycleSafe()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("C", helper(null, Arrays.asList("B"), null));
		h.put("B", helper(null, Arrays.asList("A"), null));
		h.put("A", helper(null, Arrays.asList("C"), null)); // cycle
		h.put("D", helper(null, Arrays.asList("MISSING"), null));
		QuestCatalog c = QuestCatalog.build(GpsData.empty(null), h);
		List<String> closure = c.prerequisitesClosure("C");
		assertEquals(new HashSet<>(Arrays.asList("A", "B")), new HashSet<>(closure));
		assertFalse(closure.contains("C"));
		assertEquals(Collections.emptyList(), c.prerequisitesClosure("D"));
		assertEquals(Collections.emptyList(), c.prerequisitesClosure("NOPE"));
	}

	@Test
	void prerequisitesComeBeforeDependents()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("Z", helper(null, Arrays.asList("Y", "X"), null));
		h.put("Y", helper(null, Arrays.asList("X"), null));
		h.put("X", helper(null, null, null));
		List<String> closure = QuestCatalog.build(GpsData.empty(null), h).prerequisitesClosure("Z");
		assertEquals(2, closure.size());
		assertTrue(closure.indexOf("X") < closure.indexOf("Y"));
	}

	@Test
	void helperEntryToleratesNullsAndExtraJsonFields()
	{
		QuestHelperEntry e = new com.google.gson.Gson().fromJson("{\"questPoints\":5,\"foo\":1}", QuestHelperEntry.class);
		assertTrue(e.getChunks().isEmpty());
		assertTrue(e.getQuests().isEmpty());
		assertTrue(e.getSkills().isEmpty());
	}

	@Test
	void shippedQuestRequirementsFileLoads()
	{
		Map<String, QuestHelperEntry> h = QuestHelperEntry.loadFromResource();
		assertTrue(h.size() > 100);
		assertTrue(h.containsKey("ANIMAL_MAGNETISM"));
	}
}
