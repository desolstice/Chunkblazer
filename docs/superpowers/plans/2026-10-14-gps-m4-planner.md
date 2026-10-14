# Progression GPS M4: Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an ordered plan (tasks, quest steps, training blocks, item and teleport acquisitions) that minimises total travel ticks subject to precedence, with quests as interleavable step chains whose rewards feed later requirements, and with re-planning from the current position.

**Architecture:** Pure-Java package `com.chunkblazer.gps.plan`. `NodeFactory` turns tasks/quests/training into `PlanNode`s (location, requirement, effects). `Selector` picks nodes for a goal and resolves every unmet requirement to a provider node, yielding a static precedence graph. `Solver` orders nodes on the M3 `CostMatrix` (nearest-neighbour over ready nodes, then precedence-checked 2-opt/or-opt, seeded, time-bounded, open path). `TeleportAcquirer` adds buy/make steps when travel saved clearly exceeds the detour. `Planner` is the facade; `Replanner` restarts from the current position after progress.

**Tech Stack:** Java 11, JUnit 5, Lombok (existing). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 5.2, 7). Depends on M1 (`GpsData`), M2 (`PlayerState`, `Requirement`, `RequirementDeriver`, `QuestCatalog`, `EligibilityEngine`), M3 (`CostMatrix`, `TravelContext`, `LocationResolver`, `TransportModel`).

## Global Constraints

- Objective is total travel ticks only; task/skilling/combat duration is informational (`PlanNode.durationTicks`) and never enters the cost.
- Open path from the current position (not a cycle). Deterministic for a given input and `seed`. Solve time bounded by `deadlineMillis` (default 800 ms) and run off the client thread by callers.
- Heuristic, no optimality guarantee. Do not add exact solvers or collision pathfinding.
- Quest steps are ordinary precedence-constrained nodes; step N+1 must come after step N and nothing forces them to be adjacent.
- Plans may include buying/making teleport items the player does not own as explicit `ACQUIRE_TELEPORT` steps.
- Unknown requirements are surfaced (node excluded and reported with its missing list), never assumed met.
- No client dependencies in `gps.plan`. Java release 11, tabs, braces on own lines, license header as in `QuestRequirements.java`.

## Review Focus

- Requirement with no provider (e.g. an UNKNOWN varbit, or a skill with no training method data): node must be dropped and listed as unplannable with its missing requirements, not crash or loop. Task 3 test.
- Quest whose steps have unknown locations (`approximate` / null tile): the chain must still order correctly, with null-tile steps carried at the previous location, and flagged approximate in the output. Task 2/5 tests.
- Precedence cycle in selected data (quest prerequisite loop, or two nodes each needing the other): solver must terminate and report the cycle members, not hang. Task 3/4 tests.
- Empty selection or a single node: plan is empty or a single step with total 0; no index errors. Task 4/7 tests.
- Deadline hit mid-optimisation: returned plan must still satisfy every precedence constraint (the best valid order found so far). Task 4 test.

## File Structure

New under `src/main/java/com/chunkblazer/gps/plan/` (tests mirror under `src/test/java/com/chunkblazer/gps/plan/`):
- `NodeType.java`, `Effects.java`, `PlanNode.java`, `PlanStep.java`, `Plan.java`, `GoalMode.java`, `TaskRef.java`, `PlanRequest.java`
- `NodeFactory.java` — tasks, quest chains, training and acquisition nodes
- `Selector.java`, `PrecedenceGraph.java` — selection and provider resolution
- `Solver.java` — NN + local search
- `TeleportAcquirer.java`
- `Planner.java`, `Replanner.java`
- `KillTimeCheck.java` (optional last task, spec: later milestone)
- Modify: `src/main/java/com/chunkblazer/gps/model/PlayerState.java` (add `taskPoints`)

## Interfaces defined here and used by M5 (do not rename)

```java
enum NodeType { TASK, QUEST_STEP, TRAINING, ACQUIRE_ITEM, ACQUIRE_TELEPORT }
Effects { Map<String,Long> xp; Map<String,Integer> reachLevel; Map<String,Integer> questsCompleted /*id -> quest points*/;
          Map<Integer,Integer> items; Set<String> unlocks; int taskPoints;
          static Effects NONE; PlayerState apply(PlayerState s); Effects plus(Effects o) }
PlanNode { String id; NodeType type; String label; ResolvedLocation location; Requirement requirement; Effects effects;
           List<String> after; int points; int durationTicks; String questId; Integer stepIndex; String taskId }
GoalMode { MAX_POINTS, UNLOCK_NEXT_CHUNK, TRAIN_SKILL, MAX_GEAR }  // class with static factories: GoalMode.maxPoints(), .unlockNextChunk(), .trainSkill(skill, level), .maxGear()
TaskRef { NuzlockeTask task; int homeRegionId; int unlockCost /*of home chunk, 0 if free*/ }
PlanRequest { PlayerState state; Tile start; GoalMode goal; List<TaskRef> tasks; Set<String> completedTaskIds;
              GpsData data; QuestCatalog quests; RequirementDeriver deriver; ChunkGraph graph; TransportModel transport;
              int maxNodes /*default 300*/; long seed /*default 1*/; long deadlineMillis /*default 800*/; boolean allowTeleportAcquisition }
PlanStep { int order; NodeType type; String label; Tile location; boolean approximate; int travelTicks; String reason;
           List<Missing> missingRequirements; String questId; Integer stepIndex; List<String> viaTransportEdgeIds }
Plan { List<PlanStep> steps; int totalTravelTicks; List<Unplannable> unplannable; String dataVersion }   Unplannable { String label; List<Missing> missing }
Planner(PlanRequest).plan() -> Plan;  Replanner.replan(Plan previous, PlanRequest base, PlayerState newState, Tile position, Set<String> doneNodeIds) -> Plan
```

---

### Task 1: Effects, nodes, and `PlayerState.taskPoints`

**Files:**
- Create: `NodeType.java`, `Effects.java`, `PlanNode.java` (in `gps/plan/`)
- Modify: `src/main/java/com/chunkblazer/gps/model/PlayerState.java` (add `getTaskPoints()`, `withTaskPoints(int delta)`, builder `taskPoints(int)`)
- Test: `src/test/java/com/chunkblazer/gps/plan/EffectsTest.java`, add a case to `PlayerStateTest`

**Interfaces:** Produces `Effects` as in the interface block. `apply` order: `reachLevel` (raise XP to `XpTable.xpForLevel(level)` only if currently lower), then `xp` deltas via `withXp`, then `questsCompleted` via `withQuestComplete(id, qp)`, then `items` via `withItem`, then `unlocks` via `withUnlock`, then `taskPoints` via `withTaskPoints`. `plus` unions maps (summing xp/items/points, max for reachLevel).

- [ ] **Step 1: Write the failing tests**

