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
import com.chunkblazer.gps.data.ItemDef;
import com.chunkblazer.gps.data.QuestDef;
import com.chunkblazer.gps.data.Requirements;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.model.ItemNeed;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.model.Requirement;
import com.chunkblazer.gps.model.XpTable;
import com.chunkblazer.gps.travel.TileMath;
import com.chunkblazer.gps.travel.TravelConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses which tasks to plan for a goal and resolves what each one needs beforehand (training, prerequisite
 * quests, purchasable items) into a {@link PrecedenceGraph}. Pure and deterministic: all iteration is over lists,
 * sorted ids or insertion-ordered maps.
 *
 * <p>Simplifications: item providers are bought once and never consumed; quest points, combat level, max hit,
 * locked chunks and unknown requirements have no provider, so anything needing them is reported as unplannable;
 * adjacency of locked chunks is ignored when picking the cheapest chunk to unlock; for {@code MAX_GEAR} each item
 * is worth one point and only items whose own skill and quest requirements are already met are considered.
 * Quest-chain skill providers are judged against the initial state only: {@code needsAtMostOneProvider} checks the
 * quest's own requirements there, not the state projected after other selected work.
 */
public final class Selector
{
	/** Estimated ticks of grind a training node represents; a quest chain must be cheaper than travel plus this. */
	public static final int TRAINING_PENALTY_TICKS = 200;
	private static final int MAX_DEPTH = 4;

	private final PlanRequest req;
	private final NodeFactory factory;
	private final PlayerState state;
	private final Map<Integer, ItemDef> itemsById = new HashMap<>();

	public Selector(PlanRequest req, NodeFactory factory)
	{
		this.req = req;
		this.factory = factory;
		this.state = req.getState();
		if (req.getData() != null)
		{
			for (ItemDef d : req.getData().getItems())
			{
				if (d != null)
				{
					itemsById.putIfAbsent(d.getId(), d);
				}
			}
		}
	}

	public PrecedenceGraph select()
	{
		List<Plan.Unplannable> unplannable = new ArrayList<>();
		Work work = new Work();
		GoalMode goal = req.getGoal();
		switch (goal.getKind())
		{
			case TRAIN_SKILL:
				work = selectTraining(goal, unplannable);
				break;
			case MAX_GEAR:
				work = fill(work, gearCandidates(), 0, unplannable);
				break;
			case UNLOCK_NEXT_CHUNK:
			{
				List<Candidate> scored = scoredTaskCandidates(unplannable);
				int target = unlockTarget();
				if (target > 0)
				{
					work = fill(work, scored, target, unplannable);
				}
				break;
			}
			case MAX_POINTS:
			default:
				work = fill(work, scoredTaskCandidates(unplannable), 0, unplannable);
				break;
		}
		return finish(work, unplannable);
	}

	// ---- goals ----

	private Work selectTraining(GoalMode goal, List<Plan.Unplannable> unplannable)
	{
		Work work = new Work();
		String skill = goal.getSkill();
		if (skill == null || state.getLevel(skill) >= goal.getLevel())
		{
			return work;
		}
		Optional<PlanNode> node = factory.trainingNode(skill, goal.getLevel());
		if (!node.isPresent())
		{
			unplannable.add(new Plan.Unplannable("Train " + skill + " to " + goal.getLevel(),
				skillAtom(skill, goal.getLevel()).missing(state)));
			return work;
		}
		Candidate c = new Candidate(node.get().getId(), node.get().getLabel(), 0, Collections.singletonList(node.get()));
		return fill(work, Collections.singletonList(c), 0, unplannable);
	}

	/** Task points still missing to unlock the cheapest locked chunk; 0 when there is none or it is affordable. */
	private int unlockTarget()
	{
		int cheapest = Integer.MAX_VALUE;
		for (TaskRef ref : req.getTasks())
		{
			if (ref != null && ref.getUnlockCost() > 0 && !state.hasUnlockedChunk(ref.getHomeRegionId()))
			{
				cheapest = Math.min(cheapest, ref.getUnlockCost());
			}
		}
		return cheapest == Integer.MAX_VALUE ? 0 : Math.max(0, cheapest - state.getTaskPoints());
	}

