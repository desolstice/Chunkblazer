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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/*
 * NOTE (2026-10-14): the expected values below are hand-derived from the standard OSRS max-hit
 * formulas documented on CombatStats/MaxHitCalculator. They were NOT verified against the wiki DPS
 * calculator (no wiki access was available when this was written).
 */
class MaxHitCalculatorTest
{
	private static CombatStats.CombatStatsBuilder melee99()
	{
		return CombatStats.builder().strength(99).style(CombatStats.Style.AGGRESSIVE);
	}

	@Test
	void meleeNoGearNoPrayer()
	{
		// eff = 99 + 8 + 3 = 110; floor(0.5 + 110*64/640) = floor(11.5) = 11
		assertEquals(11, MaxHitCalculator.melee(melee99().build()));
	}

	@Test
	void meleeWithWhipBonus()
	{
		// 110 * (82+64) / 640 = 25.09 -> floor(25.59) = 25
		assertEquals(25, MaxHitCalculator.melee(melee99().strengthBonus(82).build()));
	}

	@Test
	void meleeWithPiety()
	{
		// floor(99*1.23)=121; eff=121+8+3=132; 132*(82+64)/640=30.11 -> floor(30.61)=30
		assertEquals(30, MaxHitCalculator.melee(melee99().strengthBonus(82).prayerStrengthMultiplier(1.23).build()));
	}

	@Test
	void controlledStyleGivesPlusOne()
	{
		CombatStats s = CombatStats.builder().strength(1).style(CombatStats.Style.CONTROLLED).build();
		assertEquals(1, MaxHitCalculator.melee(s)); // eff=1+8+1=10 -> floor(0.5+10*64/640)=1
	}

	@Test
	void levelOneAccountMaxHitIsOne()
	{
		// eff=1+8+0=9 -> floor(0.5+9*64/640)=floor(1.4)=1
		assertEquals(1, MaxHitCalculator.melee(CombatStats.builder().strength(1).style(CombatStats.Style.ACCURATE).build()));
	}

	@Test
	void defaultsAreLevelOneAggressive()
	{
		CombatStats s = CombatStats.builder().build();
		assertEquals(1, s.getAttack());
		assertEquals(1, s.getStrength());
		assertEquals(1, s.getRanged());
		assertEquals(1, s.getMagic());
		assertEquals(CombatStats.Style.AGGRESSIVE, s.getStyle());
		assertEquals(1.0, s.getGearMultiplier());
		// eff=1+8+3=12 -> floor(0.5+12*0.1)=floor(1.7)=1
		assertEquals(1, MaxHitCalculator.melee(s));
	}

	@Test
	void nullStyleDoesNotThrow()
	{
		CombatStats s = CombatStats.builder().strength(99).style(null).build();
		// eff=99+8+0=107 -> floor(0.5+10.7)=11
		assertEquals(11, MaxHitCalculator.melee(s));
		assertEquals(11, MaxHitCalculator.ranged(CombatStats.builder().ranged(99).style(null).build()));
	}

	@Test
	void rangedWithBonus()
	{
		// ranged 99 accurate: eff=99+8+3=110; (rangedStr 60 + 64)=124; 110*124/640=21.31 -> floor(21.81)=21
		CombatStats s = CombatStats.builder().ranged(99).rangedStrengthBonus(60).style(CombatStats.Style.ACCURATE).build();
		assertEquals(21, MaxHitCalculator.ranged(s));
	}

	@Test
	void magicSpellsAndStaves()
	{
		CombatStats s = CombatStats.builder().magic(99).magicDamagePercent(10).build();
		assertEquals(26, MaxHitCalculator.magic(s, "Fire Surge")); // floor(24*1.10)=26
		assertEquals(31, MaxHitCalculator.magic(CombatStats.builder().magic(99).build(), "trident of the swamp")); // floor(99/3)-2
		assertEquals(0, MaxHitCalculator.magic(s, "no such spell"));
	}

	@Test
	void spellNamesAreCaseInsensitive()
	{
		CombatStats s = CombatStats.builder().magic(99).build();
		assertEquals(24, MaxHitCalculator.magic(s, "FIRE SURGE"));
		assertEquals(24, MaxHitCalculator.magic(s, "fire surge"));
		assertEquals(31, MaxHitCalculator.magic(s, "Trident Of The Swamp"));
	}

	@Test
	void fixedSpellTable()
	{
		String[] elements = {"wind", "water", "earth", "fire"};
		String[] tiers = {"strike", "bolt", "blast", "wave", "surge"};
		int[][] expected = {{2, 4, 6, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}, {21, 22, 23, 24}};
		for (int t = 0; t < tiers.length; t++)
		{
			for (int e = 0; e < elements.length; e++)
			{
				String name = elements[e] + " " + tiers[t];
				assertEquals(expected[t][e], MagicSpells.baseMaxHit(name, 99), name);
			}
		}
	}

	@Test
	void magicDamagePercentIsExactAtRoundingBoundaries()
	{
		// trident of the swamp at level 81: 27-2 = 25; 25 * 1.16 = 29 exactly
		assertEquals(29, MaxHitCalculator.magic(CombatStats.builder().magic(81).magicDamagePercent(16).build(), "trident of the swamp"));
		// level 141: 47-2 = 45; 45 * 1.40 = 63 exactly
		assertEquals(63, MaxHitCalculator.magic(CombatStats.builder().magic(141).magicDamagePercent(40).build(), "trident of the swamp"));
	}

	@Test
	void gearMultiplierIsExactAtRoundingBoundary()
	{
		// base 25 (99 str, +82 bonus); 25 * 1.16 = 29 exactly
		assertEquals(29, MaxHitCalculator.melee(melee99().strengthBonus(82).gearMultiplier(1.16).build()));
	}

	@Test
	void nullSpellIsZero()
	{
		assertEquals(0, MaxHitCalculator.magic(CombatStats.builder().magic(99).build(), null));
	}

	@Test
	void poweredStaffNeverNegative()
	{
		// floor(1/3) - 5 = -5 -> clamped to 0
		CombatStats s = CombatStats.builder().magic(1).build();
		assertEquals(0, MaxHitCalculator.magic(s, "trident of the seas"));
		assertEquals(0, MagicSpells.baseMaxHit("trident of the seas", 1));
	}

	@Test
	void poweredStaffFormulas()
	{
		assertEquals(28, MagicSpells.baseMaxHit("trident of the seas", 99)); // 33-5
		assertEquals(32, MagicSpells.baseMaxHit("sanguinesti staff", 99)); // 33-1
		assertEquals(34, MagicSpells.baseMaxHit("tumeken's shadow", 99)); // 33+1 (approximate)
	}

	@Test
	void slayerGearMultiplierAppliesAfterBase()
	{
		CombatStats s = melee99().strengthBonus(82).gearMultiplier(7.0 / 6.0).build();
		assertEquals(29, MaxHitCalculator.melee(s)); // floor(25 * 1.1666) = 29
	}

	@Test
	void bestPicksHighestStyle()
	{
		CombatStats s = CombatStats.builder().strength(99).style(CombatStats.Style.AGGRESSIVE).magic(99).build();
		assertEquals(24, MaxHitCalculator.best(s, Arrays.asList("fire surge")));
	}

	@Test
	void bestWithoutSpellsFallsBackToMeleeOrRanged()
	{
		CombatStats s = melee99().strengthBonus(82).build();
		assertEquals(25, MaxHitCalculator.best(s));
		assertEquals(25, MaxHitCalculator.best(s, Collections.emptyList()));
	}
}