```java
class EffectsTest
{
	@Test
	void reachLevelRaisesButNeverLowers()
	{
		PlayerState s = PlayerState.builder().level("COOKING", 50).build();
		Effects e = Effects.builder().reachLevel("COOKING", 30).reachLevel("MINING", 20).build();
		PlayerState r = e.apply(s);
		assertEquals(50, r.getLevel("COOKING"));
		assertEquals(20, r.getLevel("MINING"));
	}

	@Test
	void xpQuestItemsUnlocksPointsAllApply()
	{
		Effects e = Effects.builder().xp("COOKING", 300).questComplete("COOKS_ASSISTANT", 1).item(1944, 2)
			.unlock("TELEPORT:Explorer's ring").taskPoints(3).build();
		PlayerState r = e.apply(PlayerState.freshAccount());
		assertTrue(r.getLevel("COOKING") >= 5);
		assertTrue(r.hasQuest("COOKS_ASSISTANT"));
		assertEquals(1, r.getQuestPoints());
		assertEquals(2, r.ownedCount(1944));
		assertTrue(r.hasUnlock("TELEPORT:Explorer's ring"));
		assertEquals(3, r.getTaskPoints());
	}

	@Test
	void plusCombines()
	{
		Effects a = Effects.builder().xp("COOKING", 100).taskPoints(1).reachLevel("MINING", 10).build();
		Effects b = Effects.builder().xp("COOKING", 50).taskPoints(2).reachLevel("MINING", 15).build();
		Effects c = a.plus(b);
		assertEquals(150L, (long) c.getXp().get("COOKING"));
		assertEquals(3, c.getTaskPoints());
		assertEquals(15, (int) c.getReachLevel().get("MINING"));
	}

	@Test
	void noneIsIdentity()
	{
		PlayerState s = PlayerState.freshAccount();
		assertEquals(s.getLevel("ATTACK"), Effects.NONE.apply(s).getLevel("ATTACK"));
	}
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "com.chunkblazer.gps.plan.EffectsTest"` — Expected: compile errors.
- [ ] **Step 3: Implement** `Effects` as an immutable class with a `Builder` (methods `xp`, `reachLevel`, `questComplete(id, qp)`, `item`, `unlock`, `taskPoints`, `build`), `apply`, `plus`, `static final Effects NONE`. Add `taskPoints` to `PlayerState` (default 0, copied by every `with*`, added to the builder).
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.*"` — Expected: PASS (M2/M3 tests unaffected).
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): plan Effects/PlanNode types and PlayerState.taskPoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 2: `NodeFactory` (tasks, quest chains, training, acquisitions)

**Files:**
- Create: `TaskRef.java`, `NodeFactory.java`
- Test: `src/test/java/com/chunkblazer/gps/plan/NodeFactoryTest.java`

**Interfaces:**
- Consumes: `RequirementDeriver.derive`, `LocationResolver` (M3), `QuestCatalog`/`QuestModel`/`QuestStep`, `GpsData.getTraining()`, `GpsData.getItems()`.
- Produces `NodeFactory(GpsData data, QuestCatalog quests, RequirementDeriver deriver, LocationResolver locations)` with:
  - `PlanNode forTask(TaskRef ref)`: id `task:<taskId>`, type `TASK`, location `locations.forTask`, requirement from the deriver, effects `taskPoints = basePoints`, `points = basePoints`, `durationTicks` = `constraints.time_in_ticks[0]` else 0. A `QUEST_CHECK` task is NOT turned into a single node here (see `questChain`).
  - `List<PlanNode> questChain(String questId, int completionTaskPoints)`: one `QUEST_STEP` node per `QuestStep`: id `quest:<ID>:<index>`, `after = [previous step id]`, location `locations.forQuestStep`, requirement: step 0 carries the quest's own `Requirement` (skills, quests, quest points), later steps `Requirement.NONE`; the FINAL step's effects = quest rewards (`xp`, `questComplete(id, rewards.questPoints)`, unlock tokens `"<TYPE>:<name>"` for each reward unlock) plus `taskPoints = completionTaskPoints`, and `points = completionTaskPoints`. A quest with no steps (helper-only) yields a single atomic node `quest:<ID>:0` at `forTask` location semantics (`forQuestStep` fallback chunk centre) with the same final-step effects. Steps whose location tile is null (source NONE) get the previous step's tile marked approximate (carry); the first step falls back to the quest chunk centre; if even that is unavailable the node keeps a null tile and `location.source == "NONE"` (Selector excludes it as unplannable).
  - `PlanNode trainingNode(String skill, int level)`: best `TrainingMethod` for `skill` covering the level range around `level` (the highest `xpPerHour`, ties by lowest `minLevel`); id `train:<SKILL>:<level>`, requirement = the method's item needs, effects `reachLevel(skill, level)`, `durationTicks` = estimated from `xpPerHour` (informational), location from the method (null location -> `approximate`, tile = null, caller carries). With no method data returns `Optional.empty()` so the caller reports a missing provider. Signature `Optional<PlanNode> trainingNode(String skill, int level)`.
  - `PlanNode acquireItemNode(int itemId, int qty)` / `Optional<PlanNode> acquireItem(int itemId, int qty)`: from `ItemDef.sources`: SHOP -> location of the shop (`forName(shop, ...)`), requirement `NONE`, effects `item(itemId, qty)`; no SHOP source -> `Optional.empty()`.

- [ ] **Step 1: Write the failing tests** (fixtures from `GpsTestFixtures`; add `GpsTestFixtures.dataWithAll(transport, items, locations, quests, training)` if not present):

