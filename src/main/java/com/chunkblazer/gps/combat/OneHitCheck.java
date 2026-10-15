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
package com.chunkblazer.gps.combat;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TargetNpc;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.MonsterDef;
import java.util.Locale;
import java.util.OptionalInt;

/** Detects "1 Hit" NPC kill tasks and looks up the target's hitpoints from monster data. */
public final class OneHitCheck
{
	private OneHitCheck()
	{
	}

	/** True for NPC_KILL tasks whose time limit is exactly one tick (a limit such as 20 is not one-hit). */
	public static boolean isOneHit(NuzlockeTask t)
	{
		if (t == null || t.getCompletionType() == null || !"NPC_KILL".equals(t.getCompletionType().trim().toUpperCase(Locale.ROOT)))
		{
			return false;
		}
		TaskConstraints c = t.getConstraints();
		return c != null && c.getTimeInTicks() != null && c.getTimeInTicks() == 1;
	}

	/**
	 * Maximum hp over monsters matching the task's target NPC by id or by name (any "#Variant" suffix stripped,
	 * case-insensitive). Monsters with hp &lt;= 0 are treated as unknown; empty when nothing usable matches.
	 */
	public static OptionalInt targetHp(NuzlockeTask t, GpsData d)
	{
		TargetNpc target = t == null ? null : t.getTargetNpc();
		if (target == null || d == null)
		{
			return OptionalInt.empty();
		}
		String name = stripVariant(target.getName());
		int best = 0;
		for (MonsterDef m : d.getMonsters())
		{
			if (m == null || m.getHp() <= 0)
			{
				continue;
			}
			if (matches(m, target, name))
			{
				best = Math.max(best, m.getHp());
			}
		}
		return best > 0 ? OptionalInt.of(best) : OptionalInt.empty();
	}

	private static boolean matches(MonsterDef m, TargetNpc target, String name)
	{
		if (target.getNpcIds() != null)
		{
			for (Integer id : m.getIds())
			{
				if (target.getNpcIds().contains(id))
				{
					return true;
				}
			}
		}
		return name != null && m.getName() != null && name.equalsIgnoreCase(m.getName().trim());
	}

	private static String stripVariant(String name)
	{
		if (name == null)
		{
			return null;
		}
		int i = name.indexOf('#');
		String s = (i >= 0 ? name.substring(0, i) : name).trim();
		return s.isEmpty() ? null : s;
	}
}
