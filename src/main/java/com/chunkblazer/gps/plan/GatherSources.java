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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recognises the "DROP" item sources that are really gatherable resources (trees, rocks, fishing spots) rather than
 * monster drops, and the skill level gathering them needs. Items data lists these under the object or spot's name,
 * e.g. {@code Logs <- Tree}, {@code Oak logs <- Oak tree}.
 */
final class GatherSources
{
	private static final Map<String, Integer> WOODCUTTING_LEVEL = new HashMap<>();
	private static final Map<String, Integer> MINING_LEVEL = new HashMap<>();

	static
	{
		WOODCUTTING_LEVEL.put("tree", 1);
		WOODCUTTING_LEVEL.put("dead tree", 1);
		WOODCUTTING_LEVEL.put("evergreen tree", 1);
		WOODCUTTING_LEVEL.put("oak tree", 15);
		WOODCUTTING_LEVEL.put("willow tree", 30);
		WOODCUTTING_LEVEL.put("teak tree", 35);
		WOODCUTTING_LEVEL.put("maple tree", 45);
		WOODCUTTING_LEVEL.put("mahogany tree", 50);
		WOODCUTTING_LEVEL.put("yew tree", 60);
		WOODCUTTING_LEVEL.put("magic tree", 75);
		WOODCUTTING_LEVEL.put("redwood tree", 90);

		MINING_LEVEL.put("iron rocks", 15);
		MINING_LEVEL.put("silver rocks", 20);
		MINING_LEVEL.put("coal rocks", 30);
		MINING_LEVEL.put("gold rocks", 40);
		MINING_LEVEL.put("gem rocks", 40);
		MINING_LEVEL.put("mithril rocks", 55);
		MINING_LEVEL.put("adamantite rocks", 70);
		MINING_LEVEL.put("runite rocks", 85);
	}

	private GatherSources()
	{
	}

	/**
	 * The resource's base name ("Oak tree" for "Oak tree (The Node)") when {@code npc} is a tree, rocks, ore vein or
	 * fishing spot that is not a farming patch, event or stump; otherwise null.
	 */
	static String resourceBase(String npc)
	{
		if (npc == null)
		{
			return null;
		}
		String full = npc.trim();
		int paren = full.indexOf(" (");
		String base = (paren >= 0 ? full.substring(0, paren) : full).trim();
		String note = paren >= 0 ? full.substring(paren).toLowerCase(Locale.ROOT) : "";
		String lower = base.toLowerCase(Locale.ROOT);
		boolean resource = lower.equals("tree") || lower.endsWith(" tree") || lower.endsWith(" rocks")
			|| lower.equals("rocks") || lower.equals("ore vein") || lower.endsWith("fishing spot");
		if (!resource || lower.contains("stump") || lower.startsWith("boat") || note.contains("farming") || note.contains("event")
			|| note.contains("the node"))
		{
			return null;
		}
		return base;
	}

	/** Skill that gathers the resource: WOODCUTTING for trees, FISHING for spots, MINING otherwise. */
	static String skill(String base)
	{
		String lower = base == null ? "" : base.toLowerCase(Locale.ROOT);
		if (lower.equals("tree") || lower.endsWith(" tree"))
		{
			return "WOODCUTTING";
		}
		return lower.endsWith("fishing spot") ? "FISHING" : "MINING";
	}

	/** Level the skill needs for the resource (1 when not in the small tables). */
	static int level(String base)
	{
		String lower = base == null ? "" : base.toLowerCase(Locale.ROOT);
		Map<String, Integer> table = "WOODCUTTING".equals(skill(base)) ? WOODCUTTING_LEVEL : MINING_LEVEL;
		Integer l = table.get(lower);
		return l == null ? 1 : l;
	}
}