```java
class NodeFactoryTest
{
	private NodeFactory factory(GpsData d, Map<String, QuestHelperEntry> helper)
	{
		QuestCatalog q = QuestCatalog.build(d, helper);
		return new NodeFactory(d, q, new RequirementDeriver(q, Overrides.empty(), d), new LocationResolver(d, q));
	}

	@Test
	void taskNodeCarriesPointsAndRequirement()
	{
		GpsData d = GpsData.empty(null);
		NodeFactory f = factory(d, Collections.emptyMap());
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30,\"base_points\":3}", NuzlockeTask.class);
		PlanNode n = f.forTask(new TaskRef(t, 12850, 0));
		assertEquals("task:c", n.getId());
		assertEquals(3, n.getPoints());
		assertEquals(3, n.getEffects().getTaskPoints());
		assertEquals(30, (int) n.getRequirement().getSkills().get("COOKING"));
		assertTrue(n.getLocation().isApproximate());
	}

	@Test
	void questChainHasOrderedStepsRewardsOnLastStepRequirementOnFirst()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // TEST_QUEST: 2 steps, 300 cooking xp, requires COOKING 10
		NodeFactory f = factory(d, Collections.singletonMap("TEST_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, null)));
		List<PlanNode> chain = f.questChain("TEST_QUEST", 1);
		assertEquals(2, chain.size());
		assertEquals(Collections.emptyList(), chain.get(0).getAfter());
		assertEquals(Collections.singletonList(chain.get(0).getId()), chain.get(1).getAfter());
		assertEquals(10, (int) chain.get(0).getRequirement().getSkills().get("COOKING"));
		assertTrue(chain.get(1).getRequirement().getSkills().isEmpty());
		assertTrue(chain.get(0).getEffects().getQuestsCompleted().isEmpty());
		assertTrue(chain.get(1).getEffects().getQuestsCompleted().containsKey("TEST_QUEST"));
		assertEquals(300L, (long) chain.get(1).getEffects().getXp().get("COOKING"));
		assertEquals(1, chain.get(1).getPoints());
		assertEquals(0, chain.get(0).getPoints());
	}

	@Test
	void unknownStepLocationCarriesPreviousAndIsApproximate()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // step 1 has no location
		NodeFactory f = factory(d, Collections.emptyMap());
		List<PlanNode> chain = f.questChain("TEST_QUEST", 1);
		assertEquals(chain.get(0).getLocation().getTile(), chain.get(1).getLocation().getTile());
		assertTrue(chain.get(1).getLocation().isApproximate());
	}

	@Test
	void helperOnlyQuestIsSingleAtomicNode()
	{
		GpsData d = GpsData.empty(null);
		NodeFactory f = factory(d, Collections.singletonMap("OLD_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, Collections.singletonMap("MINING", 5))));
		List<PlanNode> chain = f.questChain("OLD_QUEST", 1);
		assertEquals(1, chain.size());
		assertTrue(chain.get(0).getEffects().getQuestsCompleted().containsKey("OLD_QUEST"));
		assertEquals(5, (int) chain.get(0).getRequirement().getSkills().get("MINING"));
	}

	@Test
	void trainingNodePicksBestMethodAndEmptyWithoutData()
	{
		GpsData d = GpsTestFixtures.dataWithTraining("{\"schema\":1,\"methods\":[{\"skill\":\"COOKING\",\"name\":\"Slow\",\"minLevel\":1,\"maxLevel\":99,\"xpPerHour\":1000,\"location\":null,\"items\":[],\"approximate\":true},"
			+ "{\"name\":\"Fast\",\"skill\":\"COOKING\",\"minLevel\":1,\"maxLevel\":99,\"xpPerHour\":50000,\"location\":null,\"items\":[],\"approximate\":true}]}");
		Optional<PlanNode> n = factory(d, Collections.emptyMap()).trainingNode("COOKING", 40);
		assertTrue(n.isPresent());
		assertTrue(n.get().getLabel().contains("Fast"));
		assertEquals(40, (int) n.get().getEffects().getReachLevel().get("COOKING"));
		assertFalse(factory(GpsData.empty(null), Collections.emptyMap()).trainingNode("COOKING", 40).isPresent());
	}

	@Test
	void acquireItemNeedsAShopSource()
	{
		GpsData d = GpsTestFixtures.dataWith("{\"schema\":1,\"edges\":[]}",
			"{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Aubury's Rune Shop\",\"price\":17}],\"teleport\":null}]}",
			"{\"schema\":1,\"entries\":[{\"name\":\"Aubury's Rune Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}");
		NodeFactory f = factory(d, Collections.emptyMap());
		Optional<PlanNode> n = f.acquireItem(554, 10);
		assertTrue(n.isPresent());
		assertEquals(10, (int) n.get().getEffects().getItems().get(554));
		assertEquals(3253, (int) n.get().getLocation().getTile().getX());
		assertFalse(f.acquireItem(999, 1).isPresent());
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** per the interface description.
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.plan.NodeFactoryTest"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): NodeFactory for tasks, quest step chains, training and acquisitions

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 3: `Selector` and `PrecedenceGraph` (goals, provider resolution)

**Files:**
- Create: `GoalMode.java`, `PrecedenceGraph.java`, `Selector.java`, `PlanRequest.java`, `Plan.java`, `PlanStep.java` (types only), `Unplannable` nested in `Plan`
- Test: `src/test/java/com/chunkblazer/gps/plan/SelectorTest.java`

**Interfaces:**
- Consumes: `NodeFactory`, `Requirement.missing`, `PlayerState`, `Effects.apply`.
- Produces:
  - `PrecedenceGraph { List<PlanNode> nodes; Map<String,List<String>> preds; List<Plan.Unplannable> unplannable }` with `static PrecedenceGraph`-building done by `Selector`; `boolean hasCycle()` returns the members of a cycle via `List<String> findCycle()` (empty if none).
  - `Selector(PlanRequest req, NodeFactory factory)` with `PrecedenceGraph select()`.
- Selection algorithm:
  1. Candidates: every `TaskRef` not in `completedTaskIds`, node via `forTask` (or `questChain` for `QUEST_CHECK` tasks whose `constraints.quest` is not already complete in state); skip candidates whose home region is not in `state.getUnlockedChunks()` (listed as unplannable with a `CHUNK` Missing).
  2. Score each candidate `points / (1 + providerCost)` where `providerCost` = number of provider nodes needed (0 when ready now); sort by score desc then id; take at most `maxNodes` nodes counting chain steps and providers.
  3. Goals: `MAX_POINTS` as above. `UNLOCK_NEXT_CHUNK`: target = the minimum positive `unlockCost` among `TaskRef`s whose home chunk is locked and adjacent (use the cheapest `TaskRef.unlockCost` among locked refs) minus `state.getTaskPoints()`; take candidates by score until the selected points reach the target. `TRAIN_SKILL(skill, level)`: only the training node (plus whatever its requirement needs). `MAX_GEAR`: candidates are `ACQUIRE_ITEM` nodes for equippable items (`ItemDef.slot != null`) that have a SHOP source and whose requirement is satisfiable; value = 1 each (documented simplification; ordering by price ascending).
  4. Provider resolution (for each selected node, repeat until stable, depth limit 4): simulate the projected state as `state` + effects of all already-selected nodes that are certain predecessors (providers); for each `Missing` of the node's requirement against the projected-with-providers state: SKILL -> `trainingNode(skill, level)` (a provider is added once per skill at the highest level any selected node needs; later nodes reuse it); QUEST -> `questChain(q)` of the prerequisite quest (recursively resolved); ITEM -> `acquireItem(id of the cheapest anyOf, qty)`; QUEST_POINTS/COMBAT_LEVEL/MAX_HIT/CHUNK/UNKNOWN -> no provider: the node and everything depending on it are dropped into `unplannable` with the missing list (`UNKNOWN` kept visible). Each provider adds `preds[node] += provider.lastStepId` (for a quest chain the LAST step, since rewards apply there).
  5. Cycles: if `findCycle()` is non-empty after resolution, drop the cycle's nodes (and dependents) into `unplannable` with an UNKNOWN Missing "precedence cycle: a -> b -> a" and continue.
- Chains: the chain's internal `after` edges are copied into `preds`.

- [ ] **Step 1: Write the failing tests** (small synthetic worlds: `SelectorTestSupport.request(state, tasksJson...)` builds a `PlanRequest` with `GpsData.empty` or fixture data):

```java
class SelectorTest
{
	@Test
	void readyTaskSelectedWithNoProviders()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12850).state(unlocked(12850)).select();
		assertEquals(1, g.getNodes().size());
		assertTrue(g.getPreds().get("task:a").isEmpty());
	}

	@Test
	void missingSkillAddsTrainingProviderAndPrecedence()
	{
		SelectorTestSupport s = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).state(unlocked(12850))
			.withTrainingMethod("COOKING", "Fast", 1, 99, 50000);
		PrecedenceGraph g = s.select();
		assertEquals(2, g.getNodes().size());
		assertEquals(Collections.singletonList("train:COOKING:30"), g.getPreds().get("task:a"));
	}

	@Test
	void sharedTrainingProviderRaisedToHighestLevelNeeded()
	{
		SelectorTestSupport s = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).withTask("b", "COOKING", 50, 5, 12850)
			.state(unlocked(12850)).withTrainingMethod("COOKING", "Fast", 1, 99, 50000);
		PrecedenceGraph g = s.select();
		long trains = g.getNodes().stream().filter(n -> n.getType() == NodeType.TRAINING).count();
		assertEquals(1, trains);
		assertEquals("train:COOKING:50", g.getNodes().stream().filter(n -> n.getType() == NodeType.TRAINING).findFirst().get().getId());
		assertEquals(Collections.singletonList("train:COOKING:50"), g.getPreds().get("task:a"));
	}

	@Test
	void noTrainingDataMakesTaskUnplannableWithMissingSkill()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 30, 5, 12850).state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(1, g.getUnplannable().size());
		assertEquals(Missing.Kind.SKILL, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void unknownRequirementIsUnplannableNotAssumedMet()
	{
		PrecedenceGraph g = new SelectorTestSupport().withRawTask("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\",\"base_points\":5}", 12850)
			.state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.UNKNOWN, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void lockedChunkTaskIsUnplannableWithChunkMissing()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12851).state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertEquals(Missing.Kind.CHUNK, g.getUnplannable().get(0).getMissing().get(0).getKind());
	}

	@Test
	void completedTasksAreSkipped()
	{
		PrecedenceGraph g = new SelectorTestSupport().withTask("a", "COOKING", 1, 5, 12850).completed("a").state(unlocked(12850)).select();
		assertTrue(g.getNodes().isEmpty());
		assertTrue(g.getUnplannable().isEmpty());
	}

	@Test
	void maxNodesCapsSelectionKeepingBestScores()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850)).maxNodes(2);
		for (int i = 0; i < 5; i++) s.withTask("t" + i, "COOKING", 1, i + 1, 12850);
		PrecedenceGraph g = s.select();
		assertEquals(2, g.getNodes().size());
		assertTrue(g.getNodes().stream().anyMatch(n -> n.getId().equals("task:t4")));
		assertTrue(g.getNodes().stream().anyMatch(n -> n.getId().equals("task:t3")));
	}

	@Test
	void unlockNextChunkGoalStopsAtTargetPoints()
	{
		SelectorTestSupport s = new SelectorTestSupport().state(unlocked(12850).withTaskPoints(1)).goal(GoalMode.unlockNextChunk())
			.withLockedChunkCost(12851, 6);
		for (int i = 0; i < 6; i++) s.withTask("t" + i, "COOKING", 1, 2, 12850);
		PrecedenceGraph g = s.select();
		int pts = g.getNodes().stream().mapToInt(PlanNode::getPoints).sum();
		assertTrue(pts >= 5 && pts <= 6, "points " + pts); // need 6 - 1 = 5, tasks give 2 each -> 3 tasks = 6
	}

	@Test
	void precedenceCycleIsReportedNotHung()
	{
		PrecedenceGraph g = PrecedenceGraph.of(
			Arrays.asList(node("x"), node("y")),
			map("x", "y", "y", "x"));
		assertEquals(new HashSet<>(Arrays.asList("x", "y")), new HashSet<>(g.findCycle()));
	}
}
```

(`SelectorTestSupport`, `unlocked(int...)` -> `PlayerState`, `node(id)` -> bare `PlanNode`, `map(...)` helpers are test-only classes in the same package; they construct requests with `GpsData.empty` plus the optional fixtures. Unlocked-chunk handling: `unlocked(12850)` returns a `PlayerState.Builder`-built state with that chunk; `withLockedChunkCost` registers the `unlockCost` on `TaskRef`s for tasks homed in that chunk. For the goal test the points target comes from the cheapest locked chunk cost, so add one locked-chunk `TaskRef` with a dummy task homed in 12851 and `unlockCost 6`; it is excluded from the selection itself because its chunk is locked, but contributes the target.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** types and `Selector` as specified. Keep provider resolution in a private method `resolve(PlanNode, ...)` that is iterative with a work queue and a `depth` map; use `LinkedHashMap`/sorted ids everywhere so output is deterministic.
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.plan.SelectorTest"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): Selector with goal modes and provider resolution

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 4: `Solver` (precedence-aware NN + 2-opt/or-opt)

**Files:**
- Create: `Solver.java`
- Test: `src/test/java/com/chunkblazer/gps/plan/SolverTest.java`

**Interfaces:**
- Consumes: a cost function `int cost(int from, int to)` over indices where index `0` is the START node and `1..n` are the plan nodes in `PrecedenceGraph.nodes` order; `preds` as `int[][]` (indices into `1..n`).
- Produces: `Solver` with `static int[] solve(int n, IntBinaryOperator cost, int[][] preds, long seed, long deadlineMillis)` returning the visiting order as node indices `1..n` (length n), and `static long pathCost(int[] order, IntBinaryOperator cost)` (start index 0 then the order; `INF` edges saturate), `static boolean valid(int[] order, int[][] preds)`. Unreachable pairs (cost >= `INF`) are allowed as long as no cheaper valid order exists; the caller reports a plan whose cost is `>= INF` as infeasible.
- Algorithm:
  1. Construction: from START, repeatedly choose among READY nodes (all preds placed) the one with minimum `cost(current, node)`, ties by lowest index. If no node is ready while nodes remain (cycle, defensive), append remaining by index and let `valid` report false so the facade drops them.
  2. Local search until no improvement or deadline: (a) 2-opt: for segment `[i..j]`, reversing it is allowed only if no precedence pair `(p,q)` has both endpoints inside the segment (their order would flip); accept if `pathCost` improves. (b) or-opt: move a block of 1-3 consecutive nodes to another position if every moved node still comes after all its preds and before all its successors. Use `seed` (via `java.util.Random(seed)`) only to shuffle the iteration order of start positions; results are deterministic for a fixed seed.
  3. The time check uses `System.nanoTime()` every 256 move evaluations; on timeout return the current (valid) order.
  4. The open path means no cost for returning to START.

- [ ] **Step 1: Write the failing tests**

```java
class SolverTest
{
	// nodes on a line: index 0 is START at x=0; node k at position pos[k]
	private static IntBinaryOperator lineCost(int[] pos)
	{
		return (a, b) -> Math.abs(pos[a] - pos[b]);
	}

