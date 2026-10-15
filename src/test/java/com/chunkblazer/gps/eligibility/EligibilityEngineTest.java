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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.google.gson.Gson;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class EligibilityEngineTest
{
	private EligibilityEngine engine(GpsData data)
	{
		QuestCatalog q = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
		return new EligibilityEngine(new RequirementDeriver(q, Overrides.empty(), data), q, data);
	}

	@Test
	void freshAccountBlockedOnLevel30Cooking()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluate(task("COOKING", 30), PlayerState.freshAccount());
		assertEquals(Eligibility.Status.BLOCKED, e.getStatus());
	}

	@Test
	void doableWhenLevelMet()
	{
		PlayerState s = PlayerState.builder().level("COOKING", 30).build();
		Eligibility e = engine(GpsData.empty(null)).evaluate(task("COOKING", 30), s);
		assertEquals(Eligibility.Status.DOABLE, e.getStatus());
		assertTrue(e.getMissing().isEmpty());
	}

	@Test
	void varbitTaskIsUnknownNotDoable()
	{
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\"}", NuzlockeTask.class);
		assertEquals(Eligibility.Status.UNKNOWN, engine(GpsData.empty(null)).evaluate(t, PlayerState.freshAccount()).getStatus());
	}

	@Test
	void combatAchievementIsUnknownButBlockedWhenAlsoMissingSkill()
	{
		EligibilityEngine eng = engine(GpsData.empty(null));
		NuzlockeTask ca = new Gson().fromJson("{\"name\":\"C\",\"taskID\":\"c\",\"completion_type\":\"COMBAT_ACHIEVEMENT\"}", NuzlockeTask.class);
		assertEquals(Eligibility.Status.UNKNOWN, eng.evaluate(ca, PlayerState.freshAccount()).getStatus());

		NuzlockeTask mixed = new Gson().fromJson("{\"name\":\"C\",\"taskID\":\"c\",\"completion_type\":\"COMBAT_ACHIEVEMENT\","
			+ "\"constraints\":{\"required_skill\":\"MINING\",\"required_level\":50}}", NuzlockeTask.class);
		Eligibility e = eng.evaluate(mixed, PlayerState.freshAccount());
		assertEquals(Eligibility.Status.BLOCKED, e.getStatus());
		assertTrue(e.getMissing().stream().anyMatch(m -> m.getKind() == Missing.Kind.SKILL));
		assertTrue(e.getMissing().stream().anyMatch(m -> m.getKind() == Missing.Kind.UNKNOWN));
	}

	@Test
	void questListsUncompletedPrerequisites()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluateQuest("ANIMAL_MAGNETISM", PlayerState.freshAccount());
		assertEquals(Eligibility.Status.BLOCKED, e.getStatus());
		assertTrue(e.getMissing().stream().anyMatch(m -> m.getKind() == Missing.Kind.QUEST));
	}

	@Test
	void questMissingHasNoDuplicates()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluateQuest("ANIMAL_MAGNETISM", PlayerState.freshAccount());
		assertEquals(e.getMissing().size(), new HashSet<>(e.getMissing()).size());
	}

	@Test
	void unknownQuestIsUnknown()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluateQuest("NOPE", PlayerState.freshAccount());
		assertEquals(Eligibility.Status.UNKNOWN, e.getStatus());
		assertEquals(1, e.getMissing().size());
	}

	@Test
	void missingGpsDataNeverThrows()
	{
		assertDoesNotThrow(() -> engine(GpsData.empty(null)).evaluate(task("NPC_KILL", 0), PlayerState.freshAccount()));
	}

	private static NuzlockeTask task(String type, int level)
	{
		return new Gson().fromJson("{\"name\":\"T\",\"taskID\":\"t\",\"completion_type\":\"" + type + "\",\"level\":" + level + "}", NuzlockeTask.class);
	}
}
