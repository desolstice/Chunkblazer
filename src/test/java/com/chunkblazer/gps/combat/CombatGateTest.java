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
package com.chunkblazer.gps.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.MonsterDef;
import com.chunkblazer.gps.eligibility.EligibilityEngine;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.Requirement;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CombatGateTest
{
	private static final Gson GSON = new Gson();

	private static final String BRUTUS = "{\"name\":\"Defeat Brutus\",\"taskID\":\"b\",\"completion_type\":\"NPC_Kill\","
		+ "\"target_npc\":{\"name\":\"Brutus\",\"npc_ids\":[15626,15627,15628]}}";

	private static MonsterDef monster(String ids, String name, int hp, int combat)
	{
		return GSON.fromJson("{\"ids\":" + ids + ",\"name\":\"" + name + "\",\"hp\":" + hp + ",\"combatLevel\":" + combat + "}",
			MonsterDef.class);
	}

	private static GpsData brutusData()
	{
		return GpsData.withMonsters(Arrays.asList(monster("[15626,15627]", "Brutus", 58, 30),
			monster("[15628]", "Demonic Brutus", 750, 1224), monster("[2790]", "Cow", 8, 2)));
	}

	private static Requirement derive(GpsData d, String taskJson)
	{
		return new RequirementDeriver(QuestCatalog.build(d, Collections.emptyMap()), Overrides.empty(), d)
			.derive(GSON.fromJson(taskJson, NuzlockeTask.class));
	}

	@Test
	void thresholdsAreDocumentedConstants()
	{
		assertEquals(10, CombatGate.COMBAT_LEVEL_MARGIN);
		assertEquals(30, CombatGate.MAX_HITS_TO_KILL);
		assertEquals(20, CombatGate.requiredCombatLevel(30));
		assertEquals(0, CombatGate.requiredCombatLevel(8));
		assertEquals(2, CombatGate.requiredMaxHit(58));
		assertEquals(1, CombatGate.requiredMaxHit(8));
		assertEquals(34, CombatGate.requiredMaxHit(1000));
	}

	@Test
	void easiestTargetVariantIsUsedNotTheDemonicOne()
	{
		MonsterDef m = CombatGate.easiestTarget(GSON.fromJson(BRUTUS, NuzlockeTask.class), brutusData()).get();
		assertEquals(30, m.getCombatLevel());
		assertEquals(58, m.getHp());
	}

	@Test
	void bossKillNeedsCombatLevelAndDamageAndIsApproximate()
	{
		Requirement r = derive(brutusData(), BRUTUS);
		assertEquals(20, r.getCombatLevel());
		assertEquals(2, r.getMinMaxHit());
		assertTrue(r.isApproximate());
	}

	@Test
	void freshAccountIsBlockedFromBrutusButNotFromACow()
	{
		GpsData d = brutusData();
		QuestCatalog q = QuestCatalog.build(d, Collections.emptyMap());
		EligibilityEngine eng = new EligibilityEngine(new RequirementDeriver(q, Overrides.empty(), d), q, d);
		PlayerState fresh = PlayerState.freshAccount();
		assertEquals("BLOCKED", eng.evaluate(GSON.fromJson(BRUTUS, NuzlockeTask.class), fresh).getStatus().name());
		String cow = "{\"name\":\"Defeat a Cow\",\"taskID\":\"c\",\"completion_type\":\"NPC_Kill\","
			+ "\"target_npc\":{\"name\":\"Cow\",\"npc_ids\":[2790]}}";
		assertEquals("DOABLE", eng.evaluate(GSON.fromJson(cow, NuzlockeTask.class), fresh).getStatus().name());
		PlayerState strong = fresh.toBuilder().combatLevel(40).build();
		assertEquals("DOABLE", eng.evaluate(GSON.fromJson(BRUTUS, NuzlockeTask.class), strong.toBuilder().maxHit(5).build())
			.getStatus().name());
	}

	@Test
	void unknownMonsterOrMissingCombatLevelAddsNoGate()
	{
		assertEquals(0, derive(GpsData.empty(null), BRUTUS).getCombatLevel());
		GpsData noLevel = GpsData.withMonsters(Collections.singletonList(
			GSON.fromJson("{\"ids\":[15626],\"name\":\"Brutus\",\"hp\":58}", MonsterDef.class)));
		assertEquals(0, derive(noLevel, BRUTUS).getCombatLevel());
	}

	@Test
	void timeLimitedKillKeepsItsOwnMaxHitAndStillGetsTheCombatGate()
	{
		Requirement r = derive(brutusData(), BRUTUS.replace("]}}", "]},\"constraints\":{\"time_in_ticks\":[20]}}"));
		assertEquals(20, r.getCombatLevel());
		assertTrue(r.getMinMaxHit() >= 2);
		assertFalse(r.isUnknown());
	}
}
