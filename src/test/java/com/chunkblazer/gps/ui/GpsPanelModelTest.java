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

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.eligibility.Eligibility;
import com.chunkblazer.gps.eligibility.EligibilityEngine;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GpsPanelModelTest
{
	private static NuzlockeTask task(String id, String type, int level, int points)
	{
		return new Gson().fromJson("{\"name\":\"" + id + "\",\"taskID\":\"" + id + "\",\"completion_type\":\"" + type + "\",\"level\":" + level + ",\"base_points\":" + points + "}", NuzlockeTask.class);
	}

	private GpsPanelModel model(List<NuzlockeTask> tasks)
	{
		GpsData d = GpsData.empty(null);
		QuestCatalog q = QuestCatalog.build(d, QuestHelperEntry.loadFromResource());
		return new GpsPanelModel(new EligibilityEngine(new RequirementDeriver(q, Overrides.empty(), d), q, d), tasks);
	}

	private static List<String> ids(List<GpsPanelModel.Row> rows)
	{
		return rows.stream().map(r -> r.getTask().getTaskId()).collect(Collectors.toList());
	}

	@Test
	void doableFirstThenBlockedAndPointsDescending()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1), task("c", "COOKING", 1, 5));
		List<GpsPanelModel.Row> rows = model(ts).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all());
		assertEquals(Arrays.asList("c", "b", "a"), rows.stream().map(r -> r.getTask().getTaskId()).collect(Collectors.toList()));
	}

	@Test
	void filtersApply()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1));
		GpsPanelModel.Filter f = GpsPanelModel.Filter.all();
		f.setShowBlocked(false);
		assertEquals(1, model(ts).rows(PlayerState.freshAccount(), f).size());
		f = GpsPanelModel.Filter.all();
		f.setText("a");
		assertEquals(1, model(ts).rows(PlayerState.freshAccount(), f).size());
	}

	@Test
	void summaryCounts()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1));
		GpsPanelModel m = model(ts);
		GpsPanelModel.Summary s = m.summary(m.rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all()));
		assertEquals(1, s.getDoable());
		assertEquals(1, s.getBlocked());
	}

	@Test
	void emptyTaskListIsFine()
	{
		assertTrue(model(Collections.emptyList()).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all()).isEmpty());
	}

	@Test
	void unknownSortsBetweenDoableAndBlocked()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("blocked", "COOKING", 50, 99), task("unk", "VARBIT_CHECK", 0, 50), task("ok", "COOKING", 1, 1));
		GpsPanelModel m = model(ts);
		List<GpsPanelModel.Row> rows = m.rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all());
		assertEquals(Arrays.asList("ok", "unk", "blocked"), ids(rows));
		assertEquals(Eligibility.Status.UNKNOWN, rows.get(1).getEligibility().getStatus());
		GpsPanelModel.Summary s = m.summary(rows);
		assertEquals(1, s.getUnknown());
	}

	@Test
	void textFilterMatchesTaskIdCaseInsensitively()
	{
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"Cook shrimp\",\"taskID\":\"cook_SHRIMP_01\",\"completion_type\":\"COOKING\",\"level\":1,\"base_points\":1}", NuzlockeTask.class);
		GpsPanelModel.Filter f = GpsPanelModel.Filter.all();
		f.setText("shrimp_01");
		assertEquals(1, model(Arrays.asList(t, task("b", "COOKING", 1, 1))).rows(PlayerState.freshAccount(), f).size());
		f.setText("COOK SHRIMP");
		assertEquals(1, model(Arrays.asList(t)).rows(PlayerState.freshAccount(), f).size());
		f.setText("   ");
		assertEquals(2, model(Arrays.asList(t, task("b", "COOKING", 1, 1))).rows(PlayerState.freshAccount(), f).size());
	}

	@Test
	void tieBreakIsDeterministic()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("z", "COOKING", 1, 5), task("m", "COOKING", 1, 5), task("a", "COOKING", 1, 5));
		assertEquals(Arrays.asList("a", "m", "z"), ids(model(ts).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all())));
		assertEquals(Arrays.asList("a", "m", "z"), ids(model(Arrays.asList(ts.get(2), ts.get(0), ts.get(1))).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all())));
	}

	@Test
	void questRowsCoverCatalogDoableFirstThenName()
	{
		GpsPanelModel m = model(Collections.emptyList());
		List<GpsPanelModel.QuestRow> rows = m.questRows(PlayerState.freshAccount());
		assertTrue(!rows.isEmpty());
		GpsPanelModel.QuestRow am = rows.stream().filter(r -> r.getId().equals("ANIMAL_MAGNETISM")).findFirst().get();
		assertEquals(Eligibility.Status.BLOCKED, am.getEligibility().getStatus());
		int lastRank = -1;
		String lastName = "";
		for (GpsPanelModel.QuestRow r : rows)
		{
			int rank = r.getEligibility().getStatus() == Eligibility.Status.DOABLE ? 0 : r.getEligibility().getStatus() == Eligibility.Status.UNKNOWN ? 1 : 2;
			assertTrue(rank >= lastRank);
			if (rank == lastRank)
			{
				assertTrue(r.getName().compareToIgnoreCase(lastName) >= 0);
			}
			lastRank = rank;
			lastName = r.getName();
		}
	}
}
