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

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.ItemDef;
import com.chunkblazer.gps.data.QuestDef;
import com.chunkblazer.gps.data.QuestStep;
import com.chunkblazer.gps.data.Requirements;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.data.TrainingMethod;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.ItemNeed;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.model.Requirement;
import com.chunkblazer.gps.model.XpTable;
import com.chunkblazer.gps.travel.LocationResolver;
import com.chunkblazer.gps.travel.ResolvedLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Builds plan nodes for tasks, quest step chains, training and item acquisitions. Pure and deterministic. */
public final class NodeFactory
{
	private static final long TICKS_PER_HOUR = 6000L;

	private final GpsData data;
	private final QuestCatalog quests;
	private final RequirementDeriver deriver;
	private final LocationResolver locations;
	private final Set<String> doneNodeIds;

	public NodeFactory(GpsData data, QuestCatalog quests, RequirementDeriver deriver, LocationResolver locations)
	{
		this(data, quests, deriver, locations, Collections.emptySet());
	}

	/** {@code doneNodeIds}: quest step ids already done; {@link #questChain} drops them. */
	public NodeFactory(GpsData data, QuestCatalog quests, RequirementDeriver deriver, LocationResolver locations,
		Set<String> doneNodeIds)
	{
		this.doneNodeIds = doneNodeIds == null ? Collections.emptySet() : doneNodeIds;
		this.data = data;
		this.quests = quests;
		this.deriver = deriver;
		this.locations = locations;
	}

	/** A single node for the task; QUEST_CHECK tasks are expanded by the caller via {@link #questChain}. */
	public PlanNode forTask(TaskRef ref)
	{
		NuzlockeTask t = ref.getTask();
		int points = t.getBasePoints();
		TaskConstraints c = t.getConstraints();
		int duration = c != null && c.getTimeInTicks() != null && c.getTimeInTicks() > 0 ? c.getTimeInTicks() : 0;
		return PlanNode.builder()
			.id("task:" + t.getTaskId())
			.type(NodeType.TASK)
			.label(t.getName() == null ? t.getTaskId() : t.getName())
			.location(locations.forTask(t, ref.getHomeRegionId()))
			.requirement(deriver.derive(t))
			.effects(Effects.builder().taskPoints(points).build())
			.points(points)
			.durationTicks(duration)
			.taskId(t.getTaskId())
			.build();
	}

	/** One node per quest step (ordered by {@code after}); empty when the quest is unknown. */
	public List<PlanNode> questChain(String questId, int completionTaskPoints)
	{
		List<PlanNode> full = fullQuestChain(questId, completionTaskPoints);
		if (doneNodeIds.isEmpty())
		{
			return full;
		}
		List<PlanNode> out = new ArrayList<>();
		for (PlanNode n : full)
		{
			if (doneNodeIds.contains(n.getId()))
			{
				continue;
			}
			List<String> after = out.isEmpty() ? Collections.<String>emptyList()
				: Collections.singletonList(out.get(out.size() - 1).getId());
			out.add(n.toBuilder().after(after).build());
		}
		return out;
	}

	private List<PlanNode> fullQuestChain(String questId, int completionTaskPoints)
	{
		Optional<QuestModel> om = quests.get(questId);
		if (!om.isPresent())
		{
			return Collections.emptyList();
		}
		QuestModel q = om.get();
		List<QuestStep> steps = q.getSteps() == null ? Collections.emptyList() : q.getSteps();
		String name = q.getName() == null ? questId : q.getName();
		Effects reward = rewardEffects(q, completionTaskPoints);
		Requirement first = withChunks(q.getRequirement(), q.getChunks());
		List<PlanNode> out = new ArrayList<>();
		if (steps.isEmpty())
		{
			out.add(PlanNode.builder()
				.id(stepId(questId, 0))
				.type(NodeType.QUEST_STEP)
				.label("Quest: " + name)
				.location(locations.forQuestStep(questId, 0))
				.requirement(first)
				.effects(reward)
				.points(completionTaskPoints)
				.questId(questId)
				.stepIndex(0)
				.build());
			return out;
		}
		ResolvedLocation previous = null;
		for (int i = 0; i < steps.size(); i++)
		{
			QuestStep s = steps.get(i);
			boolean last = i == steps.size() - 1;
			ResolvedLocation loc;
			if (s != null && s.getLocation().isKnown())
			{
				loc = locations.forQuestStep(questId, i);
			}
			else if (previous != null && previous.getTile() != null)
			{
				loc = new ResolvedLocation(previous.getTile(), true, "CARRY");
			}
			else
			{
				loc = locations.forQuestStep(questId, i);
			}
			previous = loc;
			String text = s == null || s.getText() == null ? "step " + (i + 1) : s.getText();
			out.add(PlanNode.builder()
				.id(stepId(questId, i))
				.type(NodeType.QUEST_STEP)
				.label(name + ": " + text)
				.location(loc)
				.requirement(i == 0 ? first : Requirement.NONE)
				.effects(last ? reward : Effects.NONE)
				.after(i == 0 ? Collections.emptyList() : Collections.singletonList(stepId(questId, i - 1)))
				.points(last ? completionTaskPoints : 0)
				.questId(questId)
				.stepIndex(i)
				.build());
		}
		return out;
	}

	/** The quest requirement plus a chunk requirement for each of the quest's chunks (merged, never replaced). */
	private static Requirement withChunks(Requirement base, List<Integer> chunks)
	{
		if (chunks == null || chunks.isEmpty())
		{
			return base;
		}
		Requirement.Builder b = Requirement.builder();
		for (Integer c : chunks)
		{
			if (c != null)
			{
				b.chunk(c);
			}
		}
		return base == null ? b.build() : base.merge(b.build());
	}

