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

import java.util.Locale;
import lombok.Value;

/** What the planner should optimise for. Use the static factories. */
@Value
public class GoalMode
{
	public enum Kind
	{
		MAX_POINTS, UNLOCK_NEXT_CHUNK, TRAIN_SKILL, MAX_GEAR
	}

	Kind kind;
	/** Upper case skill name for {@link Kind#TRAIN_SKILL}, otherwise null. */
	String skill;
	/** Target level for {@link Kind#TRAIN_SKILL}, otherwise 0. */
	int level;

	private GoalMode(Kind kind, String skill, int level)
	{
		this.kind = kind;
		this.skill = skill;
		this.level = level;
	}

	public static GoalMode maxPoints()
	{
		return new GoalMode(Kind.MAX_POINTS, null, 0);
	}

	public static GoalMode unlockNextChunk()
	{
		return new GoalMode(Kind.UNLOCK_NEXT_CHUNK, null, 0);
	}

	public static GoalMode trainSkill(String skill, int level)
	{
		return new GoalMode(Kind.TRAIN_SKILL, skill == null ? null : skill.toUpperCase(Locale.ROOT), level);
	}

	public static GoalMode maxGear()
	{
		return new GoalMode(Kind.MAX_GEAR, null, 0);
	}
}