	private List<Candidate> gearCandidates()
	{
		List<Candidate> out = new ArrayList<>();
		if (req.getData() == null)
		{
			return out;
		}
		for (ItemDef d : req.getData().getItems())
		{
			if (d == null || d.getSlot() == null || state.ownedCount(d.getId()) > 0 || !equippable(d))
			{
				continue;
			}
			PlanNode n = factory.acquireItemNode(d.getId(), 1);
			if (n == null)
			{
				continue;
			}
			PlanNode valued = n.toBuilder().points(1).build();
			Candidate c = new Candidate(valued.getId(), valued.getLabel(), 1, Collections.singletonList(valued));
			c.price = cheapestPrice(d);
			c.itemId = d.getId();
			out.add(c);
		}
		out.sort(Comparator.<Candidate>comparingLong(c -> c.price).thenComparingInt(c -> c.itemId));
		return out;
	}

	private boolean equippable(ItemDef d)
	{
		Requirements r = d.getRequirements();
		for (Map.Entry<String, Integer> e : r.getSkills().entrySet())
		{
			if (state.getLevel(e.getKey()) < e.getValue())
			{
				return false;
			}
		}
		for (String q : r.getQuests())
		{
			if (!state.hasQuest(q))
			{
				return false;
			}
		}
		return true;
	}

	// ---- candidates ----

	/** Task candidates scored points / (1 + providerCost), best first; blocked ones go to unplannable. */
	private List<Candidate> scoredTaskCandidates(List<Plan.Unplannable> unplannable)
	{
		List<Candidate> ready = new ArrayList<>();
		for (TaskRef ref : req.getTasks())
		{
			if (ref == null || ref.getTask() == null || req.getCompletedTaskIds().contains(ref.getTask().getTaskId()))
			{
				continue;
			}
			NuzlockeTask t = ref.getTask();
			if (isQuestCheckAlreadyDone(t))
			{
				continue;
			}
			String label = t.getName() == null ? t.getTaskId() : t.getName();
			if (!state.hasUnlockedChunk(ref.getHomeRegionId()))
			{
				unplannable.add(new Plan.Unplannable(label,
					Collections.singletonList(Missing.chunkLocked(ref.getHomeRegionId()))));
				continue;
			}
			Candidate c = new Candidate(t.getTaskId(), label, t.getBasePoints(), nodesFor(ref));
			Work scratch = new Work();
			List<Missing> fail = tryAdd(scratch, c);
			if (fail != null)
			{
				unplannable.add(new Plan.Unplannable(label, fail));
				continue;
			}
			c.score = c.points / (1.0 + Math.max(0, scratch.nodes.size() - c.nodes.size()));
			ready.add(c);
		}
		ready.sort(Comparator.<Candidate>comparingDouble(c -> -c.score).thenComparing(c -> c.key));
		return ready;
	}

	/** A quest-check task whose quest steps are all done (state not yet updated) counts as completed. */
	private boolean isQuestCheckAlreadyDone(NuzlockeTask t)
	{
		TaskConstraints c = t.getConstraints();
		return "QUEST_CHECK".equals(t.getCompletionType()) && c != null && c.getQuest() != null
			&& !state.hasQuest(c.getQuest()) && factory.questFullyDone(c.getQuest());
	}

	private List<PlanNode> nodesFor(TaskRef ref)
	{
		NuzlockeTask t = ref.getTask();
		TaskConstraints c = t.getConstraints();
		if ("QUEST_CHECK".equals(t.getCompletionType()) && c != null && c.getQuest() != null && !state.hasQuest(c.getQuest()))
		{
			List<PlanNode> chain = factory.questChain(c.getQuest(), t.getBasePoints());
			if (!chain.isEmpty())
			{
				return chain;
			}
		}
		return Collections.singletonList(factory.forTask(ref));
	}

	/** Adds candidates in order while they fit under maxNodes and (if target > 0) until the points target is reached. */
	private Work fill(Work work, List<Candidate> candidates, int target, List<Plan.Unplannable> unplannable)
	{
		Work current = work;
		int selected = 0;
		for (Candidate c : candidates)
		{
			if ((target > 0 && selected >= target) || current.nodes.size() >= req.getMaxNodes())
			{
				break;
			}
			Work trial = current.copy();
			List<Missing> fail = tryAdd(trial, c);
			if (fail != null)
			{
				addUnplannable(unplannable, c.label, fail);
				continue;
			}
			if (trial.nodes.size() > req.getMaxNodes())
			{
				continue;
			}
			boolean added = trial.nodes.size() != current.nodes.size() || trial.goals.size() != current.goals.size();
			current = trial;
			if (added)
			{
				selected += c.points; // a candidate whose goals were already selected earns nothing new
			}
		}
		return current;
	}

