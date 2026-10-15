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
package com.chunkblazer.gps.eligibility;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.chunkblazer.gps.model.Requirement;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeriverTest
{
	private static final Gson GSON = new GsonBuilder().create(); // NuzlockeTask has @JsonAdapter, plain Gson works

	private static NuzlockeTask task(String json)
	{
		return GSON.fromJson(json, NuzlockeTask.class);
	}

	private RequirementDeriver deriver()
	{
		return new RequirementDeriver(QuestCatalog.build(GpsData.empty(null), QuestHelperEntry.loadFromResource()), Overrides.empty());
	}

	private Requirement derive(String json)
	{
		return deriver().derive(task(json));
	}

	@Test
	void skillTaskDerivesSkillLevel()
	{
		Requirement r = derive("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30,\"base_points\":1}");
		assertEquals(30, (int) r.getSkills().get("COOKING"));
	}

	@Test
	void completionTypeIsCaseInsensitive()
	{
		Requirement r = derive("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"Cooking\",\"level\":30}");
		assertEquals(30, (int) r.getSkills().get("COOKING"));
	}

	@Test
	void skillThresholdUsesConstraintSkill()
	{
		Requirement r = derive("{\"name\":\"T\",\"taskID\":\"t\",\"completion_type\":\"SKILL_THRESHOLD\",\"level\":40,\"constraints\":{\"required_skill\":\"Mining\"}}");
		assertEquals(40, (int) r.getSkills().get("MINING"));
		assertEquals(1, r.getSkills().size());
	}

	@Test
	void runecraftingMapsToRunecraft()
	{
		Requirement r = derive("{\"name\":\"R\",\"taskID\":\"r\",\"completion_type\":\"RUNECRAFTING\",\"level\":44}");
		assertEquals(44, (int) r.getSkills().get("RUNECRAFT"));
	}

	@Test
	void constraintSkillAddsSkillForAnyType()
	{
		Requirement r = derive("{\"name\":\"K\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"constraints\":{\"required_skill\":\"magic\",\"required_level\":55}}");
		assertEquals(55, (int) r.getSkills().get("MAGIC"));
	}

	@Test
	void requiredItemsBecomeItemNeeds()
	{
		Requirement r = derive("{\"name\":\"Plant\",\"taskID\":\"p\",\"completion_type\":\"FARMING\",\"level\":1,\"required_items\":[{\"item\":[\"Potato Seed\"],\"item_ids\":[5318],\"quantity\":[1]}]}");
		assertEquals(1, r.getItems().size());
		assertTrue(r.getItems().get(0).getAnyOf().contains(5318));
		assertEquals(1, r.getItems().get(0).getQty());
		assertEquals("Potato Seed", r.getItems().get(0).getName());
	}

	@Test
	void requiredItemQuantityAndAnyOfIds()
	{
		Requirement r = derive("{\"name\":\"I\",\"taskID\":\"i\",\"completion_type\":\"EQUIP\",\"required_items\":[{\"item\":\"Logs\",\"item_ids\":[1511,1513],\"quantity\":5}]}");
		assertEquals(5, r.getItems().get(0).getQty());
		assertEquals(2, r.getItems().get(0).getAnyOf().size());
	}

	@Test
	void questCheckPullsQuestRequirementAndPrerequisites()
	{
		Requirement r = derive("{\"name\":\"Q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"ANIMAL_MAGNETISM\"}}");
		assertTrue(r.getQuests().contains("ERNEST_THE_CHICKEN"));
		assertEquals(19, (int) r.getSkills().get("CRAFTING"));
	}

	@Test
	void unknownQuestIdIsUnknownRequirement()
	{
		Requirement r = derive("{\"name\":\"Q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"NO_SUCH_QUEST\"}}");
		assertTrue(r.isUnknown());
		assertTrue(r.getUnknown().contains("quest data missing: NO_SUCH_QUEST"));
	}

	@Test
	void minCombatLevelBecomesCombatLevel()
	{
		Requirement r = derive("{\"name\":\"K\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"constraints\":{\"min_combat_level\":70}}");
		assertEquals(70, r.getCombatLevel());
	}

	@Test
	void requiredRegionBecomesChunk()
	{
		Requirement r = derive("{\"name\":\"K\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"constraints\":{\"required_region\":12850}}");
		assertTrue(r.getChunks().contains(12850));
		assertFalse(r.isUnknown());
	}

	@Test
	void singleAllowedRegionIsChunkOnly()
	{
		Requirement r = derive("{\"name\":\"K\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"constraints\":{\"allowed_regions\":[111]}}");
		assertTrue(r.getChunks().contains(111));
		assertFalse(r.isUnknown());
	}

	@Test
	void multipleAllowedRegionsRecordFirstAndUnknown()
	{
		Requirement r = derive("{\"name\":\"K\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"constraints\":{\"allowed_regions\":[111,222]}}");
		assertEquals(Collections.singleton(111), r.getChunks());
		assertTrue(r.getUnknown().contains("any of regions"));
	}

	@Test
	void varbitAndCaTasksAreUnknown()
	{
		assertTrue(derive("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\"}").isUnknown());
		assertTrue(derive("{\"name\":\"C\",\"taskID\":\"c\",\"completion_type\":\"COMBAT_ACHIEVEMENT\",\"ca_ids\":[1]}").isUnknown());
	}

	@Test
	void unjudgeableCompletionTypesAreUnknown()
	{
		for (String type : new String[]{"VARP_CHECK", "RAID_CHALLENGE", "NPC_DIALOGUE"})
		{
			Requirement r = derive("{\"name\":\"X\",\"taskID\":\"x\",\"completion_type\":\"" + type + "\"}");
			assertTrue(r.isUnknown(), type);
			assertTrue(r.getUnknown().get(0).contains(type), type);
		}
	}

	@Test
	void varbitOrVarpConstraintIsUnknown()
	{
		assertTrue(derive("{\"name\":\"X\",\"taskID\":\"x\",\"completion_type\":\"EQUIP\",\"constraints\":{\"varbit_id\":5}}").isUnknown());
		assertTrue(derive("{\"name\":\"X\",\"taskID\":\"x\",\"completion_type\":\"EQUIP\",\"constraints\":{\"varp_id\":5}}").isUnknown());
	}

	@Test
	void nullCompletionTypeAndConstraintsDoNotThrow()
	{
		Requirement r = derive("{\"name\":\"X\",\"taskID\":\"x\"}");
		assertFalse(r.isUnknown());
		assertTrue(r.getSkills().isEmpty());
	}

	@Test
	void overrideReplacesDerived(@TempDir Path tmp) throws Exception
	{
		Files.createDirectories(tmp.resolve("overrides"));
		Files.write(tmp.resolve("overrides/requirement_overrides.json"),
			"{\"c\":{\"skills\":{\"COOKING\":50},\"quests\":[\"X\"],\"items\":[{\"ids\":[1,2],\"qty\":3,\"name\":\"N\"}],\"minMaxHit\":7,\"unknown\":[\"u\"]}}".getBytes(StandardCharsets.UTF_8));
		RequirementDeriver d = new RequirementDeriver(QuestCatalog.build(GpsData.empty(null), Collections.emptyMap()), Overrides.load(tmp));
		Requirement r = d.derive(task("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30,\"required_items\":[{\"item_ids\":[9]}]}"));
		assertEquals(50, (int) r.getSkills().get("COOKING"));
		assertTrue(r.getQuests().contains("X"));
		assertEquals(3, r.getItems().get(0).getQty());
		assertEquals("N", r.getItems().get(0).getName());
		assertEquals(1, r.getItems().size());
		assertEquals(7, r.getMinMaxHit());
		assertEquals(Collections.singletonList("u"), r.getUnknown());
	}

	@Test
	void missingOrMalformedOverridesAreEmpty(@TempDir Path tmp) throws Exception
	{
		assertFalse(Overrides.load(tmp).get("c").isPresent());
		Files.createDirectories(tmp.resolve("overrides"));
		Files.write(tmp.resolve("overrides/requirement_overrides.json"), "{not json".getBytes(StandardCharsets.UTF_8));
		assertFalse(Overrides.load(tmp).get("c").isPresent());
		Files.write(tmp.resolve("overrides/requirement_overrides.json"), "[1,2]".getBytes(StandardCharsets.UTF_8));
		assertFalse(Overrides.load(tmp).get("c").isPresent());
		assertFalse(Overrides.empty().get("c").isPresent());
	}

	@Test
	void taskWithNothingRequiredIsNone()
	{
		Requirement r = derive("{\"name\":\"Free\",\"taskID\":\"f\",\"completion_type\":\"EQUIP\"}");
		assertTrue(r.getSkills().isEmpty());
		assertFalse(r.isUnknown());
	}
}
