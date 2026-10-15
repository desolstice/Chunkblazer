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

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.eligibility.Eligibility;
import com.chunkblazer.gps.eligibility.EligibilityEngine;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import lombok.Value;

/** Pure (Swing and RuneLite free) model behind the GPS panel: evaluates, filters and orders rows. */
public final class GpsPanelModel
{
	/** One evaluated task. */
	@Value
	public static class Row
	{
		NuzlockeTask task;
		Eligibility eligibility;
	}

	/** One evaluated quest. */
	@Value
	public static class QuestRow
	{
		String id;
		String name;
		Eligibility eligibility;
	}

	@Data
	public static class Filter
	{
		private boolean showDoable = true;
		private boolean showBlocked = true;
		private boolean showUnknown = true;
		private String text;

		public static Filter all()
		{
			return new Filter();
		}

		boolean allows(Eligibility.Status status)
		{
			switch (status)
			{
				case DOABLE:
					return showDoable;
				case BLOCKED:
					return showBlocked;
				default:
					return showUnknown;
			}
		}
	}

	@Value
	public static class Summary
	{
		int doable;
		int blocked;
		int unknown;
	}

	private final EligibilityEngine engine;
	private final List<NuzlockeTask> tasks;

	public GpsPanelModel(EligibilityEngine engine, List<NuzlockeTask> tasks)
	{
		this.engine = engine;
		this.tasks = new ArrayList<>(tasks);
	}

	private static int rank(Eligibility.Status s)
	{
		switch (s)
		{
			case DOABLE:
				return 0;
			case UNKNOWN:
				return 1;
			default:
				return 2;
		}
	}

	private static String nz(String s)
	{
		return s == null ? "" : s;
	}

	public List<Row> rows(PlayerState state, Filter filter)
	{
		Filter f = filter == null ? Filter.all() : filter;
		String needle = f.getText() == null ? "" : f.getText().trim().toLowerCase(Locale.ROOT);
		List<Row> out = new ArrayList<>();
		for (NuzlockeTask t : tasks)
		{
			if (!needle.isEmpty()
				&& !nz(t.getName()).toLowerCase(Locale.ROOT).contains(needle)
				&& !nz(t.getTaskId()).toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}
			Eligibility e = engine.evaluate(t, state);
			if (f.allows(e.getStatus()))
			{
				out.add(new Row(t, e));
			}
		}
		out.sort(Comparator
			.<Row>comparingInt(r -> rank(r.getEligibility().getStatus()))
			.thenComparing(Comparator.<Row>comparingInt(r -> r.getTask().getBasePoints()).reversed())
			.thenComparing(r -> nz(r.getTask().getName()))
			.thenComparing(r -> nz(r.getTask().getTaskId())));
		return out;
	}

	public Summary summary(List<Row> rows)
	{
		int d = 0;
		int b = 0;
		int u = 0;
		for (Row r : rows)
		{
			switch (r.getEligibility().getStatus())
			{
				case DOABLE:
					d++;
					break;
				case BLOCKED:
					b++;
					break;
				default:
					u++;
			}
		}
		return new Summary(d, b, u);
	}

	/** Every quest of the catalog, doable first then by name. */
	public List<QuestRow> questRows(PlayerState state)
	{
		List<QuestRow> out = new ArrayList<>();
		QuestCatalog catalog = engine.getQuests();
		for (QuestModel q : catalog.all())
		{
			out.add(new QuestRow(q.getId(), q.getName(), engine.evaluateQuest(q.getId(), state)));
		}
		out.sort(Comparator
			.<QuestRow>comparingInt(r -> rank(r.getEligibility().getStatus()))
			.thenComparing(r -> nz(r.getName()).toLowerCase(Locale.ROOT))
			.thenComparing(QuestRow::getId));
		return out;
	}
}