	private static void addUnplannable(List<Plan.Unplannable> unplannable, String label, List<Missing> missing)
	{
		for (Plan.Unplannable u : unplannable)
		{
			if (u.getLabel().equals(label))
			{
				return;
			}
		}
		unplannable.add(new Plan.Unplannable(label, missing));
	}

	// ---- provider resolution ----

	/**
	 * Adds the candidate's nodes as goals and resolves providers breadth first up to {@link #MAX_DEPTH}. Returns null
	 * on success or the blocking requirements; on failure the work is left dirty and must be discarded.
	 */
	private List<Missing> tryAdd(Work w, Candidate c)
	{
		for (PlanNode n : c.nodes)
		{
			if (w.goals.contains(n.getId()))
			{
				return null; // already selected through another task; nothing more to add
			}
		}
		Deque<Pending> queue = new ArrayDeque<>();
		for (PlanNode n : c.nodes)
		{
			w.nodes.put(n.getId(), n);
			w.goals.add(n.getId());
			w.preds.computeIfAbsent(n.getId(), k -> new LinkedHashSet<>()).addAll(n.getAfter());
			queue.add(new Pending(n, 0));
		}
		while (!queue.isEmpty())
		{
			List<Missing> fail = resolve(w, queue.poll(), queue);
			if (fail != null)
			{
				return fail;
			}
		}
		if (w.upgraded)
		{
			w.collectGarbage();
		}
		return null;
	}

	private List<Missing> resolve(Work w, Pending p, Deque<Pending> queue)
	{
		PlanNode n = p.node;
		Requirement r = n.getRequirement();
		Requirement.Builder rest = Requirement.builder().questPoints(r.getQuestPoints()).combatLevel(r.getCombatLevel())
			.minMaxHit(r.getMinMaxHit());
		r.getChunks().forEach(rest::chunk);
		r.getUnknown().forEach(rest::unknown);
		List<Missing> blocked = rest.build().missing(state);
		if (!blocked.isEmpty())
		{
			return blocked;
		}
		for (Map.Entry<String, Integer> e : r.getSkills().entrySet())
		{
			Requirement atom = skillAtom(e.getKey(), e.getValue());
			if (!atom.missing(state).isEmpty())
			{
				String id = provideSkill(w, p, atom, e.getKey(), e.getValue(), queue);
				if (id == null)
				{
					return atom.missing(state);
				}
				w.addPred(n.getId(), id);
			}
		}
		for (String q : r.getQuests())
		{
			Requirement atom = Requirement.builder().quest(q).build();
			if (!atom.missing(state).isEmpty() && !factory.questFullyDone(q))
			{
				String id = provideQuest(w, p, atom, q, queue);
				if (id == null)
				{
					return atom.missing(state);
				}
				w.addPred(n.getId(), id);
			}
		}
		for (ItemNeed need : r.getItems())
		{
			Requirement atom = Requirement.builder().item(need).build();
			if (!atom.missing(state).isEmpty())
			{
				String id = provideItem(w, p, atom, need);
				if (id == null)
				{
					return atom.missing(state);
				}
				w.addPred(n.getId(), id);
			}
		}
		return null;
	}

	/** An already selected node whose effects satisfy the atom, or null. */
	private String existingProvider(Work w, PlanNode n, Requirement atom)
	{
		for (PlanNode e : w.nodes.values())
		{
			if (e == n || e.getType() == NodeType.TASK || e.getId().equals(n.getId())
				|| (n.getQuestId() != null && n.getQuestId().equals(e.getQuestId())))
			{
				continue;
			}
			if (atom.missing(e.getEffects().apply(state)).isEmpty())
			{
				return e.getId();
			}
		}
		return null;
	}

	private String provideSkill(Work w, Pending p, Requirement atom, String skill, int level, Deque<Pending> queue)
	{
		String existing = existingProvider(w, p.node, atom);
		if (existing != null)
		{
			return existing;
		}
		Optional<PlanNode> t = factory.trainingNode(skill, level);
		if (p.depth + 1 > MAX_DEPTH)
		{
			return null;
		}
		List<PlanNode> offer = questOffer(w, p, skill, level, t);
		if (offer != null)
		{
			return addChain(w, offer, p.depth + 1, queue);
		}
		if (!t.isPresent())
		{
			return null;
		}
		PlanNode old = null;
		for (PlanNode e : w.nodes.values())
		{
			if (e.getType() == NodeType.TRAINING && e.getEffects().getReachLevel().containsKey(skill))
			{
				old = e;
				break;
			}
		}
		if (old != null)
		{
			w.replace(old.getId(), t.get());
		}
		else
		{
			w.nodes.put(t.get().getId(), t.get());
		}
		queue.add(new Pending(t.get(), p.depth + 1));
		return t.get().getId();
	}

