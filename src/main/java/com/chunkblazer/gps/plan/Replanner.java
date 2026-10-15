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
package com.chunkblazer.gps.plan;

import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.PlayerState;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Re-runs the planner after progress: a new player state and position, minus the nodes already done. Training and
 * acquisition nodes in {@code doneNodeIds} are ignored because the new state already reflects them.
 */
public final class Replanner
{
	private Replanner()
	{
	}

	public static Plan replan(Plan previous, PlanRequest base, PlayerState newState, Tile position, Set<String> doneNodeIds)
	{
		Set<String> done = doneNodeIds == null ? Collections.emptySet() : doneNodeIds;
		Set<String> completed = new LinkedHashSet<>(base.getCompletedTaskIds());
		Set<String> doneSteps = new LinkedHashSet<>(base.getDoneNodeIds());
		for (String id : done)
		{
			if (id.startsWith("task:"))
			{
				completed.add(id.substring("task:".length()));
			}
			else if (id.startsWith("quest:"))
			{
				doneSteps.add(id);
			}
		}
		PlanRequest copy = base.toBuilder()
			.state(newState)
			.start(position)
			.completedTaskIds(completed)
			.doneNodeIds(doneSteps)
			.build();
		return new Planner(copy).plan();
	}
}
