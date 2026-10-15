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

import com.chunkblazer.NuzlockeChunk;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPickerModelTest
{
	private ChunkPickerModel model()
	{
		return ChunkPickerModel.of(Arrays.asList(
			new ChunkEntry(12850, "Lumbridge", "Misthalin"),
			new ChunkEntry(12853, "Varrock", "Misthalin"),
			new ChunkEntry(11828, "Falador", "Asgarnia")));
	}

	@Test
	void filterByTextAndArea()
	{
		ChunkPickerModel m = model();
		assertEquals(1, m.filter("lumb", null).size());
		assertEquals(2, m.filter("", "Misthalin").size());
		assertEquals(1, m.filter("12853", "All").size());
		assertEquals(0, m.filter("zzz", null).size());
	}

	@Test
	void areasSortedUnique()
	{
		assertEquals(Arrays.asList("Asgarnia", "Misthalin"), model().areas());
	}

	@Test
	void selectionAndSelectAllVisible()
	{
		ChunkPickerModel m = model();
		m.set(12850, true);
		assertEquals(Collections.singleton(12850), m.selected());
		m.setAll(m.filter("", "Misthalin"), true);
		assertEquals(new HashSet<>(Arrays.asList(12850, 12853)), m.selected());
		m.setAll(m.filter("", "Misthalin"), false);
		assertTrue(m.selected().isEmpty());
	}

	@Test
	void seedFromRealIgnoresUnknownRegions()
	{
		ChunkPickerModel m = model();
		m.selectAreaFrom(new HashSet<>(Arrays.asList(12850, 99999)));
		assertEquals(Collections.singleton(12850), m.selected());
	}

	@Test
	void duplicateRegionEntriesCollapse()
	{
		ChunkPickerModel m = ChunkPickerModel.of(Arrays.asList(new ChunkEntry(1, "A", "X"), new ChunkEntry(1, "A", "X")));
		assertEquals(1, m.count());
	}

	@Test
	void singleRegionChunkKeepsPlainName()
	{
		NuzlockeChunk c = new NuzlockeChunk();
		c.setRegionIds(Collections.singletonList(12850));
		c.setFriendlyName("Lumbridge");
		ChunkPickerModel m = ChunkPickerModel.fromChunks(Collections.singletonList(c));
		assertEquals("Lumbridge", m.filter("", null).get(0).getName());
	}

	@Test
	void ofNullBehavesAsEmpty()
	{
		assertEquals(0, ChunkPickerModel.of(null).count());
	}

	@Test
	void fromChunksOneEntryPerRegionWithFallbackName()
	{
		NuzlockeChunk named = new NuzlockeChunk();
		named.setRegionIds(Arrays.asList(12850, 12851));
		named.setFriendlyName("Lumbridge");
		named.setArea("Misthalin");
		NuzlockeChunk unnamed = new NuzlockeChunk();
		unnamed.setRegionIds(Collections.singletonList(11828));
		unnamed.setArea("Asgarnia");

		ChunkPickerModel m = ChunkPickerModel.fromChunks(Arrays.asList(named, unnamed));
		assertEquals(3, m.count());
		assertEquals("Lumbridge (12851)", m.filter("12851", null).get(0).getName());
		assertEquals("Lumbridge (12850)", m.filter("12850", null).get(0).getName());
		assertEquals("Region 11828", m.filter("11828", null).get(0).getName());
		assertEquals(Arrays.asList("Asgarnia", "Misthalin"), m.areas());
	}
}