	private String provideQuest(Work w, Pending p, Requirement atom, String questId, Deque<Pending> queue)
	{
		String existing = existingProvider(w, p.node, atom);
		if (existing != null)
		{
			return existing;
		}
		if (p.depth + 1 > MAX_DEPTH)
		{
			return null;
		}
		List<PlanNode> chain = factory.questChain(questId, 0);
		if (chain.isEmpty())
		{
			return null;
		}
		return addChain(w, chain, p.depth + 1, queue);
	}

	/** Adds a quest step chain as providers; precedence is on its last step, whose id is returned. */
	private static String addChain(Work w, List<PlanNode> chain, int depth, Deque<Pending> queue)
	{
		for (PlanNode s : chain)
		{
			w.nodes.putIfAbsent(s.getId(), s);
			w.preds.computeIfAbsent(s.getId(), k -> new LinkedHashSet<>()).addAll(s.getAfter());
			queue.add(new Pending(s, depth));
		}
		return chain.get(chain.size() - 1).getId();
	}

	/**
	 * The cheapest quest chain whose rewards cover the xp shortfall for the level and that needs at most one more
	 * provider itself, if it beats training (travel proxy plus {@link #TRAINING_PENALTY_TICKS}); otherwise null.
	 * Choice (lamp) xp valid for the skill counts once, and is added to the chain's last step so the projected state
	 * really contains it.
	 */
	private List<PlanNode> questOffer(Work w, Pending p, String skill, int level, Optional<PlanNode> training)
	{
		if (req.getQuests() == null)
		{
			return null;
		}
		long shortfall = (long) XpTable.xpForLevel(level) - state.getXp(skill);
		double trainingCost = Double.MAX_VALUE;
		if (training.isPresent())
		{
			Tile loc = training.get().getLocation() == null ? null : training.get().getLocation().getTile();
			double travel = loc != null && loc.isKnown() && req.getStart() != null && req.getStart().isKnown()
				? walkProxy(req.getStart(), loc) : 0;
			trainingCost = travel + TRAINING_PENALTY_TICKS;
		}
		List<QuestModel> models = new ArrayList<>(req.getQuests().all());
		models.sort(Comparator.comparing(QuestModel::getId));
		List<PlanNode> best = null;
		double bestCost = trainingCost;
		for (QuestModel q : models)
		{
			if (state.hasQuest(q.getId()) || q.getId().equals(p.node.getQuestId()) || w.hasQuest(q.getId()))
			{
				continue;
			}
			long direct = rewardXp(q, skill);
			long choice = choiceXp(q, skill);
			if (direct + choice < shortfall || !needsAtMostOneProvider(q, skill))
			{
				continue;
			}
			List<PlanNode> chain = new ArrayList<>(factory.questChain(q.getId(), 0));
			if (chain.isEmpty())
			{
				continue;
			}
			double cost = chainProxy(chain);
			if (cost < bestCost)
			{
				bestCost = cost;
				if (direct < shortfall)
				{
					int last = chain.size() - 1;
					PlanNode ln = chain.get(last);
					chain.set(last, ln.toBuilder()
						.effects(ln.getEffects().plus(Effects.builder().xp(skill, choice).build())).build());
				}
				best = chain;
			}
		}
		return best;
	}

	private static double walkProxy(Tile a, Tile b)
	{
		return TileMath.tileDistance(a, b) * TravelConfig.WALK_TICKS_PER_TILE;
	}

	/**
	 * Straight-line walking estimate from the start through the chain's known step tiles in order; each step without a
	 * known tile adds {@link #TRAINING_PENALTY_TICKS}.
	 */
	private double chainProxy(List<PlanNode> chain)
	{
		Tile prev = req.getStart() != null && req.getStart().isKnown() ? req.getStart() : null;
		double total = 0;
		for (PlanNode n : chain)
		{
			Tile t = n.getLocation() == null ? null : n.getLocation().getTile();
			if (t == null || !t.isKnown())
			{
				total += TRAINING_PENALTY_TICKS; // unlocated step: pessimistic, so an unknown chain never wins by default
				continue;
			}
			if (prev != null)
			{
				total += walkProxy(prev, t);
			}
			prev = t;
		}
		return total;
	}

