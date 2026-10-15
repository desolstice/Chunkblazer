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
package com.chunkblazer.gps.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpsDataStoreTest
{
	private static Path fixture(String name) throws Exception
	{
		return Paths.get(GpsDataStoreTest.class.getResource("/com/chunkblazer/gps/data/" + name).toURI());
	}

	@Test
	void loadsAllFilesFromFullFixture() throws Exception
	{
		GpsData d = GpsDataStore.load(fixture("full"));
		assertTrue(d.getStatus().isComplete());
		assertEquals("test-1", d.getDataVersion());
		assertEquals(8, d.getMonsters().get(0).getHp());
		assertEquals("TEST_QUEST", d.getQuests().get(0).getId());
		assertEquals(Integer.valueOf(10), d.getQuests().get(0).getRequirements().getSkills().get("COOKING"));
		assertEquals(Integer.valueOf(300), d.getQuests().get(0).getRewards().getXp().get("COOKING"));
		assertEquals("SPELL", d.getTransport().get(0).getKind());
		assertNull(d.getTransport().get(0).getFrom());
		assertEquals(3208, d.getLocations().get(0).getX());
		assertEquals(1, d.getTraining().size());
		assertTrue(d.getItems().get(0).getRequirements().getQuests().isEmpty());
		assertNull(d.getItems().get(0).getTeleport());
	}

	@Test
	void stepWithoutLocationIsNotKnown() throws Exception
	{
		GpsData d = GpsDataStore.load(fixture("full"));
		assertTrue(d.getQuests().get(0).getSteps().get(0).getLocation().isKnown());
		assertFalse(d.getQuests().get(0).getSteps().get(1).getLocation().isKnown());
	}

	@Test
	void absentCollectionsNeverNull() throws Exception
	{
		Path tmp = Files.createTempDirectory("gps");
		Files.write(tmp.resolve("quests.json"), "{\"quests\":[{\"id\":\"A\"}]}".getBytes("UTF-8"));
		GpsData d = GpsDataStore.load(tmp);
		QuestDef q = d.getQuests().get(0);
		assertTrue(q.getSteps().isEmpty());
		assertTrue(q.getRewards().getXp().isEmpty());
		assertTrue(q.getRequirements().getItems().isEmpty());
	}

	@Test
	void missingDirectoryReturnsEmptyDataAndStatusNeverThrows(@TempDir Path tmp)
	{
		GpsData d = GpsDataStore.load(tmp.resolve("does-not-exist"));
		assertFalse(d.getStatus().isDirectoryFound());
		assertTrue(d.getMonsters().isEmpty());
		assertTrue(d.getQuests().isEmpty());
		assertTrue(d.getStatus().describe().contains("not found"));
	}

	@Test
	void emptyDirectoryListsEveryFileMissing(@TempDir Path tmp)
	{
		GpsData d = GpsDataStore.load(tmp);
		assertTrue(d.getStatus().isDirectoryFound());
		assertEquals(6, d.getStatus().getMissingFiles().size());
		assertFalse(d.getStatus().isComplete());
	}

	@Test
	void invalidJsonFileIsReportedOthersStillLoad(@TempDir Path tmp) throws Exception
	{
		Files.copy(fixture("full/monsters.json"), tmp.resolve("monsters.json"));
		Files.copy(fixture("invalid/items.json"), tmp.resolve("items.json"));
		GpsData d = GpsDataStore.load(tmp);
		assertEquals(1, d.getMonsters().size());
		assertTrue(d.getItems().isEmpty());
		assertTrue(d.getStatus().getInvalidFiles().contains("items.json"));
	}

	@Test
	void emptyFileIsInvalidNotCrash(@TempDir Path tmp) throws Exception
	{
		Files.write(tmp.resolve("quests.json"), new byte[0]);
		GpsData d = GpsDataStore.load(tmp);
		assertTrue(d.getQuests().isEmpty());
		assertTrue(d.getStatus().getInvalidFiles().contains("quests.json"));
	}
}
