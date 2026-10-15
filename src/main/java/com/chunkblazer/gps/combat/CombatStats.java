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

import lombok.Builder;
import lombok.Value;

/**
 * Inputs for max-hit calculation. Levels are boosted levels; multipliers default to 1.0.
 */
@Value
@Builder
public class CombatStats
{
	public enum Style
	{
		AGGRESSIVE, CONTROLLED, ACCURATE, DEFENSIVE, RAPID, LONGRANGE
	}

	@Builder.Default
	int attack = 1;
	@Builder.Default
	int strength = 1;
	@Builder.Default
	int ranged = 1;
	@Builder.Default
	int magic = 1;

	/** Equipment melee strength bonus. */
	int strengthBonus;
	/** Equipment ranged strength bonus. */
	int rangedStrengthBonus;
	/** Magic damage bonus in percent, e.g. 10 for +10%. */
	int magicDamagePercent;

	@Builder.Default
	Style style = Style.AGGRESSIVE;

	@Builder.Default
	double prayerStrengthMultiplier = 1.0;
	@Builder.Default
	double prayerRangedMultiplier = 1.0;
	@Builder.Default
	double voidMelee = 1.0;
	@Builder.Default
	double voidRanged = 1.0;
	@Builder.Default
	double voidMagicDamage = 1.0;
	/** Slayer helm / salve style multiplier. */
	@Builder.Default
	double gearMultiplier = 1.0;
}