	private static long rewardXp(QuestModel q, String skill)
	{
		long xp = 0;
		if (q.getRewards() != null)
		{
			for (Map.Entry<String, Integer> e : q.getRewards().getXp().entrySet())
			{
				if (e.getKey() != null && e.getKey().equalsIgnoreCase(skill) && e.getValue() != null)
				{
					xp += e.getValue();
				}
			}
		}
		return xp;
	}

	/** The largest single choice reward valid for the skill (empty skill list or "ANY" means every skill). */
	private static long choiceXp(QuestModel q, String skill)
	{
		long best = 0;
		if (q.getRewards() != null)
		{
			for (QuestDef.ChoiceXp c : q.getRewards().getChoiceXp())
			{
				if (c == null)
				{
					continue;
				}
				boolean valid = c.getSkills().isEmpty();
				for (String s : c.getSkills())
				{
					valid |= s != null && (s.equalsIgnoreCase(skill) || s.equalsIgnoreCase("ANY"));
				}
				if (valid)
				{
					best = Math.max(best, c.getAmount());
				}
			}
		}
		return best;
	}

	/** True when the quest's own unmet requirements are none, or exactly one that a skill/quest/item provider covers. */
	private boolean needsAtMostOneProvider(QuestModel q, String providedSkill)
	{
		Requirement r = q.getRequirement();
		Requirement.Builder rest = Requirement.builder().questPoints(r.getQuestPoints()).combatLevel(r.getCombatLevel())
			.minMaxHit(r.getMinMaxHit());
		r.getChunks().forEach(rest::chunk);
		r.getUnknown().forEach(rest::unknown);
		if (!rest.build().missing(state).isEmpty())
		{
			return false;
		}
		int unmet = 0;
		for (Map.Entry<String, Integer> e : r.getSkills().entrySet())
		{
			if (state.getLevel(e.getKey()) < e.getValue())
			{
				unmet++;
				if (e.getKey().equalsIgnoreCase(providedSkill) || !factory.trainingNode(e.getKey(), e.getValue()).isPresent())
				{
					return false;
				}
			}
		}
		for (String pre : r.getQuests())
		{
			if (!state.hasQuest(pre))
			{
				unmet++;
				if (!req.getQuests().get(pre).isPresent())
				{
					return false;
				}
			}
		}
		for (ItemNeed need : r.getItems())
		{
			if (!Requirement.builder().item(need).build().missing(state).isEmpty())
			{
				unmet++;
				boolean buyable = false;
				for (int id : need.getAnyOf())
				{
					buyable |= factory.acquireItem(id, 1).isPresent();
				}
				if (!buyable)
				{
					return false;
				}
			}
		}
		return unmet <= 1;
	}

	private String provideItem(Work w, Pending p, Requirement atom, ItemNeed need)
	{
		String existing = existingProvider(w, p.node, atom);
		if (existing != null)
		{
			return existing;
		}
		int owned = 0;
		for (int id : need.getAnyOf())
		{
			owned += state.ownedCount(id);
		}
		int qty = Math.max(1, need.getQty() - owned);
		List<Integer> ids = new ArrayList<>(need.getAnyOf());
		ids.sort(Comparator.<Integer>comparingLong(id -> itemsById.containsKey(id) ? cheapestPrice(itemsById.get(id)) : Long.MAX_VALUE)
			.thenComparingInt(id -> id));
		for (int id : ids)
		{
			Optional<PlanNode> node = factory.acquireItem(id, qty);
			if (node.isPresent())
			{
				w.nodes.putIfAbsent(node.get().getId(), node.get());
				return node.get().getId();
			}
		}
		return null;
	}

	private static long cheapestPrice(ItemDef d)
	{
		long best = Long.MAX_VALUE;
		for (ItemDef.Source s : d.getSources())
		{
			if (s != null && "SHOP".equals(s.getType()))
			{
				best = Math.min(best, s.getPrice() == null ? Integer.MAX_VALUE : s.getPrice());
			}
		}
		return best;
	}

	private static Requirement skillAtom(String skill, int level)
	{
		return Requirement.builder().skill(skill, level).build();
	}

	// ---- result ----