	private static int[][] noPreds(int n)
	{
		int[][] p = new int[n + 1][];
		for (int i = 0; i <= n; i++) p[i] = new int[0];
		return p;
	}

	@Test
	void visitsAllNodesExactlyOnce()
	{
		int[] pos = {0, 30, 10, 20};
		int[] order = Solver.solve(3, lineCost(pos), noPreds(3), 1, 500);
		assertEquals(3, order.length);
		assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), Arrays.stream(order).boxed().collect(Collectors.toSet()));
	}

	@Test
	void openPathOnALineIsOptimal()
	{
		int[] pos = {0, 30, 10, 20};
		int[] order = Solver.solve(3, lineCost(pos), noPreds(3), 1, 500);
		assertEquals(30, Solver.pathCost(order, lineCost(pos)));
	}

	@Test
	void precedenceRespectedEvenWhenItCostsMore()
	{
		int[] pos = {0, 10, 20, 30};      // node1@10 must come after node3@30
		int[][] preds = noPreds(3);
		preds[1] = new int[]{3};
		int[] order = Solver.solve(3, lineCost(pos), preds, 1, 500);
		assertTrue(Solver.valid(order, preds));
		assertTrue(indexOf(order, 3) < indexOf(order, 1));
	}

	@Test
	void questChainInterleavesNearbyTask()
	{
		// START@0; quest step1@10 (node1), step2@100 (node2, after node1); task@55 (node3) lies between
		int[] pos = {0, 10, 100, 55};
		int[][] preds = noPreds(3);
		preds[2] = new int[]{1};
		int[] order = Solver.solve(3, lineCost(pos), preds, 1, 500);
		assertArrayEquals(new int[]{1, 3, 2}, order); // task done between the two quest steps
		assertEquals(100, Solver.pathCost(order, lineCost(pos)));
	}

	@Test
	void localSearchIsNoWorseThanNearestNeighbourAndCloseToOptimum()
	{
		int[][] pts = {{0, 0}, {10, 0}, {0, 10}, {11, 1}, {1, 11}, {5, 5}};
		IntBinaryOperator c = (a, b) -> (int) Math.round(Math.hypot(pts[a][0] - pts[b][0], pts[a][1] - pts[b][1]));
		int[] order = Solver.solve(5, c, noPreds(5), 3, 1000);
		long nn = Solver.pathCost(Solver.construct(5, c, noPreds(5)), c);
		long cost = Solver.pathCost(order, c);
		assertTrue(cost <= nn, cost + " vs nn " + nn);
		assertTrue(cost <= Math.round(bruteForce(5, c) * 1.1), cost + " vs optimum " + bruteForce(5, c));
	}

	private static long bruteForce(int n, IntBinaryOperator c)
	{
		return permute(new int[n], new boolean[n + 1], 0, n, c, Long.MAX_VALUE);
	}

	private static long permute(int[] cur, boolean[] used, int k, int n, IntBinaryOperator c, long best)
	{
		if (k == n)
		{
			return Math.min(best, Solver.pathCost(cur, c));
		}
		for (int v = 1; v <= n; v++)
		{
			if (!used[v])
			{
				used[v] = true;
				cur[k] = v;
				best = permute(cur, used, k + 1, n, c, best);
				used[v] = false;
			}
		}
		return best;
	}

	@Test
	void deterministicForSameSeed()
	{
		Random r = new Random(5);
		int[] pos = new int[41];
		for (int i = 1; i <= 40; i++) pos[i] = r.nextInt(1000);
		int[] a = Solver.solve(40, lineCost(pos), noPreds(40), 9, 2000);
		int[] b = Solver.solve(40, lineCost(pos), noPreds(40), 9, 2000);
		assertArrayEquals(a, b);
	}

	@Test
	void timeoutStillReturnsValidOrder()
	{
		Random r = new Random(1);
		int n = 400;
		int[] pos = new int[n + 1];
		for (int i = 1; i <= n; i++) pos[i] = r.nextInt(100000);
		int[][] preds = noPreds(n);
		for (int i = 2; i <= n; i += 2) preds[i] = new int[]{i - 1}; // many chains
		int[] order = Solver.solve(n, lineCost(pos), preds, 1, 1); // 1 ms budget
		assertEquals(n, order.length);
		assertTrue(Solver.valid(order, preds));
	}

	@Test
	void emptyAndSingle()
	{
		assertEquals(0, Solver.solve(0, (a, b) -> 0, noPreds(0), 1, 100).length);
		assertArrayEquals(new int[]{1}, Solver.solve(1, (a, b) -> 7, noPreds(1), 1, 100));
	}

	@Test
	void cycleDoesNotHangAndIsReportedInvalid()
	{
		int[][] preds = noPreds(2);
		preds[1] = new int[]{2};
		preds[2] = new int[]{1};
		int[] order = Solver.solve(2, (a, b) -> 1, preds, 1, 100);
		assertEquals(2, order.length);
		assertFalse(Solver.valid(order, preds));
	}

	private static int indexOf(int[] a, int v)
	{
		for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
		return -1;
	}
}
```


- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement**

```java
public final class Solver
{
	private Solver() {}

