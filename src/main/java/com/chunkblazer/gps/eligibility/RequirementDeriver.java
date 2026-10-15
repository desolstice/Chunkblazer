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
package com.chunkblazer.gps.eligibility;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredItem;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.gps.combat.CombatGate;
import com.chunkblazer.gps.combat.KillTimeCheck;
import com.chunkblazer.gps.combat.OneHitCheck;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.MonsterDef;
import com.chunkblazer.gps.model.ItemNeed;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.model.Requirement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Derives the {@link Requirement} for a task from its catalog fields, unless overridden. */
public final class RequirementDeriver
{
	/** Skill-type completion types mapped to a skill name; SKILL_THRESHOLD maps to null (reads constraints.required_skill). */
	private static final Map<String, String> SKILL_TYPES = new HashMap<>();
	private static final Set<String> UNKNOWN_TYPES = new HashSet<>(Arrays.asList(
		"VARBIT_CHECK", "VARP_CHECK", "COMBAT_ACHIEVEMENT", "RAID_CHALLENGE", "NPC_DIALOGUE"));

	static
	{
		for (String s : Arrays.asList("COOKING", "MINING", "FISHING", "WOODCUTTING", "FLETCHING", "CRAFTING",
			"AGILITY", "THIEVING", "CONSTRUCTION", "SMITHING", "SLAYER", "FIREMAKING", "HERBLORE", "HUNTER",
			"FARMING", "PRAYER"))
		{
			SKILL_TYPES.put(s, s);
		}
		SKILL_TYPES.put("RUNECRAFTING", "RUNECRAFT");
		SKILL_TYPES.put("SKILL_THRESHOLD", null);
	}

	private final QuestCatalog quests;
	private final Overrides overrides;
	private final GpsData data;

	public RequirementDeriver(QuestCatalog quests, Overrides overrides, GpsData data)
	{
		this.quests = Objects.requireNonNull(quests, "quests");
		this.overrides = Objects.requireNonNull(overrides, "overrides");
		this.data = Objects.requireNonNull(data, "data");
	}

