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
package com.chunkblazer.gps.model;

public final class XpTable
{
	public static final int MAX_LEVEL = 99;
	private static final int[] XP = new int[MAX_LEVEL + 2];

	static
	{
		double points = 0;
		XP[1] = 0;
		for (int lvl = 1; lvl < MAX_LEVEL; lvl++)
		{
			points += Math.floor(lvl + 300.0 * Math.pow(2.0, lvl / 7.0));
			XP[lvl + 1] = (int) Math.floor(points / 4.0);
		}
	}

	private XpTable()
	{
	}

	public static int xpForLevel(int level)
	{
		return XP[Math.max(1, Math.min(MAX_LEVEL, level))];
	}

	public static int levelForXp(long xp)
	{
		int lvl = 1;
		while (lvl < MAX_LEVEL && xp >= XP[lvl + 1])
		{
			lvl++;
		}
		return lvl;
	}
}