	public static int[] solve(int n, IntBinaryOperator cost, int[][] preds, long seed, long deadlineMillis)
	{
		if (n == 0)
		{
			return new int[0];
		}
		long deadline = System.nanoTime() + deadlineMillis * 1_000_000L;
		int[] order = construct(n, cost, preds);
		if (valid(order, preds))
		{
			improve(order, cost, preds, new Random(seed), deadline);
		}
		return order;
	}

	static int[] construct(int n, IntBinaryOperator cost, int[][] preds)
	{
		boolean[] placed = new boolean[n + 1];
		int[] order = new int[n];
		int cur = 0;
		for (int k = 0; k < n; k++)
		{
			int best = -1;
			int bestCost = Integer.MAX_VALUE;
			for (int v = 1; v <= n; v++)
			{
				if (placed[v] || !ready(v, placed, preds))
				{
					continue;
				}
				int c = cost.applyAsInt(cur, v);
				if (c < bestCost)
				{
					bestCost = c;
					best = v;
				}
			}
			if (best < 0)
			{
				for (int v = 1; v <= n; v++)
				{
					if (!placed[v])
					{
						best = v;
						break;
					}
				}
			}
			placed[best] = true;
			order[k] = best;
			cur = best;
		}
		return order;
	}

	private static boolean ready(int v, boolean[] placed, int[][] preds)
	{
		for (int p : preds[v])
		{
			if (!placed[p])
			{
				return false;
			}
		}
		return true;
	}

	public static boolean valid(int[] order, int[][] preds)
	{
		int[] pos = new int[order.length + 1];
		for (int i = 0; i < order.length; i++)
		{
			pos[order[i]] = i;
		}
		for (int v = 1; v <= order.length; v++)
		{
			for (int p : preds[v])
			{
				if (pos[p] >= pos[v])
				{
					return false;
				}
			}
		}
		return true;
	}

