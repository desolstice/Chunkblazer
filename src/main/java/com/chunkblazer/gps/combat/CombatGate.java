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
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.MonsterDef;
import java.util.Optional;

/**
 * A simple, explainable "is this fight sensible yet" gate for NPC kill tasks that carry no other combat requirement.
 * Two rules, both from the target's monster data:
 * <ul>
 * <li>combat level: the player must be within {@link #COMBAT_LEVEL_MARGIN} levels of the monster
 * (required = monster combat level - margin);</li>
 * <li>damage: the player's max hit must be at least monster hp / {@link #MAX_HITS_TO_KILL}, so the kill is not
 * hopeless (a 750 hp boss needs a max hit of 25).</li>
 * </ul>
 * When several monsters match the task (variants), the easiest one (lowest combat level, then lowest hp) is used, so a
 * task is only gated by the weakest form it accepts. Gates are heuristics and are reported as approximate.
 */
public final class CombatGate
{
	/** A player may fight monsters up to this many combat levels above their own. */
	public static final int COMBAT_LEVEL_MARGIN = 10;
	/** A kill is only sensible if the player's max hit times this many covers the monster's hitpoints. */
	public static final int MAX_HITS_TO_KILL = 30;

	private CombatGate()
	{
	}

	/** Combat level the player needs for a monster of this level; 0 when the monster is unknown or low level. */
	public static int requiredCombatLevel(int monsterCombatLevel)
	{
		return Math.max(0, monsterCombatLevel - COMBAT_LEVEL_MARGIN);
	}

	/** Max hit the player needs to kill a monster with this many hitpoints; 0 when hp is unknown. */
	public static int requiredMaxHit(int monsterHp)
	{
		return monsterHp <= 0 ? 0 : Math.max(1, (monsterHp + MAX_HITS_TO_KILL - 1) / MAX_HITS_TO_KILL);
	}

	/** The matching monster with the lowest combat level (ties: lowest hp) that has a combat level and hp. */
	public static Optional<MonsterDef> easiestTarget(NuzlockeTask t, GpsData d)
	{
		TargetNpc target = t == null ? null : t.getTargetNpc();
		if (target == null || d == null)
		{
			return Optional.empty();
		}
		String name = OneHitCheck.stripVariant(target.getName());
		MonsterDef best = null;
		for (MonsterDef m : d.getMonsters())
		{
			if (m == null || m.getHp() <= 0 || m.getCombatLevel() <= 0 || !OneHitCheck.matches(m, target, name))
			{
				continue;
			}
			if (best == null || m.getCombatLevel() < best.getCombatLevel()
				|| (m.getCombatLevel() == best.getCombatLevel() && m.getHp() < best.getHp()))
			{
				best = m;
			}
		}
		return Optional.ofNullable(best);
	}
}
