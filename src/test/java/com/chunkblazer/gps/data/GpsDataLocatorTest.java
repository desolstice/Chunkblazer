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

import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpsDataLocatorTest
{
	@Test
	void blankUsesDefault()
	{
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir(""));
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir("   "));
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir(null));
	}

	@Test
	void explicitPathIsUsedAndTildeExpanded()
	{
		assertEquals(Paths.get("D:/x/gps"), GpsDataLocator.resolveDir(" D:/x/gps "));
		assertEquals(Paths.get(System.getProperty("user.home"), "gps"), GpsDataLocator.resolveDir("~/gps"));
	}

	@Test
	void loadOfMissingDirDoesNotThrow()
	{
		assertFalse(GpsDataLocator.load("Z:/definitely/not/here").getStatus().isDirectoryFound());
	}

	@Test
	void invalidPathCharactersNeverThrow()
	{
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir("a\0b"));
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.load("a\0b").getStatus().getDir());
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.load("~/a\0b").getStatus().getDir());
	}

	@Test
	void bareTildeResolvesToHome()
	{
		Path home = Paths.get(System.getProperty("user.home"));
		assertEquals(home, GpsDataLocator.resolveDir("~"));
		assertEquals(home, GpsDataLocator.resolveDir("~/"));
		assertEquals(home, GpsDataLocator.resolveDir("~\\"));
	}

	@Test
	void regularFileAsDirDoesNotThrow(@TempDir Path tmp) throws Exception
	{
		Path f = tmp.resolve("f");
		Files.write(f, new byte[] {1});
		assertFalse(GpsDataLocator.load(f.toString()).getStatus().isDirectoryFound());
	}
}