	public static long pathCost(int[] order, IntBinaryOperator cost)
	{
		long total = 0;
		int cur = 0;
		for (int v : order)
		{
			total += cost.applyAsInt(cur, v);
			cur = v;
		}
		return total;
	}
	// improve(): loops 2-opt and or-opt passes; see algorithm above. Each accepted move re-checks valid() on the
	// affected window only (position array maintained incrementally), and strictly lowers pathCost.
}
```

Write `improve` fully: maintain `int[] pos`; 2-opt pass iterates `i` over a seeded shuffled list of start indices, `j > i`; compute delta from the four affected edges (`prev(i)->order[i]`, `order[j]->next(j)` replaced by `prev(i)->order[j]`, `order[i]->next(j)`; for `j` at the end there is no outgoing edge since the path is open); reject when any `v` in `order[i..j]` has a pred also inside `[i..j]` (check via `pos`); apply with in-place reversal and fix `pos`. Or-opt: for block lengths 1..3 and target positions, delta from removing the block and inserting it (forward orientation only), reject if any block member's pred lies at/after the insertion point or any successor-of-block-member (precompute `succs`) lies before it. Check the deadline every 256 evaluations and return immediately when exceeded. Stop when a full pass makes no improvement.

- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.plan.SolverTest"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/plan/Solver.java src/test/java/com/chunkblazer/gps/plan/SolverTest.java
git commit -m "feat(gps): precedence-aware solver (NN + 2-opt + or-opt)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 5: Quests as providers, reward feedback, end-to-end ordering

**Files:**
- Modify: `Selector.java` (quest XP providers), `NodeFactory.java` if needed
- Create: `Planner.java`
- Test: `src/test/java/com/chunkblazer/gps/plan/QuestPlanningTest.java`

**Interfaces:**
- Consumes: Tasks 1-4, `CostMatrix`, `TravelContext`, `Solver`.
- Produces `Planner(PlanRequest req)` with `Plan plan()`: (1) `Selector.select()`; (2) nodes list -> `TravelNode`s (index 0 = start tile, then nodes; null tiles carried from the preceding node in `after` chain or the start) -> `CostMatrix.build`; (3) `Solver.solve` with `preds` from the graph; (4) projected-state verification: replay the order applying `Effects` and assert each node's `requirement.missing(projected)` is empty, otherwise move that node to `unplannable` (defensive; should not happen); (5) build `PlanStep`s: `travelTicks = matrix.cost(prev, this)`, `viaTransportEdgeIds = matrix.route(prev,this)`, `reason` text built from the node (e.g. "Quest step 2/5: Cook's Assistant (unlocks Cooking 300 xp)", "Needed for: <label of first dependent node>", "<points> task points"), `missingRequirements` empty for planned steps (blocked ones are in `unplannable`), `approximate` from location.
- Selector change: when a skill is missing, in addition to the training provider consider each quest in `QuestCatalog` not complete whose `rewards.xp[skill]` (plus `choiceXp` entries valid for ANY skill, counted once) covers the shortfall `xpForLevel(level) - currentXp` and whose own requirement is satisfiable from the projected state with at most one more provider: if such a quest exists, choose it over training when its chain's estimated cost (sum of distances between consecutive step tiles using straight-line `TileMath.tileDistance * 0.5` as a cheap proxy) is less than the training node's estimated cost (`trainingTravelProxy` = distance from the player's start to the training location, or 0 when the location is null) plus a constant `TRAINING_PENALTY_TICKS = 200` that represents the grind; the penalty is a named constant in `Selector`. The chosen quest's chain becomes the provider (precedence on its LAST step).

- [ ] **Step 1: Write the failing tests**

```java
class QuestPlanningTest
{
	@Test
	void questStepsInterleaveWithNearbyTaskInFullPlan()
	{
		// quest step 1 at (3200,3200), step 2 at (3300,3200) (region 51); task at (3250,3200) in region 50/51 boundary region 50
		PlanWorld w = PlanWorld.builder().unlocked(12850, 12851)
			.quest("Q", step("go A", 3200, 3200), step("go C", 3300, 3200))
			.task("t", 1, 1, 3250, 3200, 12850)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> labels = p.getSteps().stream().map(PlanStep::getLabel).collect(Collectors.toList());
		int i1 = indexContaining(labels, "go A"), i2 = indexContaining(labels, "go C"), it = indexContaining(labels, "t");
		assertTrue(i1 < it && it < i2, labels.toString());
	}

