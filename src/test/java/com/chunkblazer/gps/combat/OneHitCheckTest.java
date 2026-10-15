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

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.MonsterDef;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.QuestCatalog;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneHitCheckTest
{
	private static final Gson GSON = new Gson();

	private static NuzlockeTask task(String json)
	{
		return GSON.fromJson(json, NuzlockeTask.class);
	}

	private static MonsterDef monster(List<Integer> ids, String name, int hp)
	{
		String json = "{\"ids\":" + ids + ",\"name\":\"" + name + "\",\"hp\":" + hp + "}";
		return GSON.fromJson(json, MonsterDef.class);
	}

	private static RequirementDeriver deriverFor(GpsData d)
	{
		return new RequirementDeriver(QuestCatalog.build(d, Collections.emptyMap()), Overrides.empty(), d);
	}

	private static final String ONE_HIT = "{\"name\":\"Defeat a Level 20 Dark Wizard 1 Hit\",\"taskID\":\"w\",\"completion_type\":\"NPC_Kill\","
		+ "\"target_npc\":{\"npc\":[\"Wizard\"],\"npc_ids\":[510,5088],\"quantity\":[1]},\"constraints\":{\"time_in_ticks\":[1]}}";

	private static final String NAME_ONLY = "{\"name\":\"n\",\"taskID\":\"n\",\"completion_type\":\"NPC_KILL\","
		+ "\"target_npc\":{\"npc\":[\"Dark wizard#Level 20\"],\"quantity\":[1]},\"constraints\":{\"time_in_ticks\":[1]}}";

	@Test
	void oneTickKillIsOneHit()
	{
		assertTrue(OneHitCheck.isOneHit(task(ONE_HIT)));
	}

	@Test
	void twentyTickKillIsNotOneHit()
	{
		assertFalse(OneHitCheck.isOneHit(task(ONE_HIT.replace("[1]}", "[20]}"))));
	}

	@Test
	void nonKillTaskIsNotOneHit()
	{
		assertFalse(OneHitCheck.isOneHit(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"COOKING\",\"constraints\":{\"time_in_ticks\":[1]}}")));
	}

	@Test
	void missingConstraintsOrTimeIsNotOneHit()
	{
		assertFalse(OneHitCheck.isOneHit(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\"}")));
		assertFalse(OneHitCheck.isOneHit(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\",\"constraints\":{}}")));
	}

	@Test
	void hpComesFromMonsterDataMaxOverMatchingIds()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 12), monster(Arrays.asList(5088), "Dark wizard", 14)));
		assertEquals(14, OneHitCheck.targetHp(task(ONE_HIT), d).getAsInt());
	}

	@Test
	void idsIntersectMatchesAndIgnoresUnrelatedMonsters()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(1, 5088, 9), "Other", 30), monster(Arrays.asList(77), "Big", 500)));
		assertEquals(30, OneHitCheck.targetHp(task(ONE_HIT), d).getAsInt());
	}

	@Test
	void nameMatchStripsVariantSuffixCaseInsensitive()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(1), "DARK WIZARD", 22), monster(Arrays.asList(2), "Dark wizards", 99)));
		assertEquals(22, OneHitCheck.targetHp(task(NAME_ONLY), d).getAsInt());
	}

	@Test
	void nonPositiveHpIsTreatedAsUnknown()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 0)));
		assertFalse(OneHitCheck.targetHp(task(ONE_HIT), d).isPresent());
		assertTrue(deriverFor(d).derive(task(ONE_HIT)).isUnknown());
	}

	@Test
	void noMonsterDataGivesEmptyAndDeriverMarksUnknown()
	{
		assertFalse(OneHitCheck.targetHp(task(ONE_HIT), GpsData.empty(null)).isPresent());
		RequirementDeriver der = deriverFor(GpsData.empty(null));
		assertTrue(der.derive(task(ONE_HIT)).isUnknown());
	}

	@Test
	void withMonsterDataDeriverSetsMinMaxHit()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 12)));
		assertEquals(12, deriverFor(d).derive(task(ONE_HIT)).getMinMaxHit());
	}

	@Test
	void nonOneHitKillGetsNeitherMinMaxHitNorUnknown()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 12)));
		var r = deriverFor(d).derive(task(ONE_HIT.replace("[1]}", "[20]}")));
		assertEquals(0, r.getMinMaxHit());
		assertFalse(r.isUnknown());
		assertFalse(deriverFor(GpsData.empty(null)).derive(task(ONE_HIT.replace("[1]}", "[20]}"))).isUnknown());
	}
}