	/** Builds the graph, repeatedly dropping precedence cycles (and what depends on them) into unplannable. */
	private PrecedenceGraph finish(Work work, List<Plan.Unplannable> unplannable)
	{
		PrecedenceGraph g = work.toGraph(unplannable);
		while (true)
		{
			List<String> cycle = g.findCycle();
			if (cycle.isEmpty())
			{
				return g;
			}
			String text = "precedence cycle: " + String.join(" -> ", cycle) + " -> " + cycle.get(0);
			Set<String> drop = new LinkedHashSet<>(cycle);
			boolean grew = true;
			while (grew)
			{
				grew = false;
				for (Map.Entry<String, LinkedHashSet<String>> e : work.preds.entrySet())
				{
					if (!drop.contains(e.getKey()) && work.nodes.containsKey(e.getKey())
						&& !Collections.disjoint(e.getValue(), drop))
					{
						drop.add(e.getKey());
						grew = true;
					}
				}
			}
			for (String id : drop)
			{
				unplannable.add(new Plan.Unplannable(work.nodes.get(id).getLabel(),
					Collections.singletonList(new Missing(Missing.Kind.UNKNOWN, text)), id));
			}
			work.remove(drop);
			g = work.toGraph(unplannable);
		}
	}

	// ---- helpers ----

	private static final class Candidate
	{
		final String key;
		final String label;
		final int points;
		final List<PlanNode> nodes;
		double score;
		long price;
		int itemId;

		Candidate(String key, String label, int points, List<PlanNode> nodes)
		{
			this.key = key;
			this.label = label;
			this.points = points;
			this.nodes = nodes;
		}
	}

	private static final class Pending
	{
		final PlanNode node;
		final int depth;

		Pending(PlanNode node, int depth)
		{
			this.node = node;
			this.depth = depth;
		}
	}

	/** Mutable selection under construction: nodes in insertion order, precedence edges, and which nodes are goals. */
	private static final class Work
	{
		final LinkedHashMap<String, PlanNode> nodes = new LinkedHashMap<>();
		final LinkedHashMap<String, LinkedHashSet<String>> preds = new LinkedHashMap<>();
		final LinkedHashSet<String> goals = new LinkedHashSet<>();
		boolean upgraded;

		boolean hasQuest(String questId)
		{
			for (PlanNode n : nodes.values())
			{
				if (questId.equals(n.getQuestId()))
				{
					return true;
				}
			}
			return false;
		}

		Work copy()
		{
			Work c = new Work();
			c.nodes.putAll(nodes);
			preds.forEach((k, v) -> c.preds.put(k, new LinkedHashSet<>(v)));
			c.goals.addAll(goals);
			c.upgraded = upgraded;
			return c;
		}

		void addPred(String node, String pred)
		{
			if (!node.equals(pred))
			{
				preds.computeIfAbsent(node, k -> new LinkedHashSet<>()).add(pred);
			}
		}

		/** Swaps a provider for a replacement, redirecting everything that depended on it. */
		void replace(String oldId, PlanNode replacement)
		{
			nodes.remove(oldId);
			preds.remove(oldId);
			nodes.put(replacement.getId(), replacement);
			for (Map.Entry<String, LinkedHashSet<String>> e : preds.entrySet())
			{
				if (e.getValue().remove(oldId))
				{
					e.getValue().add(replacement.getId());
				}
			}
			if (goals.remove(oldId))
			{
				goals.add(replacement.getId());
			}
			upgraded = true;
		}

		void remove(Set<String> ids)
		{
			ids.forEach(id ->
			{
				nodes.remove(id);
				preds.remove(id);
				goals.remove(id);
			});
			preds.values().forEach(v -> v.removeAll(ids));
			collectGarbage();
		}

		/** Drops provider nodes nothing depends on any more. */
		void collectGarbage()
		{
			boolean changed = true;
			while (changed)
			{
				changed = false;
				Set<String> used = new LinkedHashSet<>();
				for (Map.Entry<String, LinkedHashSet<String>> e : preds.entrySet())
				{
					if (nodes.containsKey(e.getKey()))
					{
						used.addAll(e.getValue());
					}
				}
				for (String id : new ArrayList<>(nodes.keySet()))
				{
					if (!goals.contains(id) && !used.contains(id))
					{
						nodes.remove(id);
						preds.remove(id);
						changed = true;
					}
				}
			}
		}

		PrecedenceGraph toGraph(List<Plan.Unplannable> unplannable)
		{
			Map<String, List<String>> p = new LinkedHashMap<>();
			preds.forEach((k, v) -> p.put(k, new ArrayList<>(v)));
			return PrecedenceGraph.of(new ArrayList<>(nodes.values()), p, unplannable, goals);
		}
	}
}