	@Test
	void questXpFeedsLaterRequirementAndReplacesTraining()
	{
		// task needs COOKING 30 (13,363 xp); quest Q gives 15,000 cooking xp and sits next to the task; a training method exists but is far
		PlanWorld w = PlanWorld.builder().unlocked(12850)
			.quest("Q", 15000, "COOKING", step("do quest", 3210, 3210))
			.task("cook", "COOKING", 30, 5, 3215, 3215, 12850)
			.trainingMethod("COOKING", "Far grind", 1, 99, 30000, 3200 + 600, 3200)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getLabel().contains("do quest")));
		assertTrue(p.getSteps().stream().noneMatch(s -> s.getType() == NodeType.TRAINING));
		int iq = indexContaining(labelsOf(p), "do quest"), ic = indexContaining(labelsOf(p), "cook");
		assertTrue(iq < ic);
	}

	@Test
	void trainingUsedWhenNoQuestCoversShortfall()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850)
			.task("cook", "COOKING", 30, 5, 3215, 3215, 12850)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING));
	}

	@Test
	void prerequisiteQuestIsPlannedBeforeDependentQuest()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850)
			.quest("A", step("A step", 3205, 3205))
			.quest("B", requiresQuest("A"), step("B step", 3206, 3206))
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		List<String> l = labelsOf(p);
		assertTrue(indexContaining(l, "A step") < indexContaining(l, "B step"));
	}

	@Test
	void unknownLocationStepsAreFlaggedApproximate()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).quest("Q", stepNoLocation("somewhere"), step("known", 3205, 3205)).start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		assertTrue(p.getSteps().stream().anyMatch(PlanStep::isApproximate));
	}

	@Test
	void emptyWorldGivesEmptyPlan()
	{
		Plan p = new Planner(PlanWorld.builder().unlocked(12850).start(3200, 3200).build().request()).plan();
		assertTrue(p.getSteps().isEmpty());
		assertEquals(0, p.getTotalTravelTicks());
	}

	@Test
	void replayVerifiesRequirementsHoldAlongTheOrder()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850)
			.task("cook", "COOKING", 30, 5, 3215, 3215, 12850)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220)
			.start(3200, 3200).build();
		Plan p = new Planner(w.request()).plan();
		PlayerState s = w.request().getState();
		for (PlanStep step : p.getSteps())
		{
			PlanNode n = w.nodeFor(step);
			assertTrue(n.getRequirement().missing(s).isEmpty(), step.getLabel());
			s = n.getEffects().apply(s);
		}
	}
}
```

`PlanWorld` is a test-only builder (same package) that assembles synthetic `GpsData` (quests, training, locations JSON via `GpsTestFixtures`), `TaskRef`s, a single-chunk-per-region `ChunkGraph` (all `unlocked` regions fully connected to adjacent region ids), `TransportModel` with no edges, `QuestCatalog`, `RequirementDeriver`, and exposes `request()` and `nodeFor(PlanStep)` (looks the node up by label in the last `Selector` output kept by the builder); the quest step helpers `step(text, x, y)`, `stepNoLocation(text)`, `requiresQuest(id)` fill the quest JSON. Write this helper fully as part of this step; it is the shared fixture for Tasks 5-7.

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `Planner.plan()` and the Selector quest-provider rule.
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.plan.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): Planner facade with quest chains, reward feedback and interleaving

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 6: `TeleportAcquirer` (buy/make teleports when worth it)

**Files:**
- Create: `TeleportAcquirer.java`
- Modify: `Planner.java` (call it when `allowTeleportAcquisition`)
- Test: `src/test/java/com/chunkblazer/gps/plan/TeleportAcquirerTest.java`

**Interfaces:**
- Consumes: `TransportModel.usable(state, acquired, true)` (ACQUIRABLE edges), `CostMatrix`, `Solver`, `NodeFactory.acquireItem`.
- Produces: `TeleportAcquirer(PlanRequest, NodeFactory)`; `AcquireResult apply(List<PlanNode> nodes, int[] order, ...)` returning an updated node list and order including `ACQUIRE_TELEPORT` nodes. Constants in `TeleportAcquirer`: `MIN_NET_SAVING_TICKS = 100`, `MAX_ACQUISITIONS = 5`, `MAX_QTY = 25`.
- Algorithm: baseline cost `C0 = pathCost(order)` using the matrix without acquirables. For each ACQUIRABLE edge `e` (sorted by id): build a matrix with `acquiredEdgeIds = {e}` over the same nodes plus the acquisition node's own location (the shop tile; if unknown skip the edge) and compute `C1` for the same order, `uses` = number of consecutive pairs whose `route` contains `e`; `savings = C0 - C1`. The cheapest insertion of the acquisition node: position `p` in `0..firstUse` (insert before the first node whose incoming leg uses `e`) minimising added detour `d`; accept when `savings - d >= MIN_NET_SAVING_TICKS`. The acquisition node: type `ACQUIRE_TELEPORT`, label "Buy <item> x<qty> at <shop>", `effects.item(itemId, min(uses, MAX_QTY))`, location = shop tile; `preds` for every node whose leg uses the edge. After accepting, re-run `Solver.solve` once (with the acquisition node and its new precedence) and keep it only if the new cost is lower. Greedy, at most `MAX_ACQUISITIONS`, re-evaluating `C0` after each acceptance.

- [ ] **Step 1: Write the failing tests** on a line world: START at x=0; tasks at x=10000 region-far and x=10010; a spell teleport from anywhere to x=9990 requiring item 554 not owned; shop selling 554 at x=20 (close to START). Expect: acquisition at the shop is inserted first, plan total ticks lower than without acquisition; with the shop 9000 tiles away the acquisition is NOT inserted (net saving below threshold); with `allowTeleportAcquisition=false` never inserted; the acquisition node precedes every node using the teleport; saving below `MIN_NET_SAVING_TICKS` rejects.

```java
class TeleportAcquirerTest
{
	@Test
	void buysTeleportRunesWhenItSavesALotOfTravel()
	{
		PlanWorld w = PlanWorld.builder().unlockedRange(10).spellTeleport("tele", 554, 9990).shop("Rune shop", 554, 20)
			.task("far1", "COOKING", 1, 1, 10000, 3200, TileMath.regionId(10000, 3200)).task("far2", "COOKING", 1, 1, 10010, 3200, TileMath.regionId(10010, 3200)).start(0, 3200).allowAcquisition(true).build();
		Plan with = new Planner(w.request()).plan();
		Plan without = new Planner(w.requestWithAcquisition(false)).plan();
		assertTrue(with.getTotalTravelTicks() < without.getTotalTravelTicks());
		int ia = indexOfType(with, NodeType.ACQUIRE_TELEPORT);
		assertTrue(ia >= 0 && ia < indexOfLabel(with, "far1"));
	}
	// plus the four rejection cases listed above, each as its own @Test
}
```

Write the world numbers concretely when implementing: a `PlanWorld` with `unlockedRange` must unlock a corridor of regions between x=0 and x=10100 at y=3200 (about 160 regions) so walking is possible but long (about 10,000 tiles * 0.5 * 1.25 = 6,250 ticks), making the teleport worth about 6,000 ticks and the acquisition detour about 20 ticks. The "shop too far" case puts the shop at x=9000 relative to START (detour about 5,600 ticks, net saving negative).

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** per the algorithm.
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.plan.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): TeleportAcquirer inserts buy-teleport steps when net saving is large

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 7: `Replanner`, determinism and performance

**Files:**
- Create: `Replanner.java`
- Test: `src/test/java/com/chunkblazer/gps/plan/ReplannerTest.java`, `PlannerPerfTest.java`

**Interfaces:**
- Produces: `Replanner.replan(Plan previous, PlanRequest base, PlayerState newState, Tile position, Set<String> doneNodeIds) -> Plan`: builds a new `PlanRequest` copy with `state = newState`, `start = position`, `completedTaskIds = base.completedTaskIds + task ids from doneNodeIds (ids starting with "task:")`; quest steps in `doneNodeIds` (`quest:<ID>:<i>`) are passed through a `NodeFactory` option so already-done steps are dropped from their chain and the next remaining step has no `after`; training/acquisition done ids are ignored because the new state already reflects them. Calls `new Planner(copy).plan()`.
  Implementation note: add `PlanRequest.doneNodeIds` (Set<String>) consumed by `NodeFactory.questChain` (skip steps whose id is in the set) and `Selector`; `Replanner` just fills it.

- [ ] **Step 1: Write the failing tests**

```java
class ReplannerTest
{
	@Test
	void completedTaskDisappearsAndStartMoves()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).task("a", 1, 1, 3210, 3200, 12850).task("b", 1, 1, 3300, 3200, 12850).start(3200, 3200).build();
		Plan p1 = new Planner(w.request()).plan();
		String first = p1.getSteps().get(0).getLabel();
		Plan p2 = Replanner.replan(p1, w.request(), w.request().getState(), p1.getSteps().get(0).getLocation(), Collections.singleton("task:" + first));
		assertTrue(p2.getSteps().stream().noneMatch(s -> s.getLabel().equals(first)));
		assertEquals(Collections.emptyList(), p2.getUnplannable());
		assertEquals(w.walkTicks(p1.getSteps().get(0).getLocation(), p2.getSteps().get(0).getLocation()), p2.getSteps().get(0).getTravelTicks());
	}

	@Test
	void partiallyDoneQuestResumesAtNextStep()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).quest("Q", step("s0", 3201, 3200), step("s1", 3202, 3200), step("s2", 3203, 3200)).start(3200, 3200).build();
		Plan p = Replanner.replan(null, w.request(), w.request().getState(), w.tile(3201, 3200), Collections.singleton("quest:Q:0"));
		List<String> labels = p.getSteps().stream().map(PlanStep::getLabel).collect(Collectors.toList());
		assertEquals(2, labels.size());
		assertTrue(labels.get(0).contains("s1") && labels.get(1).contains("s2"));
	}

	@Test
	void levelUpFromStateChangeRemovesTrainingStep()
	{
		PlanWorld w = PlanWorld.builder().unlocked(12850).task("cook", "COOKING", 30, 5, 3215, 3215, 12850)
			.trainingMethod("COOKING", "Grind", 1, 99, 30000, 3220, 3220).start(3200, 3200).build();
		Plan before = new Planner(w.request()).plan();
		assertTrue(before.getSteps().stream().anyMatch(s -> s.getType() == NodeType.TRAINING));
		PlayerState leveled = w.request().getState().withXp("COOKING", 14000);
		Plan after = Replanner.replan(before, w.request(), leveled, w.tile(3200, 3200), Collections.emptySet());
		assertTrue(after.getSteps().stream().noneMatch(s -> s.getType() == NodeType.TRAINING));
	}

	@Test
	void sameInputSamePlan()
	{
		PlanWorld w = PlanWorld.random(42, 200);
		List<String> a = labelsOf(new Planner(w.request()).plan());
		List<String> b = labelsOf(new Planner(PlanWorld.random(42, 200).request()).plan());
		assertEquals(a, b);
	}
}
```


```java
class PlannerPerfTest
{
	@Test
	void fiveHundredNodesPlanUnderTwoSecondsIncludingMatrix()
	{
		PlanWorld w = PlanWorld.random(7, 500).maxNodes(500);
		long t0 = System.nanoTime();
		Plan p = new Planner(w.request()).plan();
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertTrue(p.getSteps().size() >= 400, "steps " + p.getSteps().size());
		assertTrue(ms < 2000, "plan took " + ms + " ms");
	}
}
```

`PlanWorld.random(seed, taskCount)` builds a 20x20 grid of unlocked regions, `taskCount` skill tasks with level 1 at seeded random tiles, 30 quests of 3-6 steps each at seeded random tiles, 40 spell teleports, no acquirables; used for determinism/perf.

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `Replanner` and the `doneNodeIds` plumbing.
- [ ] **Step 4: Run** `./gradlew test --tests "com.chunkblazer.gps.*"` — Expected: PASS. If the perf test fails, profile (matrix build vs solver) and tune: lower `Solver` deadline share, cap or-opt block length, or precompute node rows in `CostMatrix`; the 2 s bound includes the matrix, the solver's own budget stays 800 ms.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): Replanner, determinism and 500-node performance tests

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 8: `KillTimeCheck` (time-limited kill tasks, approximate)

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/combat/KillTimeCheck.java`
- Modify: `RequirementDeriver.java` (time-limited kill tasks), `EligibilityEngine`/`Requirement` only if needed
- Test: `src/test/java/com/chunkblazer/gps/combat/KillTimeCheckTest.java`

