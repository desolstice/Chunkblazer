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

import java.util.List;
import java.util.Map;
import lombok.Value;

@Value
public class QuestDef
{
	String id;
	String name;
	Requirements requirements;
	Rewards rewards;
	List<QuestStep> steps;

	public Requirements getRequirements()
	{
		return requirements == null ? new Requirements(null, null, 0, 0, null) : requirements;
	}

	public Rewards getRewards()
	{
		return rewards == null ? new Rewards(null, null, 0, null) : rewards;
	}

	public List<QuestStep> getSteps()
	{
		return Safe.list(steps);
	}

	@Value
	public static class Rewards
	{
		Map<String, Integer> xp;
		List<ChoiceXp> choiceXp;
		int questPoints;
		List<Unlock> unlocks;

		public Map<String, Integer> getXp()
		{
			return Safe.map(xp);
		}

		public List<ChoiceXp> getChoiceXp()
		{
			return Safe.list(choiceXp);
		}

		public List<Unlock> getUnlocks()
		{
			return Safe.list(unlocks);
		}
	}

	@Value
	public static class ChoiceXp
	{
		int amount;
		int count;
		List<String> skills;

		public List<String> getSkills()
		{
			return Safe.list(skills);
		}
	}

	@Value
	public static class Unlock
	{
		String type;
		String name;
	}
}
