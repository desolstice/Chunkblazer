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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.chunkblazer.gps.model.Requirement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CatalogWideDerivationTest
{
	// Only types that legitimately need nothing derivable may appear here. Update this set deliberately.
	private static final Set<String> EXPECTED_NONE_TYPES = new HashSet<>(Arrays.asList(
		"EQUIP", "OBTAIN", "NPC_KILL", "AGILITY", "FARMING", "FISHING", "MINING", "WOODCUTTING", "COOKING", "FLETCHING",
		"CRAFTING", "THIEVING", "CONSTRUCTION", "SMITHING", "SLAYER", "FIREMAKING", "HERBLORE", "HUNTER", "RUNECRAFTING", "PRAYER",
		// QUEST_CHECK on starter quests with no skill/quest prerequisites (Cook's Assistant, Sheep Shearer, ...) needs nothing.
		"QUEST_CHECK"));

	private static RequirementDeriver deriver()
	{
		GpsData data = GpsData.empty(null);
		QuestCatalog q = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
		return new RequirementDeriver(q, Overrides.empty(), data);
	}

	@Test
	void everyCatalogTaskDerivesWithoutThrowing()
	{
		RequirementDeriver d = deriver();
		List<NuzlockeTask> tasks = CatalogTestSupport.loadCatalogTasks();
		assertTrue(tasks.size() > 4900, "task count " + tasks.size());
		for (NuzlockeTask t : tasks)
		{
			assertNotNull(d.derive(t), t.getTaskId());
		}
	}

	@Test
	void someCatalogTasksDeriveNonEmptyRequirement()
	{
		RequirementDeriver d = deriver();
		int nonEmpty = 0;
		for (NuzlockeTask t : CatalogTestSupport.loadCatalogTasks())
		{
			if (!isNone(d.derive(t)))
			{
				nonEmpty++;
			}
		}
		assertTrue(nonEmpty > 100, "non-empty requirements: " + nonEmpty);
	}

	@Test
	void everyTaskIsEitherRequirementBearingNoneOrExplicitlyUnknown()
	{
		// the "NONE" bucket must be a conscious set: list its completion types so a new unmodelled type is noticed.
		RequirementDeriver d = deriver();
		Set<String> noneTypes = new TreeSet<>();
		for (NuzlockeTask t : CatalogTestSupport.loadCatalogTasks())
		{
			if (isNone(d.derive(t)) && t.getCompletionType() != null)
			{
				noneTypes.add(t.getCompletionType().toUpperCase(Locale.ROOT));
			}
		}
		System.out.println("NONE_TYPES=" + noneTypes);
		assertTrue(EXPECTED_NONE_TYPES.containsAll(noneTypes), "Unexpected requirement-free types: " + noneTypes);
	}

	private static boolean isNone(Requirement r)
	{
		return r.getSkills().isEmpty() && r.getQuests().isEmpty() && r.getItems().isEmpty() && r.getChunks().isEmpty()
			&& r.getCombatLevel() == 0 && r.getQuestPoints() == 0 && r.getMinMaxHit() == 0 && !r.isUnknown();
	}
}