**Interfaces:**
- Produces `KillTimeCheck.estimateTicks(int monsterHp, int playerMaxHit, int attackSpeedTicks, double hitChance)` -> `int`: `attackSpeedTicks * ceil(monsterHp / (playerMaxHit * 0.5 * hitChance))` (average hit is half of max; uses 0.5), returns `INF` when `playerMaxHit <= 0`; `KillTimeCheck.canKillInTime(...)` -> boolean `estimateTicks <= limit`. `hitChance` default `0.7` (documented approximation; no accuracy/defence model). Flagged approximate: `RequirementDeriver` adds `unknown`-free `minMaxHit` for time-limited kills only when `limit > 1`: `minMaxHit = ceil(2 * hp / (limitTicks / attackSpeed(4) * 0.7))`, and the resulting requirement text notes "approximate" through a new `Requirement.approximate` boolean that the UI shows as "(approx.)".

- [ ] **Step 1: Write the failing tests**

```java
class KillTimeCheckTest
{
	@Test
	void estimateScalesWithHpAndSpeed()
	{
		// hp 20, max 10, speed 4, chance 0.7: avg dmg 3.5/attack -> ceil(20/3.5)=6 attacks -> 24 ticks
		assertEquals(24, KillTimeCheck.estimateTicks(20, 10, 4, 0.7));
	}

	@Test
	void zeroMaxHitIsInfinite()
	{
		assertEquals(TravelConfig.INF, KillTimeCheck.estimateTicks(20, 0, 4, 0.7));
	}

	@Test
	void canKillInTimeComparesToLimit()
	{
		assertTrue(KillTimeCheck.canKillInTime(20, 10, 4, 0.7, 24));
		assertFalse(KillTimeCheck.canKillInTime(20, 10, 4, 0.7, 23));
	}

	@Test
	void derivedMinMaxHitForTwentyTickKill()
	{
		// desert lizard hp 25 in 20 ticks: attacks = 20/4 = 5; need 25 / (5 * 0.7) = 7.14 avg -> max hit 15
		assertEquals(15, KillTimeCheck.requiredMaxHit(25, 20, 4, 0.7));
	}
}
```

`requiredMaxHit(hp, limitTicks, attackSpeed, hitChance) = ceil(2.0 * hp / ((limitTicks / attackSpeed) * hitChance))` (with `limitTicks/attackSpeed` as a double floor-bounded to at least 1 attack).

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement**, then extend `RequirementDeriver` rule 8: for `NPC_KILL` tasks with `time_in_ticks[0] > 1` and known hp, set `minMaxHit(requiredMaxHit(...))` and `approximate(true)`. Add `Requirement.approximate` (builder, merge OR, getter) with a test line in `RequirementTest`.
- [ ] **Step 4: Run** `./gradlew test` (full suite) — Expected: PASS; the M2 catalog-wide test must still pass (update `EXPECTED_NONE_TYPES` only if a justified change is needed).
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): approximate kill-in-time requirement for time-limited kill tasks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

## Self-Review

- Spec coverage (section 7): nodes incl. quest steps/acquisitions (Tasks 1-2); minimise travel subject to precedence incl. quest step order and requirement-derived providers (Tasks 3-5); quest interleaving + XP/unlock feedback (Tasks 4-5 tests); teleport acquisition (Task 6); NN + 2-opt/or-opt, seeded, time-bounded, open path (Task 4); goal modes (Task 3); re-planning (Task 7); output `PlanStep` (Task 5); time-limit DPS estimate deferred from M2 (Task 8).
- Known simplifications (recorded for review): provider choice between a quest and training uses a straight-line proxy plus a constant grind penalty; `MAX_GEAR` is a simple shop-item list; unlocking new chunks mid-plan is not planned (locked-chunk tasks are reported unplannable; `UNLOCK_NEXT_CHUNK` plans the points needed instead).
- Type names match the interface block (`PlanNode`, `Effects`, `PlanStep`, `Plan`, `PlanRequest`, `TaskRef`) and the M2/M3 interfaces they consume.
