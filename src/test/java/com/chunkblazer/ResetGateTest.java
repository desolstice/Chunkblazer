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

import com.chunkblazer.api.ChunkBlazerApiClient;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The reset link restores from the server, so it must refuse while the server copy may
 * be behind: sync off, sync refused, or no recent successful sync (Blaze Locked reset
 * while sync was refused and lost two days of progress).
 */
@ExtendWith(MockitoExtension.class)
class ResetGateTest
{
	private static final long NOW = 10_000_000L;
	private static final String KEY = "11111111-2222-3333-4444-555555555555";

	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ChunkBlazerApiClient apiClient;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		set("config", config);
		set("apiClient", apiClient);
		lenient().when(config.apiEnabled()).thenReturn(true);
		lenient().when(apiClient.currentApiKey()).thenReturn(KEY);
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	@Test
	void allowedRightAfterASuccessfulSync() throws Exception
	{
		set("lastSyncSuccessMs", NOW - 30_000L);
		assertNull(plugin.resetBlockedReason(NOW));
	}

	@Test
	void blockedWhenSyncIsOff() throws Exception
	{
		lenient().when(config.apiEnabled()).thenReturn(false);
		set("lastSyncSuccessMs", NOW - 30_000L);
		String reason = plugin.resetBlockedReason(NOW);
		assertNotNull(reason);
		assertTrue(reason.contains("only exists on this computer"));
	}

	@Test
	void blockedWithNoKey() throws Exception
	{
		lenient().when(apiClient.currentApiKey()).thenReturn(null);
		set("lastSyncSuccessMs", NOW - 30_000L);
		assertNotNull(plugin.resetBlockedReason(NOW));
	}

	@Test
	void blockedWhileTheServerRefusesTheKey() throws Exception
	{
		set("syncRejectedKey", KEY);
		set("lastSyncSuccessMs", NOW - 30_000L);
		assertNotNull(plugin.resetBlockedReason(NOW));
	}

	@Test
	void blockedBeforeAnySyncThisSession()
	{
		assertNotNull(plugin.resetBlockedReason(NOW));
	}

	@Test
	void blockedWhenTheLastSyncIsStale() throws Exception
	{
		set("lastSyncSuccessMs", NOW - 5 * 60_000L);
		assertNotNull(plugin.resetBlockedReason(NOW));
	}
}
