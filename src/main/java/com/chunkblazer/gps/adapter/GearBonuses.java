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
package com.chunkblazer.gps.adapter;

import java.util.List;
import lombok.Value;

/**
 * Summed equipment bonuses relevant to max hit. Pure values so the logic is testable without
 * RuneLite types.
 */
@Value
public class GearBonuses
{
	/** One worn item's strength, ranged strength and magic damage percent. */
	@Value
	public static class Triple
	{
		int str;
		int rstr;
		double mdmg;
	}

	int strength;
	int rangedStrength;
	double magicDamagePercent;

	public static GearBonuses sum(List<Triple> worn)
	{
		int str = 0;
		int rstr = 0;
		double mdmg = 0;
		if (worn != null)
		{
			for (Triple t : worn)
			{
				if (t != null)
				{
					str += t.getStr();
					rstr += t.getRstr();
					mdmg += t.getMdmg();
				}
			}
		}
		return new GearBonuses(str, rstr, mdmg);
	}
}