	/** True when the quest exists, has steps, and every one of its step nodes is already in the done set. */
	public boolean questFullyDone(String questId)
	{
		if (doneNodeIds.isEmpty())
		{
			return false;
		}
		List<PlanNode> full = fullQuestChain(questId, 0);
		if (full.isEmpty())
		{
			return false;
		}
		for (PlanNode n : full)
		{
			if (!doneNodeIds.contains(n.getId()))
			{
				return false;
			}
		}
		return true;
	}

	private static String stepId(String questId, int index)
	{
		return "quest:" + questId + ":" + index;
	}

	private static Effects rewardEffects(QuestModel q, int completionTaskPoints)
	{
		QuestDef.Rewards r = q.getRewards() == null ? new QuestDef.Rewards(null, null, 0, null) : q.getRewards();
		Effects.Builder b = Effects.builder();
		r.getXp().forEach((skill, amount) -> b.xp(skill == null ? null : skill.toUpperCase(Locale.ROOT), amount));
		b.questComplete(q.getId(), r.getQuestPoints());
		for (QuestDef.Unlock u : r.getUnlocks())
		{
			if (u != null && u.getType() != null && u.getName() != null)
			{
				b.unlock(u.getType() + ":" + u.getName());
			}
		}
		b.taskPoints(completionTaskPoints);
		return b.build();
	}

	/** Best training method covering the level (highest xp/h, ties by lowest min level), if any. */
	public Optional<PlanNode> trainingNode(String skill, int level)
	{
		TrainingMethod best = null;
		for (TrainingMethod m : data.getTraining())
		{
			if (m == null || m.getSkill() == null || !m.getSkill().equalsIgnoreCase(skill)
				|| level < m.getMinLevel() || level > m.getMaxLevel())
			{
				continue;
			}
			if (best == null || xph(m) > xph(best) || (xph(m) == xph(best) && m.getMinLevel() < best.getMinLevel()))
			{
				best = m;
			}
		}
		if (best == null)
		{
			return Optional.empty();
		}
		String sk = skill.toUpperCase(Locale.ROOT);
		Tile loc = best.getLocation();
		ResolvedLocation rl = loc != null && loc.isKnown()
			? new ResolvedLocation(loc, best.isApproximate() || loc.isApproximate(), "TRAINING")
			: new ResolvedLocation(null, true, "TRAINING");
		long span = Math.max(0, XpTable.xpForLevel(level) - XpTable.xpForLevel(Math.max(1, best.getMinLevel())));
		int duration = xph(best) > 0 ? (int) Math.min(Integer.MAX_VALUE, span * TICKS_PER_HOUR / xph(best)) : 0;
		return Optional.of(PlanNode.builder()
			.id("train:" + sk + ":" + level)
			.type(NodeType.TRAINING)
			.label("Train " + sk + " to " + level + " (" + best.getName() + ")")
			.location(rl)
			.requirement(itemRequirement(best.getItems()))
			.effects(Effects.builder().reachLevel(sk, level).build())
			.durationTicks(duration)
			.build());
	}

	private static long xph(TrainingMethod m)
	{
		return m.getXpPerHour() == null ? 0 : m.getXpPerHour();
	}

	private static Requirement itemRequirement(List<Requirements.ItemReq> items)
	{
		Requirement.Builder b = Requirement.builder();
		for (Requirements.ItemReq i : items)
		{
			if (i == null)
			{
				continue;
			}
			if (i.getIds().isEmpty())
			{
				b.unknown("Item: " + i.getName());
			}
			else
			{
				b.item(new ItemNeed(new LinkedHashSet<>(i.getIds()), i.getQty(), i.getName()));
			}
		}
		return b.build();
	}

	/** Like {@link #acquireItem} but returns null when the item cannot be bought. */
	public PlanNode acquireItemNode(int itemId, int qty)
	{
		return acquireItem(itemId, qty).orElse(null);
	}

	/** Buys the item at its cheapest shop with a known location; empty if there is none. */
	public Optional<PlanNode> acquireItem(int itemId, int qty)
	{
		ItemDef def = null;
		for (ItemDef d : data.getItems())
		{
			if (d != null && d.getId() == itemId)
			{
				def = d;
				break;
			}
		}
		if (def == null)
		{
			return Optional.empty();
		}
		ItemDef.Source best = null;
		ResolvedLocation bestLoc = null;
		for (ItemDef.Source s : def.getSources())
		{
			if (s == null || !"SHOP".equals(s.getType()) || s.getShop() == null)
			{
				continue;
			}
			ResolvedLocation loc = locations.forName(s.getShop(), 0);
			if (!"NAME".equals(loc.getSource()))
			{
				continue;
			}
			if (best == null || price(s) < price(best)
				|| (price(s) == price(best) && s.getShop().compareTo(best.getShop()) < 0))
			{
				best = s;
				bestLoc = loc;
			}
		}
		if (best == null)
		{
			return Optional.empty();
		}
		String name = def.getName() == null ? "item " + itemId : def.getName();
		return Optional.of(PlanNode.builder()
			.id("acquire:" + itemId + ":" + qty)
			.type(NodeType.ACQUIRE_ITEM)
			.label("Buy " + qty + " x " + name + " (" + best.getShop() + ")")
			.location(bestLoc)
			.effects(Effects.builder().item(itemId, qty).build())
			.build());
	}

	private static int price(ItemDef.Source s)
	{
		return s.getPrice() == null ? Integer.MAX_VALUE : s.getPrice();
	}
}
