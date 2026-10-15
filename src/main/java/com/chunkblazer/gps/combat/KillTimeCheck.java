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

import com.chunkblazer.gps.travel.TravelConfig;

/**
 * Approximate time-to-kill estimates for time-limited kill tasks. This is deliberately crude: the average hit is
 * taken as half the max hit and the hit chance is a flat constant (no accuracy or defence model), so every result
 * derived from it is flagged as approximate.
 */
public final class KillTimeCheck
{
	/** Documented flat hit-chance approximation. */
	public static final double DEFAULT_HIT_CHANCE = 0.7;
	/** Default player attack speed in ticks used by requirement derivation. */
	public static final int DEFAULT_ATTACK_SPEED = 4;

	private KillTimeCheck()
	{
	}

	/** Estimated ticks to deal {@code monsterHp} damage, or {@link TravelConfig#INF} when the player cannot hit. */
	public static int estimateTicks(int monsterHp, int playerMaxHit, int attackSpeedTicks, double hitChance)
	{
		if (playerMaxHit <= 0 || hitChance <= 0)
		{
			return TravelConfig.INF;
		}
		if (monsterHp <= 0)
		{
			return 0;
		}
		double attacks = Math.ceil(monsterHp / (playerMaxHit * 0.5 * hitChance));
		double ticks = attacks * attackSpeedTicks;
		return ticks >= TravelConfig.INF ? TravelConfig.INF : (int) ticks;
	}

	public static boolean canKillInTime(int monsterHp, int playerMaxHit, int attackSpeedTicks, double hitChance, int limitTicks)
	{
		return estimateTicks(monsterHp, playerMaxHit, attackSpeedTicks, hitChance) <= limitTicks;
	}

	/** Smallest max hit that is estimated to kill {@code monsterHp} within {@code limitTicks} (at least one attack). */
	public static int requiredMaxHit(int monsterHp, int limitTicks, int attackSpeedTicks, double hitChance)
	{
		double attacks = Math.max(1.0, (double) limitTicks / attackSpeedTicks);
		return (int) Math.ceil(2.0 * monsterHp / (attacks * hitChance));
	}
}