	public Requirement derive(NuzlockeTask t)
	{
		Optional<Requirement> override = overrides.get(t.getTaskId());
		if (override.isPresent())
		{
			return override.get();
		}
		String type = t.getCompletionType() == null ? "" : t.getCompletionType().toUpperCase(Locale.ROOT);
		TaskConstraints c = t.getConstraints();
		String constraintSkill = c == null || c.getRequiredSkill() == null || c.getRequiredSkill().trim().isEmpty()
			? null : c.getRequiredSkill().trim().toUpperCase(Locale.ROOT);
		Requirement.Builder b = Requirement.builder();
		Requirement fromQuest = Requirement.NONE;

		// (1) skill-type tasks
		if (SKILL_TYPES.containsKey(type))
		{
			String skill = constraintSkill != null ? constraintSkill : SKILL_TYPES.get(type);
			if (skill != null)
			{
				b.skill(skill, t.getLevelRequirement());
			}
		}
		// (2) explicit constraint skill, for any type
		if (constraintSkill != null)
		{
			b.skill(constraintSkill, c.getRequiredLevel());
		}
		// (3) required items
		List<RequiredItem> items = t.getRequiredItems();
		if (items != null)
		{
			for (RequiredItem ri : items)
			{
				if (ri == null)
				{
					continue;
				}
				if (ri.getItemIds() == null || ri.getItemIds().isEmpty())
				{
					b.unknown("item without ids: " + ri.getItem());
					continue;
				}
				int qty = ri.getQuantity() != null ? ri.getQuantity()
					: ri.getQuantityRange() != null && !ri.getQuantityRange().isEmpty() ? ri.getQuantityRange().get(0) : 1;
				b.item(new ItemNeed(new LinkedHashSet<>(ri.getItemIds()), qty, ri.getItem()));
			}
		}
		// (4) quest check
		if ("QUEST_CHECK".equals(type) && c != null && c.getQuest() != null)
		{
			String id = c.getQuest();
			Optional<QuestModel> quest = quests.get(id);
			if (quest.isPresent())
			{
				Requirement.Builder qb = Requirement.builder();
				for (String pre : quests.prerequisitesClosure(id))
				{
					qb.quest(pre);
				}
				fromQuest = quest.get().getRequirement().merge(qb.build());
			}
			else
			{
				b.unknown("quest data missing: " + id);
			}
		}
		if (c != null)
		{
			// (5) combat level
			if (c.getMinCombatLevel() != null)
			{
				b.combatLevel(c.getMinCombatLevel());
			}
			// (6) regions
			if (c.getRequiredRegion() != null)
			{
				b.chunk(c.getRequiredRegion());
			}
			List<Integer> allowed = c.getAllowedRegions();
			if (allowed != null && !allowed.isEmpty())
			{
				b.chunk(allowed.get(0));
				if (allowed.size() > 1)
				{
					b.unknown("any of regions");
				}
			}
			// (7) varbit/varp constraints
			if (c.getVarbitId() != null)
			{
				b.unknown("varbit check " + c.getVarbitId());
			}
			if (c.getVarpId() != null)
			{
				b.unknown("varp check " + c.getVarpId());
			}
		}
		// (7) completion types that cannot be judged from skills and items
		if (UNKNOWN_TYPES.contains(type))
		{
			b.unknown("cannot judge " + type + " from skills and items");
		}
		// (8) one-hit kills need a max hit of at least the target's hitpoints
		if (OneHitCheck.isOneHit(t))
		{
			OptionalInt hp = OneHitCheck.targetHp(t, data);
			if (hp.isPresent())
			{
				b.minMaxHit(hp.getAsInt());
			}
			else
			{
				b.unknown("one-hit target HP unknown (no monster data)");
			}
		}
		else if (isTimeLimitedKill(t))
		{
			// approximate: kills with a limit above one tick need enough damage output to finish in time
			OptionalInt hp = OneHitCheck.targetHp(t, data);
			if (hp.isPresent())
			{
				b.minMaxHit(KillTimeCheck.requiredMaxHit(hp.getAsInt(), t.getConstraints().getTimeInTicks(),
					KillTimeCheck.DEFAULT_ATTACK_SPEED, KillTimeCheck.DEFAULT_HIT_CHANCE));
				b.approximate(true);
			}
		}
		return b.build().merge(combatGate(t)).merge(fromQuest);
	}

	/**
	 * (9) heuristic combat gate for NPC kills so a fresh account is not sent to bosses: a combat level near the
	 * monster's, and (when no one-hit or time limit already sets one) a max hit that makes the kill feasible.
	 */
	private Requirement combatGate(NuzlockeTask t)
	{
		if (t.getCompletionType() == null || !"NPC_KILL".equals(t.getCompletionType().trim().toUpperCase(Locale.ROOT)))
		{
			return Requirement.NONE;
		}
		Optional<MonsterDef> m = CombatGate.easiestTarget(t, data);
		if (!m.isPresent())
		{
			return Requirement.NONE;
		}
		Requirement.Builder g = Requirement.builder().approximate(true);
		g.combatLevel(CombatGate.requiredCombatLevel(m.get().getCombatLevel()));
		if (!OneHitCheck.isOneHit(t) && !isTimeLimitedKill(t))
		{
			g.minMaxHit(CombatGate.requiredMaxHit(m.get().getHp()));
		}
		return g.build();
	}

	private static boolean isTimeLimitedKill(NuzlockeTask t)
	{
		TaskConstraints c = t.getConstraints();
		return t.getCompletionType() != null && "NPC_KILL".equals(t.getCompletionType().trim().toUpperCase(Locale.ROOT))
			&& c != null && c.getTimeInTicks() != null && c.getTimeInTicks() > 1;
	}
}
