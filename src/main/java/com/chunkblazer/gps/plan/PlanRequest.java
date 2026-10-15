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

import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.travel.ChunkGraph;
import com.chunkblazer.gps.travel.TransportModel;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/** Everything the planner needs for one run. Immutable. */
@Value
@Builder(toBuilder = true)
public class PlanRequest
{
	PlayerState state;
	Tile start;
	@Builder.Default
	GoalMode goal = GoalMode.maxPoints();
	@Builder.Default
	List<TaskRef> tasks = Collections.emptyList();
	@Builder.Default
	Set<String> completedTaskIds = Collections.emptySet();
	/** Node ids (quest steps) already done; their steps are dropped from quest chains. */
	@Builder.Default
	Set<String> doneNodeIds = Collections.emptySet();
	GpsData data;
	QuestCatalog quests;
	RequirementDeriver deriver;
	ChunkGraph graph;
	TransportModel transport;
	@Builder.Default
	int maxNodes = 300;
	@Builder.Default
	long seed = 1;
	@Builder.Default
	long deadlineMillis = 800;
	boolean allowTeleportAcquisition;
}
