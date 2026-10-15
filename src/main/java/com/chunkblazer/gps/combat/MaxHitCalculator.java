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

import java.util.Collections;
import java.util.List;

/**
 * Standard OSRS max-hit formulas.
 */
public final class MaxHitCalculator
{
	private MaxHitCalculator()
	{
	}

	public static int melee(CombatStats s)
	{
		int styleBonus = 0;
		if (s.getStyle() == CombatStats.Style.AGGRESSIVE)
		{
			styleBonus = 3;
		}
		else if (s.getStyle() == CombatStats.Style.CONTROLLED)
		{
			styleBonus = 1;
		}
		int eff = (int) Math.floor(s.getStrength() * s.getPrayerStrengthMultiplier()) + 8 + styleBonus;
		eff = (int) Math.floor(eff * s.getVoidMelee());
		return finish(eff, s.getStrengthBonus(), s.getGearMultiplier());
	}

	public static int ranged(CombatStats s)
	{
		int styleBonus = s.getStyle() == CombatStats.Style.ACCURATE ? 3 : 0;
		int eff = (int) Math.floor(s.getRanged() * s.getPrayerRangedMultiplier()) + 8 + styleBonus;
		eff = (int) Math.floor(eff * s.getVoidRanged());
		return finish(eff, s.getRangedStrengthBonus(), s.getGearMultiplier());
	}

	public static int magic(CombatStats s, String spell)
	{
		int base = MagicSpells.baseMaxHit(spell, s.getMagic());
		return (int) Math.floor(base * (1 + s.getMagicDamagePercent() / 100.0) * s.getVoidMagicDamage());
	}

	public static int best(CombatStats s)
	{
		return best(s, Collections.emptyList());
	}

	public static int best(CombatStats s, List<String> availableSpells)
	{
		int best = Math.max(melee(s), ranged(s));
		if (availableSpells != null)
		{
			for (String spell : availableSpells)
			{
				best = Math.max(best, magic(s, spell));
			}
		}
		return best;
	}

	private static int finish(int effectiveLevel, int bonus, double gearMultiplier)
	{
		int base = (int) Math.floor(0.5 + effectiveLevel * (bonus + 64) / 640.0);
		return (int) Math.floor(base * gearMultiplier);
	}
}
