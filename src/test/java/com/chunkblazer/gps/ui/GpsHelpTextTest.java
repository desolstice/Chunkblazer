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
package com.chunkblazer.gps.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.data.GpsDataStatus;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GpsHelpTextTest
{
	@Test
	void missingDirectoryMentionsPathAndSetting()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("C:/x/gps"), false, new ArrayList<>(), new ArrayList<>(), null);
		String t = GpsHelpText.forStatus(st, Paths.get("C:/x/gps"));
		assertTrue(t.contains("not found"));
		assertTrue(t.contains("GPS data folder"));
		assertTrue(t.contains("gps"));
	}

	@Test
	void completeDataNeedsNoHelp()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), true, new ArrayList<>(), new ArrayList<>(), "v1");
		assertEquals("", GpsHelpText.forStatus(st, Paths.get("x")));
	}

	@Test
	void partialDataListsMissingAndInvalid()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), true, Arrays.asList("transport.json"), Arrays.asList("items.json"), null);
		String t = GpsHelpText.forStatus(st, Paths.get("x"));
		assertTrue(t.contains("transport.json"));
		assertTrue(t.contains("items.json"));
	}

	@Test
	void nullStatusAndNullFolderDoNotThrow()
	{
		assertEquals("", GpsHelpText.forStatus(null, null));
		GpsDataStatus st = new GpsDataStatus(null, false, null, null, null);
		assertTrue(GpsHelpText.forStatus(st, null).contains("default"));
	}
}
