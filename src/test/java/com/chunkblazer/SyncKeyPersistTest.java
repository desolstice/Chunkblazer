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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The server sends an account's sync key only on its first login, so a key that can't be
 * saved the moment it arrives must be held and saved later, and only into that account
 * (HevansBlazer: the key was dropped, and every later session's sync was refused).
 */
@ExtendWith(MockitoExtension.class)
class SyncKeyPersistTest
{
	private static final String KEY = "11111111-2222-3333-4444-555555555555";
	private static final String ACCOUNT = "account-a";
	private static final String OTHER_ACCOUNT = "account-b";

	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	@Mock
	private ScheduledExecutorService executorService;

	private Map<String, String> writes;
	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		set("config", config);
		set("configManager", configManager);
		set("executorService", executorService);
		writes = RsProfileTestSupport.install(configManager, config);
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private Object get(String name) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private void storeReady(boolean ready)
	{
		lenient().when(configManager.getRSProfileKey()).thenReturn(ready ? "test-profile" : null);
	}

	@Test
	void savesAndFlushesRightAwayWhenTheStoreIsReady() throws Exception
	{
		plugin.persistApiKey(KEY, ACCOUNT);

		assertEquals(KEY, writes.get("apiKey"));
		verify(executorService).execute(any());
		assertNull(get("unsavedApiKey"));
	}

	@Test
	void holdsTheKeyUntilTheStoreIsReadyThenSavesIt() throws Exception
	{
		storeReady(false);
		plugin.persistApiKey(KEY, ACCOUNT);
		assertNull(writes.get("apiKey"), "nothing to save into yet");
		assertEquals(KEY, get("unsavedApiKey"));

		storeReady(true);
		plugin.retryUnsavedApiKey(ACCOUNT);

		assertEquals(KEY, writes.get("apiKey"));
		assertNull(get("unsavedApiKey"));
	}

	@Test
	void staysHeldWhileTheStoreIsStillNotReady() throws Exception
	{
		storeReady(false);
		plugin.persistApiKey(KEY, ACCOUNT);
		plugin.retryUnsavedApiKey(ACCOUNT);
		plugin.retryUnsavedApiKey(OTHER_ACCOUNT);

		assertEquals(KEY, get("unsavedApiKey"), "not dropped before there is anywhere to save it");
	}

	@Test
	void neverSavesTheKeyIntoADifferentAccount() throws Exception
	{
		storeReady(false);
		plugin.persistApiKey(KEY, ACCOUNT);

		storeReady(true);
		plugin.retryUnsavedApiKey(OTHER_ACCOUNT);

		assertNull(writes.get("apiKey"), "another account must never get this key");
		assertNull(get("unsavedApiKey"));
		plugin.retryUnsavedApiKey(ACCOUNT);
		assertNull(writes.get("apiKey"), "dropped, not re-armed");
		verify(executorService, never()).execute(any());
	}

	@Test
	void aKeyWithNoKnownAccountIsNeverSaved() throws Exception
	{
		storeReady(false);
		plugin.persistApiKey(KEY, null);

		storeReady(true);
		plugin.retryUnsavedApiKey(ACCOUNT);

		assertNull(writes.get("apiKey"));
	}

	@Test
	void resettingLocalDataForgetsAHeldKey() throws Exception
	{
		storeReady(false);
		plugin.persistApiKey(KEY, ACCOUNT);

		storeReady(true);
		plugin.resetAccountLocalData();

		assertNull(get("unsavedApiKey"));
		plugin.retryUnsavedApiKey(ACCOUNT);
		assertNull(writes.get("apiKey"));
	}
}
