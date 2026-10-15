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

import com.chunkblazer.gps.data.GpsDataStatus;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.plan.Plan;
import com.chunkblazer.gps.plan.PlanStep;
import com.chunkblazer.gps.travel.TravelConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Value;

/** Pure (Swing free) conversion of a {@link Plan} into display rows. */
public final class PlanViewModel
{
	public enum Kind
	{
		STEP, QUEST_STEP, TRAINING, ACQUIRE, UNPLANNABLE, INFO
	}

	@Value
	public static class Row
	{
		int order;
		String title;
		String subtitle;
		String badge;
		String travelText;
		boolean approximate;
		boolean unplannable;
		Kind kind;
	}

	@Value
	public static class View
	{
		List<Row> rows;
		String header;
		String footer;
	}

	static final String EMPTY_TEXT = "Nothing to plan: complete a goal, unlock chunks, or change the goal.";
	static final String ONLY_BLOCKED_TEXT = "No plannable steps; see blocked items below.";
	private static final String APPROX_SUFFIX = " (location approximate)";

	private PlanViewModel()
	{
	}

	public static View build(Plan plan, GpsDataStatus status, String dataVersion)
	{
		return build(plan, status, dataVersion, null);
	}

	/** @param questName optional id to display name lookup; null or a null result falls back to the title-cased id */
	public static View build(Plan plan, GpsDataStatus status, String dataVersion, Function<String, String> questName)
	{
		List<PlanStep> steps = plan == null || plan.getSteps() == null ? new ArrayList<>() : plan.getSteps();
		List<Plan.Unplannable> blocked = plan == null || plan.getUnplannable() == null ? new ArrayList<>() : plan.getUnplannable();

		Map<String, Integer> questCount = new HashMap<>();
		Map<String, Integer> questTotal = new HashMap<>();
		for (PlanStep s : steps)
		{
			if (s.getQuestId() != null)
			{
				questCount.merge(s.getQuestId(), 1, Integer::sum);
				int idx = s.getStepIndex() == null ? 0 : s.getStepIndex();
				questTotal.merge(s.getQuestId(), idx + 1, Math::max);
			}
		}

		List<Row> rows = new ArrayList<>();
		if (steps.isEmpty())
		{
			rows.add(info(blocked.isEmpty() ? EMPTY_TEXT : ONLY_BLOCKED_TEXT));
		}
		for (PlanStep s : steps)
		{
			String badge = null;
			Kind kind = kindOf(s);
			if (s.getQuestId() != null)
			{
				int count = questCount.get(s.getQuestId());
				int total = Math.max(count, questTotal.get(s.getQuestId()));
				badge = displayName(s.getQuestId(), questName) + " " + count + "/" + total;
			}
			String reason = s.getReason() == null ? "" : s.getReason();
			String subtitle = s.isApproximate() ? reason + APPROX_SUFFIX : reason;
			rows.add(new Row(s.getOrder(), s.getLabel(), subtitle.trim(), badge, formatTicks(s.getTravelTicks()),
				s.isApproximate(), false, kind));
		}
		int order = steps.size();
		for (Plan.Unplannable u : blocked)
		{
			String missing = u.getMissing() == null ? "" : u.getMissing().stream()
				.map(Missing::getText).collect(Collectors.joining("; "));
			rows.add(new Row(++order, u.getLabel(), missing, null, "", false, true, Kind.UNPLANNABLE));
		}

		int total = plan == null ? 0 : plan.getTotalTravelTicks();
		String header = steps.size() + (steps.size() == 1 ? " step" : " steps") + ", travel ~" + formatTicks(total);
		return new View(rows, header, footer(status, dataVersion));
	}

	private static String footer(GpsDataStatus status, String dataVersion)
	{
		if (status != null && !status.isComplete())
		{
			return status.describe();
		}
		return dataVersion == null ? "GPS data version unknown" : "GPS data " + dataVersion;
	}

	private static Row info(String text)
	{
		return new Row(0, text, "", null, "", false, false, Kind.INFO);
	}

	private static Kind kindOf(PlanStep s)
	{
		if (s.getType() == null)
		{
			return Kind.STEP;
		}
		switch (s.getType())
		{
			case QUEST_STEP:
				return Kind.QUEST_STEP;
			case TRAINING:
				return Kind.TRAINING;
			case ACQUIRE_ITEM:
			case ACQUIRE_TELEPORT:
				return Kind.ACQUIRE;
			default:
				return Kind.STEP;
		}
	}

	private static String displayName(String id, Function<String, String> questName)
	{
		String name = questName == null ? null : questName.apply(id);
		if (name != null && !name.isEmpty())
		{
			return name;
		}
		StringBuilder sb = new StringBuilder();
		for (String w : id.toLowerCase(Locale.ROOT).split("_"))
		{
			if (w.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}

	/** "0s", "12s", "1m 05s", "1h 02m"; {@link TravelConfig#INF} is "unreachable". */
	public static String formatTicks(int ticks)
	{
		if (ticks >= TravelConfig.INF)
		{
			return "unreachable";
		}
		long seconds = Math.round(Math.max(0, ticks) * 0.6);
		if (seconds < 60)
		{
			return seconds + "s";
		}
		if (seconds < 3600)
		{
			return (seconds / 60) + "m " + String.format(Locale.ROOT, "%02d", seconds % 60) + "s";
		}
		return (seconds / 3600) + "h " + String.format(Locale.ROOT, "%02d", (seconds % 3600) / 60) + "m";
	}
}
