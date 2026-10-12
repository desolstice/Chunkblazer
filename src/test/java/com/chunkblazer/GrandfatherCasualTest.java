/*
 * Copyright (c) 2026, btwinnn
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

package com.chunkblazer;

import com.chunkblazer.api.EligibilitySnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

/**
 * Accounts with progress from before a mode was required are set to Casual so they keep
 * playing, unless they could still go Competitive, in which case the choice stays theirs.
 */
@ExtendWith(MockitoExtension.class)
class GrandfatherCasualTest
{
	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	@Mock
	private Client client;

	@Mock
	private Player player;

	@Mock
	private ClientThread clientThread;

	private Map<String, String> writes;
	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		set("config", config);
		set("configManager", configManager);
		set("client", client);
		set("clientThread", clientThread);
		writes = RsProfileTestSupport.install(configManager, config);
		lenient().when(client.getLocalPlayer()).thenReturn(player);
		lenient().when(player.getName()).thenReturn("Tester");
		writes.put("completedTasks", "talk_to_hans");
		account(0, 40, 20, 5);
	}

	/** Account type (0 = main, 1-3 = ironmen), combat level, every skill's level, quest points. */
	private void account(int type, int combat, int skillLevel, int questPoints)
	{
		lenient().when(client.getVarbitValue(anyInt())).thenReturn(type);
		lenient().when(client.getVarpValue(anyInt())).thenReturn(questPoints);
		lenient().when(player.getCombatLevel()).thenReturn(combat);
		lenient().when(client.getRealSkillLevel(any(Skill.class))).thenReturn(skillLevel);
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void grandfather() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("grandfatherCasual");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	private boolean lockedCasual()
	{
		String hash = writes.get("accountModeHash");
		return hash != null && hash.endsWith(":CASUAL");
	}

	@Test
	void aMainWithProgressIsSetToCasual() throws Exception
	{
		grandfather();
		assertTrue(lockedCasual());
		assertTrue(plugin.isModeLocked());
	}

	@Test
	void noFreeChunkIsUnlockedForTheGrandfatheredAccount() throws Exception
	{
		String before = writes.get("unlockedChunks");
		grandfather();
		assertTrue(lockedCasual());
		assertEquals(before, writes.get("unlockedChunks"), "Casual's free start chunk is for new accounts only");
	}

	@Test
	void anAccountThatCouldStillGoCompetitiveKeepsTheChoice() throws Exception
	{
		account(1, 3, 1, 1);
		grandfather();
		assertNull(writes.get("accountModeHash"));
	}

	@Test
	void anAccountWithNoProgressIsLeftToPick() throws Exception
	{
		writes.put("completedTasks", "");
		grandfather();
		assertNull(writes.get("accountModeHash"));
	}

	@Test
	void waitsForTheServerStateWhenSyncing() throws Exception
	{
		lenient().when(config.apiEnabled()).thenReturn(true);
		grandfather();
		assertNull(writes.get("accountModeHash"), "the server may still hand this account its mode");

		set("serverStateMerged", true);
		grandfather();
		assertTrue(lockedCasual());
	}

	@Test
	void aCompetitiveLockInProgressIsLeftAlone() throws Exception
	{
		set("pendingCompetitiveLock", true);
		grandfather();
		assertNull(writes.get("accountModeHash"));
	}

	private static EligibilitySnapshot snapshot(Integer type, int combat, int qp, int hp, int other)
	{
		Map<String, Integer> skills = new HashMap<>();
		for (Skill s : Skill.values())
		{
			skills.put(s.name(), s == Skill.HITPOINTS ? hp : other);
		}
		return EligibilitySnapshot.builder().accountType(type).combatLevel(combat).questPoints(qp)
			.skills(skills).build();
	}

	@Test
	void competitiveMirrorMatchesTheServerRule()
	{
		assertTrue(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(1, 3, 1, 10, 1)), "fresh ironman");
		assertTrue(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(3, 9, 1, 15, 3)), "at every limit");
		assertTrue(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(null, 3, 1, 10, 1)), "type unknown");
		assertFalse(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(0, 3, 1, 10, 1)), "a main");
		assertFalse(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(1, 10, 1, 10, 1)), "combat 10");
		assertFalse(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(1, 3, 2, 10, 1)), "a real quest");
		assertFalse(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(1, 3, 1, 16, 1)), "Hitpoints 16");
		assertFalse(ChunkBlazerPlugin.couldStillGoCompetitive(snapshot(1, 3, 1, 10, 4)), "a skill at 4");
	}
}
