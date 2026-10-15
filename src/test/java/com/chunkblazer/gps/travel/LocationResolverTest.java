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
package com.chunkblazer.gps.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataStore;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationResolverTest
{
	private static GpsData dataWith(String locationsJson)
	{
		return GpsTestFixtures.dataWithLocations(locationsJson);
	}

	private static NuzlockeTask task(String json)
	{
		return new Gson().fromJson(json, NuzlockeTask.class);
	}

	private static LocationResolver resolver(GpsData d)
	{
		return new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
	}

	@Test
	void npcIdMatchWins()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Cow\",\"kind\":\"NPC\",\"ids\":[2790],\"x\":3253,\"y\":3270,\"plane\":0}]}");
		ResolvedLocation l = resolver(d).forTask(task("{\"name\":\"Kill cow\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"Cow\"],\"npc_ids\":[2790],\"quantity\":[1]}}"), 12850);
		assertEquals("NPC_ID", l.getSource());
		assertFalse(l.isApproximate());
		assertEquals(3253, (int) l.getTile().getX());
	}

	@Test
	void nameMatchStripsVariantSuffix()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Desert Lizard\",\"kind\":\"NPC\",\"ids\":[],\"x\":3400,\"y\":3000,\"plane\":0}]}");
		ResolvedLocation l = resolver(d).forTask(task("{\"name\":\"Lizard\",\"taskID\":\"l\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"desert lizard#Yellow\"],\"npc_ids\":[460],\"quantity\":[1]}}"), 12850);
		assertEquals("NAME", l.getSource());
		assertEquals(3400, (int) l.getTile().getX());
	}

	@Test
	void noDataFallsBackToChunkCentreApproximate()
	{
		GpsData d = GpsData.empty(null);
		ResolvedLocation l = resolver(d).forTask(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"COOKING\"}"), 12850);
		assertTrue(l.isApproximate());
		assertEquals("CHUNK_CENTRE", l.getSource());
		assertEquals(TileMath.centre(12850), l.getTile());
	}

	@Test
	void nullTaskAndNullTargetFieldsFallBack()
	{
		LocationResolver r = resolver(dataWith("{\"schema\":1,\"entries\":[{\"name\":null,\"kind\":null,\"ids\":null,\"x\":1,\"y\":2,\"plane\":0}]}"));
		assertEquals("CHUNK_CENTRE", r.forTask(null, 12850).getSource());
		NuzlockeTask nullIds = task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc_ids\":[1]}}");
		nullIds.getTargetNpc().setNpcIds(Arrays.asList((Integer) null));
		assertEquals("CHUNK_CENTRE", r.forTask(nullIds, 12850).getSource());
		assertEquals("CHUNK_CENTRE", r.forName(null, 12850).getSource());
		assertEquals("CHUNK_CENTRE", r.forTask(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\"}"), 12850).getSource());
	}

	@Test
	void ambiguousMatchesPickClosestToHomeThenName()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":["
			+ "{\"name\":\"B\",\"kind\":\"NPC\",\"ids\":[5],\"x\":3232,\"y\":3232,\"plane\":1},"
			+ "{\"name\":\"A\",\"kind\":\"NPC\",\"ids\":[5],\"x\":3232,\"y\":3232,\"plane\":0},"
			+ "{\"name\":\"Far\",\"kind\":\"NPC\",\"ids\":[5],\"x\":100,\"y\":100,\"plane\":0}]}");
		NuzlockeTask t = task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"A\"],\"npc_ids\":[5],\"quantity\":[1]}}");
		// region 12850 = (50,50) -> centre (3232,3232)
		assertEquals(3232, (int) resolver(d).forTask(t, 12850).getTile().getX());
		assertEquals(0, resolver(d).forTask(t, 12850).getTile().getPlane());
		assertEquals(100, (int) resolver(d).forTask(t, 0x0101).getTile().getX());
	}

	@Test
	void questStepUsesKnownLocationElseQuestChunk()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full"));
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("TEST_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, null));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, h));
		assertEquals(3208, (int) r.forQuestStep("TEST_QUEST", 0).getTile().getX());
		ResolvedLocation s1 = r.forQuestStep("TEST_QUEST", 1);
		assertTrue(s1.isApproximate());
		assertEquals(TileMath.centre(12850), s1.getTile());
		assertEquals(TileMath.centre(12850), r.forQuestStep("TEST_QUEST", 99).getTile());
		assertEquals(TileMath.centre(12850), r.forQuestStep("TEST_QUEST", -1).getTile());
		assertNull(r.forQuestStep("NOPE", 0).getTile());
		assertNull(r.forQuestStep(null, 0).getTile());
		assertEquals("NONE", r.forQuestStep("NOPE", 0).getSource());
	}

	@Test
	void questStepWithoutChunksAndUnknownLocationIsUnroutable()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full"));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		assertNull(r.forQuestStep("TEST_QUEST", 1).getTile());
	}

	@Test
	void questCheckTaskUsesFirstStep()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full"));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		ResolvedLocation l = r.forTask(task("{\"name\":\"q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"TEST_QUEST\"}}"), 12850);
		assertEquals("QUEST_START", l.getSource());
		assertEquals(3208, (int) l.getTile().getX());
	}

	@Test
	void nearestBankPicksClosest()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Far Bank\",\"kind\":\"BANK\",\"ids\":[],\"x\":3000,\"y\":3000,\"plane\":0},{\"name\":\"Near Bank\",\"kind\":\"BANK\",\"ids\":[],\"x\":3205,\"y\":3205,\"plane\":0}]}");
		LocationResolver r = resolver(d);
		assertEquals(3205, (int) r.nearestBank(new Tile(3200, 3200, 0, false, null)).get().getTile().getX());
		assertFalse(r.nearestBank(null).isPresent());
		assertFalse(r.nearestBank(new Tile(null, null, 0, true, "NONE")).isPresent());
		assertFalse(resolver(GpsData.empty(null)).nearestBank(new Tile(1, 1, 0, false, null)).isPresent());
	}

	@Test
	void entriesWithoutCoordsAreNeverMatched()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":["
			+ "{\"name\":\"Ghost\",\"kind\":\"BANK\",\"ids\":[77]},"
			+ "{\"name\":\"Real Bank\",\"kind\":\"BANK\",\"ids\":[],\"x\":3000,\"y\":3000,\"plane\":0}]}");
		LocationResolver r = resolver(d);
		NuzlockeTask byId = task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"Ghost\"],\"npc_ids\":[77],\"quantity\":[1]}}");
		ResolvedLocation l = r.forTask(byId, 12850);
		assertEquals("CHUNK_CENTRE", l.getSource());
		assertEquals(TileMath.centre(12850), l.getTile());
		assertEquals("CHUNK_CENTRE", r.forName("Ghost", 12850).getSource());
		// (0,0) is nearest to the origin, but the coordinate-less bank must not win
		assertEquals(3000, (int) r.nearestBank(new Tile(0, 0, 0, false, null)).get().getTile().getX());
	}

	@Test
	void forNamePrefersExactOverCaseInsensitiveThenFallsBack()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":["
			+ "{\"name\":\"varrock\",\"kind\":\"TOWN\",\"ids\":[],\"x\":3232,\"y\":3232,\"plane\":0},"
			+ "{\"name\":\"Varrock\",\"kind\":\"TOWN\",\"ids\":[],\"x\":100,\"y\":100,\"plane\":0}]}");
		LocationResolver r = resolver(d);
		// exact match wins even though the lower-case entry is closer to home
		assertEquals(100, (int) r.forName("Varrock", 12850).getTile().getX());
		assertEquals(3232, (int) r.forName("varrock", 12850).getTile().getX());
		// no exact entry: case-insensitive match
		ResolvedLocation ci = r.forName("VARROCK", 12850);
		assertEquals("NAME", ci.getSource());
		assertFalse(ci.isApproximate());
		assertEquals(3232, (int) ci.getTile().getX());
		ResolvedLocation none = r.forName("Nowhere", 12850);
		assertEquals("CHUNK_CENTRE", none.getSource());
		assertTrue(none.isApproximate());
		assertEquals(TileMath.centre(12850), none.getTile());
	}

	@Test
	void questCheckWithUnknownQuestFallsBackToChunkCentre()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full"));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		ResolvedLocation l = r.forTask(task("{\"name\":\"q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"NOPE\"}}"), 12850);
		assertEquals("CHUNK_CENTRE", l.getSource());
		assertTrue(l.isApproximate());
		assertEquals(TileMath.centre(12850), l.getTile());
	}

	@Test
	void questStartIsApproximateWhenStepZeroFallsBackToQuestChunk() throws Exception
	{
		Path dir = Files.createTempDirectory("gps-quest");
		dir.toFile().deleteOnExit();
		Path f = dir.resolve("quests.json");
		f.toFile().deleteOnExit();
		Files.write(f, ("{\"schema\":1,\"quests\":[{\"id\":\"Q0\",\"name\":\"Q0\",\"steps\":[{\"index\":0,\"text\":\"t\",\"kind\":\"OTHER\","
			+ "\"location\":{\"x\":null,\"y\":null,\"plane\":0,\"approximate\":true,\"source\":\"NONE\"},\"items\":[]}]}]}").getBytes("UTF-8"));
		GpsData d = GpsDataStore.load(dir);
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("Q0", new QuestHelperEntry(Arrays.asList(12850), null, null));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, h));
		ResolvedLocation l = r.forTask(task("{\"name\":\"q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"Q0\"}}"), 1);
		assertEquals("QUEST_START", l.getSource());
		assertTrue(l.isApproximate());
		assertEquals(TileMath.centre(12850), l.getTile());
	}

	@Test
	void nearestBankTieBreaksByNameAndKindIsCaseInsensitive()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":["
			+ "{\"name\":\"Zed Bank\",\"kind\":\"bank\",\"ids\":[],\"x\":3210,\"y\":3200,\"plane\":0},"
			+ "{\"name\":\"Alpha Bank\",\"kind\":\"Bank\",\"ids\":[],\"x\":3190,\"y\":3200,\"plane\":0}]}");
		ResolvedLocation l = resolver(d).nearestBank(new Tile(3200, 3200, 0, false, null)).get();
		assertEquals("BANK", l.getSource());
		assertEquals(3190, (int) l.getTile().getX());
	}

	@Test
	void nameMatchUsesCaseInsensitiveLookup()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Goblin\",\"kind\":\"NPC\",\"ids\":[],\"x\":3100,\"y\":3100,\"plane\":0}]}");
		ResolvedLocation l = resolver(d).forTask(task("{\"name\":\"g\",\"taskID\":\"g\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"GOBLIN\"],\"npc_ids\":[1],\"quantity\":[1]}}"), 12850);
		assertEquals("NAME", l.getSource());
		assertEquals(3100, (int) l.getTile().getX());
	}
}
