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
package com.chunkblazer.gps.eligibility;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.model.Requirement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Evaluates tasks and quests against a {@link PlayerState}. */
public final class EligibilityEngine
{
	private final RequirementDeriver deriver;
	private final QuestCatalog quests;
	private final GpsData data;

	public EligibilityEngine(RequirementDeriver deriver, QuestCatalog quests, GpsData data)
	{
		this.deriver = Objects.requireNonNull(deriver, "deriver");
		this.quests = Objects.requireNonNull(quests, "quests");
		this.data = Objects.requireNonNull(data, "data");
	}

	public QuestCatalog getQuests()
	{
		return quests;
	}

	public Eligibility evaluate(NuzlockeTask task, PlayerState state)
	{
		return Eligibility.of(deriver.derive(task).missing(state));
	}

	/**
	 * The quest's own requirement plus every not yet completed transitive prerequisite as a QUEST entry.
	 * A quest the player already completed is evaluated like any other (no special casing).
	 */
	public Eligibility evaluateQuest(String questId, PlayerState state)
	{
		Optional<QuestModel> quest = quests.get(questId);
		if (!quest.isPresent())
		{
			return new Eligibility(Eligibility.Status.UNKNOWN, Collections.singletonList(
				new Missing(Missing.Kind.UNKNOWN, "Unknown quest: " + questId)));
		}
		Requirement req = quest.get().getRequirement();
		List<Missing> missing = new ArrayList<>(req.missing(state));
		Set<Missing> seen = new HashSet<>(missing);
		// QuestModel.chunks lists every chunk the quest touches; all of them must be unlocked.
		// Same text the Requirement uses for a chunk, so the dedupe catches any overlap.
		for (int chunk : quest.get().getChunks())
		{
			if (!state.hasUnlockedChunk(chunk))
			{
				Missing m = new Missing(Missing.Kind.CHUNK, "Chunk " + chunk + " not unlocked");
				if (seen.add(m))
				{
					missing.add(m);
				}
			}
		}
		for (String pre : quests.prerequisitesClosure(questId))
		{
			if (state.hasQuest(pre))
			{
				continue;
			}
			// Same text the Requirement uses for a directly required quest, so the dedupe below catches it.
			Missing m = Requirement.builder().quest(pre).build().missing(PlayerState.freshAccount()).get(0);
			if (seen.add(m))
			{
				missing.add(m);
			}
		}
		return Eligibility.of(missing);
	}
}
