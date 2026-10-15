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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Base max hits for standard-spellbook combat spells and powered staves.
 */
public final class MagicSpells
{
	private static final String[] ELEMENT_NAMES = {"wind", "water", "earth", "fire"};
	private static final String[] TIER_NAMES = {"strike", "bolt", "blast", "wave", "surge"};
	private static final int[][] LEVELS = {
		{1, 5, 9, 13}, {17, 23, 29, 35}, {41, 47, 53, 59}, {62, 66, 70, 75}, {81, 85, 90, 95}
	};
	private static final Map<String, Integer> FIXED = new HashMap<>();
	private static final Map<String, IntUnaryOperator> STAVES = new HashMap<>();

	static
	{
		String[] elements = {"wind", "water", "earth", "fire"};
		String[] tiers = {"strike", "bolt", "blast", "wave", "surge"};
		int[] starts = {2, 9, 13, 17, 21};
		for (int t = 0; t < tiers.length; t++)
		{
			for (int e = 0; e < elements.length; e++)
			{
				int step = t == 0 ? 2 : 1; // strikes go 2, 4, 6, 8
				FIXED.put(elements[e] + " " + tiers[t], starts[t] + e * step);
			}
		}

		STAVES.put("trident of the seas", level -> level / 3 - 5);
		STAVES.put("trident of the swamp", level -> level / 3 - 2);
		STAVES.put("sanguinesti staff", level -> level / 3 - 1);
		STAVES.put("tumeken's shadow", level -> level / 3 + 1); // approximate
	}

	private MagicSpells()
	{
	}

	/**
	 * @return every standard-spellbook combat spell whose magic level requirement is met
	 * (strikes 1/5/9/13, bolts 17/23/29/35, blasts 41/47/53/59, waves 62/66/70/75, surges 81/85/90/95
	 * in wind, water, earth, fire order)
	 */
	public static List<String> available(int magicLevel)
	{
		List<String> spells = new ArrayList<>();
		for (int t = 0; t < TIER_NAMES.length; t++)
		{
			for (int e = 0; e < ELEMENT_NAMES.length; e++)
			{
				if (magicLevel >= LEVELS[t][e])
				{
					spells.add(ELEMENT_NAMES[e] + " " + TIER_NAMES[t]);
				}
			}
		}
		return spells;
	}

	/**
	 * @return the unmodified max hit of the named spell/staff at the given magic level, or 0 if unknown
	 */
	public static int baseMaxHit(String name, int magicLevel)
	{
		if (name == null)
		{
			return 0;
		}
		String key = name.trim().toLowerCase(Locale.ROOT);
		Integer fixed = FIXED.get(key);
		if (fixed != null)
		{
			return fixed;
		}
		IntUnaryOperator staff = STAVES.get(key);
		return staff == null ? 0 : Math.max(0, staff.applyAsInt(Math.max(0, magicLevel)));
	}
}
