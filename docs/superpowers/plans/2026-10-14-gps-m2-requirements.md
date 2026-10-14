# Progression GPS M2: Requirement Model, Eligibility, Max Hit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Given a `PlayerState` (real or simulated) decide for every catalog task and every quest whether it is doable now, blocked (with the exact missing requirements) or unknown, including one-hit/kill tasks via a max-hit calculator, and show the result in a GPS panel.

**Architecture:** Pure-Java package `com.chunkblazer.gps.model` (state, requirements, XP table), `com.chunkblazer.gps.combat` (max hit) and `com.chunkblazer.gps.eligibility` (derivation + engine). A thin client adapter builds `PlayerState`; a separate `GpsPanel` (own sidebar nav button) lists results. Wiki-derived data comes only through `GpsData` (M1) and may be absent.

**Tech Stack:** Java 11 release, Gson, Lombok, JUnit 5, Mockito (all existing). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 3, 5, 5.1, 5.2, 8). Depends on M1 (`com.chunkblazer.gps.data.*`, `GpsDataLocator`).

## Global Constraints

- Logic classes in `com.chunkblazer.gps.*` have no `Client`/RuneLite client dependency; only `gps.adapter` and `gps.ui` may import RuneLite.
- Unknown requirements are tagged `UNKNOWN` and surfaced; never silently treated as met.
- Unknown bank contents count as "not owned".
- No server catalog schema or Go server change. No new dependencies. Java release 11 (no records, no `var` in lambdas params, no text blocks).
- Nothing wiki-derived in the repo; tests use synthetic fixtures or the committed catalog `tasks_catalog.json.gz` / `quest_requirements.json` (both already in the repo).
- Style: tabs, braces on their own line, license header copied from `src/main/java/com/chunkblazer/QuestRequirements.java`.

## Review Focus

- `GpsData` empty (no data dir): eligibility must still work from catalog + `quest_requirements.json`, one-hit checks become UNKNOWN, nothing throws. Task 7 test.
- Catalog `completion_type` is mixed case (`NPC_Kill`, `NPC_KILL`, `Obtain`, `OBTAIN`): derivation must be case-insensitive. Task 4 test.
- A task with a `time_in_ticks` limit of `[1]` is the one-hit marker (e.g. "Defeat a Level 20 Dark Wizard 1 Hit"); a plain time limit like `[20]` is NOT a one-hit task. Task 6 test.
- Level 99 / XP boundary: XP-to-level at exactly 13,034,431 xp and 200M cap, level 1 account (all skills 1, Hitpoints 10). Task 1 test.
- Quest task referencing an unknown quest id, or a quest in a prerequisite cycle in the data: must not loop forever. Task 3 test.

## File Structure

New (all under `src/main/java/com/chunkblazer/gps/` unless noted; tests mirror under `src/test/java/com/chunkblazer/gps/`):
- `model/XpTable.java` — XP/level conversions
- `model/PlayerState.java` — immutable state with `with*` projection methods
- `model/Requirement.java`, `model/Missing.java`, `model/ItemNeed.java` — requirement value types + `missing(state)` evaluation
- `model/QuestCatalog.java` — merges `GpsData.quests` with `quest_requirements.json` fallback into `QuestModel`s
- `model/QuestModel.java` — quest id, requirement, steps, rewards (consumed by M3/M4)
- `eligibility/RequirementDeriver.java` — `NuzlockeTask` -> `Requirement`
- `eligibility/Overrides.java` — local `requirement_overrides.json`
- `eligibility/EligibilityEngine.java`, `eligibility/Eligibility.java`
- `combat/CombatStats.java`, `combat/MaxHitCalculator.java`, `combat/MagicSpells.java`, `combat/OneHitCheck.java`
- `adapter/PlayerStateProvider.java` (client), `adapter/SimulatedState.java`
- `ui/GpsPanel.java` (+ nav button wiring in `ChunkBlazerPlugin.java`, minimal)

## Interfaces defined here and used by M3-M5 (do not rename)

```java
// model
XpTable.xpForLevel(int level) -> int;  XpTable.levelForXp(long xp) -> int;  XpTable.MAX_LEVEL == 99
PlayerState: getLevel(String skill) -> int;  getXp(String skill) -> long;  hasQuest(String questId) -> boolean;
  getQuestPoints() -> int;  getCombatLevel() -> int;  ownedCount(int itemId) -> int;
  hasUnlockedChunk(int regionId) -> boolean;  getUnlockedChunks() -> Set<Integer>;
  withXp(String skill, long delta) -> PlayerState;  withQuestComplete(String questId, int questPointsGained) -> PlayerState;
  withItem(int itemId, int qty) -> PlayerState;  withUnlockedChunk(int regionId) -> PlayerState;
  withUnlock(String token) -> PlayerState;  hasUnlock(String token) -> boolean;
  static Builder builder();  static PlayerState freshAccount();
Requirement: skills Map<String,Integer>; quests Set<String>; questPoints int; combatLevel int; items List<ItemNeed>;
  minMaxHit int; chunks Set<Integer>; unknown List<String>;
  List<Missing> missing(PlayerState s);  Requirement merge(Requirement o);  static Requirement NONE;  boolean isUnknown()
Missing { Kind kind; String text; }  Kind = SKILL, QUEST, QUEST_POINTS, COMBAT_LEVEL, ITEM, MAX_HIT, CHUNK, UNKNOWN
ItemNeed { Set<Integer> anyOf; int qty; String name; }
QuestModel { String id; String name; Requirement requirement; List<QuestStep> steps; QuestDef.Rewards rewards; boolean fromWikiData; }
QuestCatalog: static QuestCatalog build(GpsData data, Map<String,QuestHelperEntry> helper);  Optional<QuestModel> get(String id);  Collection<QuestModel> all();
// eligibility
Eligibility { Status status; List<Missing> missing; }  Status = DOABLE, BLOCKED, UNKNOWN
EligibilityEngine(RequirementDeriver deriver, QuestCatalog quests, GpsData data): Eligibility evaluate(NuzlockeTask t, PlayerState s);
  Eligibility evaluateQuest(String questId, PlayerState s)
RequirementDeriver: Requirement derive(NuzlockeTask t)
// combat
CombatStats { int attack, strength, ranged, magic; int strengthBonus, rangedStrengthBonus; int magicDamagePercent; Style style; ...}
MaxHitCalculator.melee(CombatStats) -> int; .ranged(CombatStats) -> int; .magic(CombatStats, String spellOrStaff) -> int; .best(CombatStats) -> int
```

---

### Task 1: `XpTable` and `PlayerState`

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/model/XpTable.java`, `PlayerState.java`
- Test: `src/test/java/com/chunkblazer/gps/model/XpTableTest.java`, `PlayerStateTest.java`

**Interfaces:** Produces `XpTable`, `PlayerState` exactly as listed above. Skills are RuneLite `Skill` enum names as strings (`"ATTACK"`, ...). Combat level is computed by `PlayerState` from the 7 combat skills when not explicitly set via `builder().combatLevel(int)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.chunkblazer.gps.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class XpTableTest
{
	@Test
	void knownLevelThresholds()
	{
		assertEquals(0, XpTable.xpForLevel(1));
		assertEquals(83, XpTable.xpForLevel(2));
		assertEquals(1154, XpTable.xpForLevel(10));
		assertEquals(13_034_431, XpTable.xpForLevel(99));
	}

	@Test
	void levelForXpBoundaries()
	{
		assertEquals(1, XpTable.levelForXp(0));
		assertEquals(1, XpTable.levelForXp(82));
		assertEquals(2, XpTable.levelForXp(83));
		assertEquals(98, XpTable.levelForXp(13_034_430));
		assertEquals(99, XpTable.levelForXp(13_034_431));
		assertEquals(99, XpTable.levelForXp(200_000_000L));
	}
}
```

```java
package com.chunkblazer.gps.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerStateTest
{
	@Test
	void freshAccountHasLevelOneAndTenHitpoints()
	{
		PlayerState s = PlayerState.freshAccount();
		assertEquals(1, s.getLevel("ATTACK"));
		assertEquals(10, s.getLevel("HITPOINTS"));
		assertEquals(3, s.getCombatLevel());
		assertEquals(0, s.getQuestPoints());
	}

	@Test
	void withXpProjectsLevelAndLeavesOriginalUntouched()
	{
		PlayerState a = PlayerState.freshAccount();
		PlayerState b = a.withXp("COOKING", 300);
		assertEquals(1, a.getLevel("COOKING"));
		assertEquals(300, b.getXp("COOKING"));
		assertEquals(Math.max(1, XpTable.levelForXp(300)), b.getLevel("COOKING"));
		assertTrue(b.getLevel("COOKING") >= 5);
	}

	@Test
	void questCompletionAddsPointsOnce()
	{
		PlayerState s = PlayerState.freshAccount().withQuestComplete("COOKS_ASSISTANT", 1);
		assertTrue(s.hasQuest("COOKS_ASSISTANT"));
		assertEquals(1, s.getQuestPoints());
		assertEquals(1, s.withQuestComplete("COOKS_ASSISTANT", 1).getQuestPoints());
	}

	@Test
	void itemsAccumulateAndUnknownIsZero()
	{
		PlayerState s = PlayerState.freshAccount().withItem(1944, 2).withItem(1944, 1);
		assertEquals(3, s.ownedCount(1944));
		assertEquals(0, s.ownedCount(1));
	}

	@Test
	void unlockedChunksAndTokens()
	{
		PlayerState s = PlayerState.freshAccount().withUnlockedChunk(12850).withUnlock("TELEPORT:Explorer's ring");
		assertTrue(s.hasUnlockedChunk(12850));
		assertFalse(s.hasUnlockedChunk(1));
		assertTrue(s.hasUnlock("TELEPORT:Explorer's ring"));
	}

	@Test
	void combatLevelFormula()
	{
		PlayerState s = PlayerState.builder().level("ATTACK", 99).level("STRENGTH", 99).level("DEFENCE", 99)
			.level("HITPOINTS", 99).level("PRAYER", 99).level("RANGED", 99).level("MAGIC", 99).build();
		assertEquals(126, s.getCombatLevel());
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.chunkblazer.gps.model.*"`
Expected: compile errors.

- [ ] **Step 3: Implement**

```java
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

	private XpTable() {}

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
```

`PlayerState`: a final class holding `Map<String,Long> xp`, `Map<String,Integer> levels` (levels default `1`, HITPOINTS `10`), `Set<String> quests`, `int questPoints`, `Integer combatOverride`, `Map<Integer,Integer> items`, `Set<Integer> chunks`, `Set<String> unlocks`; every `with*` copies the maps (immutability, `Collections.unmodifiable*` getters). `levels` is the "real" level; `withXp` adds delta to `xp[skill]` where the base xp of a skill with no explicit xp is `XpTable.xpForLevel(level)`, then sets `level = max(level, levelForXp(newXp))` (boosts are not modelled). Combat level: `base = 0.25*(defence + hitpoints + floor(prayer/2))`, `melee = 0.325*(attack+strength)`, `ranged = 0.325*floor(1.5*ranged)`, `mage = 0.325*floor(1.5*magic)`, `floor(base + max(melee, ranged, mage))`. Builder methods: `level(skill,int)`, `xp(skill,long)`, `quest(id)`, `questPoints(int)`, `combatLevel(int)`, `item(id,qty)`, `chunk(regionId)`, `unlock(token)`, `build()`. `freshAccount()` = defaults (combat level 3). `withQuestComplete` adds points only if the quest was not already complete.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.chunkblazer.gps.model.*"` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/model src/test/java/com/chunkblazer/gps/model
git commit -m "feat(gps): XpTable and immutable PlayerState

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 2: `Requirement` and missing-requirement evaluation

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/model/Requirement.java`, `Missing.java`, `ItemNeed.java`
- Test: `src/test/java/com/chunkblazer/gps/model/RequirementTest.java`

**Interfaces:**
- Consumes: `PlayerState` (Task 1).
- Produces: `Requirement`, `Missing`, `ItemNeed` per the interface block. `missing(state)` returns one `Missing` per unmet item: SKILL "Cooking 20 (you have 12)", QUEST "Quest: Cook's Assistant" (display name = id title-cased), QUEST_POINTS, COMBAT_LEVEL, ITEM "Egg x1" (or "any of 3 items"), MAX_HIT "Max hit 24 (you have 17)", CHUNK "Chunk 12850 not unlocked", and one UNKNOWN per `unknown` entry. `merge` unions quests/chunks/unknown, takes the max of numeric levels per skill, max of questPoints/combatLevel/minMaxHit, concatenates item needs.

- [ ] **Step 1: Write the failing tests**

```java
class RequirementTest
{
	private static Requirement skills(String skill, int lvl)
	{
		return Requirement.builder().skill(skill, lvl).build();
	}

	@Test
	void noneIsAlwaysMet()
	{
		assertTrue(Requirement.NONE.missing(PlayerState.freshAccount()).isEmpty());
	}

	@Test
	void skillShortfallReported()
	{
		List<Missing> m = skills("COOKING", 20).missing(PlayerState.freshAccount());
		assertEquals(1, m.size());
		assertEquals(Missing.Kind.SKILL, m.get(0).getKind());
		assertTrue(m.get(0).getText().contains("20"));
	}

	@Test
	void questAndChunkAndItemReported()
	{
		Requirement r = Requirement.builder().quest("COOKS_ASSISTANT").chunk(12850)
			.item(new ItemNeed(new HashSet<>(Arrays.asList(1944)), 1, "Egg")).build();
		PlayerState none = PlayerState.freshAccount();
		assertEquals(3, r.missing(none).size());
		PlayerState ok = none.withQuestComplete("COOKS_ASSISTANT", 1).withUnlockedChunk(12850).withItem(1944, 1);
		assertTrue(r.missing(ok).isEmpty());
	}

	@Test
	void anyOfItemSatisfiedByAnyId()
	{
		Requirement r = Requirement.builder().item(new ItemNeed(new HashSet<>(Arrays.asList(1, 2, 3)), 1, "Tool")).build();
		assertTrue(r.missing(PlayerState.freshAccount().withItem(2, 1)).isEmpty());
	}

	@Test
	void itemQuantityCountsAcrossAnyOfIds()
	{
		Requirement r = Requirement.builder().item(new ItemNeed(new HashSet<>(Arrays.asList(1, 2)), 3, "Logs")).build();
		assertTrue(r.missing(PlayerState.freshAccount().withItem(1, 1).withItem(2, 1)).size() == 1);
		assertTrue(r.missing(PlayerState.freshAccount().withItem(1, 2).withItem(2, 1)).isEmpty());
	}

	@Test
	void unknownIsNeverMet()
	{
		Requirement r = Requirement.builder().unknown("varbit 1234").build();
		assertTrue(r.isUnknown());
		assertEquals(Missing.Kind.UNKNOWN, r.missing(PlayerState.freshAccount()).get(0).getKind());
	}

	@Test
	void maxHitNeedChecksMinMaxHit()
	{
		Requirement r = Requirement.builder().minMaxHit(24).build();
		assertEquals(1, r.missing(PlayerState.freshAccount()).size());
	}

	@Test
	void mergeTakesStrictest()
	{
		Requirement m = skills("COOKING", 20).merge(skills("COOKING", 30)).merge(skills("MINING", 5));
		assertEquals(30, (int) m.getSkills().get("COOKING"));
		assertEquals(5, (int) m.getSkills().get("MINING"));
	}
}
```

Notes: MAX_HIT cannot be computed from `PlayerState` alone (gear-dependent). `Requirement.missing(PlayerState)` therefore treats `minMaxHit > 0` as unmet unless `state.getMaxHit() >= minMaxHit`; add `PlayerState.getMaxHit()` (builder `maxHit(int)`, default 0, set by the adapter from `MaxHitCalculator.best` in Task 8). Add that field and its `PlayerStateTest` assertion in this task.

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.model.RequirementTest` — Expected: compile errors.

- [ ] **Step 3: Implement** `Requirement` as an immutable class with a nested `Builder` (`skill`, `quest`, `questPoints`, `combatLevel`, `item`, `minMaxHit`, `chunk`, `unknown`, `build`), `missing` as described above (item need: sum `state.ownedCount(id)` over `anyOf`, compare to `qty`), `merge`, `isUnknown() = !unknown.isEmpty()`, `static final Requirement NONE = builder().build()`. `Missing` is `@Value` with `enum Kind`. `ItemNeed` is `@Value`.

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/model src/test/java/com/chunkblazer/gps/model
git commit -m "feat(gps): Requirement model with missing-requirement evaluation

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 3: `QuestCatalog` (quests as first-class models)

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/model/QuestModel.java`, `QuestHelperEntry.java`, `QuestCatalog.java`
- Test: `src/test/java/com/chunkblazer/gps/model/QuestCatalogTest.java`

**Interfaces:**
- Consumes: `GpsData.getQuests()` (`QuestDef`), `Requirement`, `quest_requirements.json` entries.
- Produces: `QuestHelperEntry { List<Integer> chunks; List<String> quests; Map<String,Integer> skills; }` (Gson DTO matching the existing file's shape; unknown extra fields like questPoints/combatLevel are tolerated), `QuestHelperEntry.loadFromResource() -> Map<String,QuestHelperEntry>` (reads `/com/chunkblazer/quest_requirements.json`, returns empty map on failure), `QuestModel`, and `QuestCatalog` per the interface block. `QuestCatalog.prerequisitesClosure(String questId) -> List<String>` returns all transitive prerequisite quest ids in dependency order (prerequisites first, excluding the quest itself), cycle-safe, unknown ids skipped.
- Merge rule: wiki `QuestDef` requirements win where non-empty (skills/quests); `quest_requirements.json` fills the gaps; `chunks` always come from the helper entry (kept on the model as `chunks` List<Integer> for M3's chunk-centre fallback). A quest present only in the helper file becomes a `QuestModel` with `steps = []`, `fromWikiData=false`.

- [ ] **Step 1: Write the failing tests**

```java
class QuestCatalogTest
{
	private static QuestHelperEntry helper(List<Integer> chunks, List<String> quests, Map<String, Integer> skills)
	{
		return new QuestHelperEntry(chunks, quests, skills);
	}

	@Test
	void helperOnlyQuestHasNoSteps()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("ANIMAL_MAGNETISM", helper(Arrays.asList(11575), Arrays.asList("ERNEST_THE_CHICKEN"), Collections.singletonMap("CRAFTING", 19)));
		QuestCatalog c = QuestCatalog.build(GpsData.empty(null), h);
		QuestModel q = c.get("ANIMAL_MAGNETISM").get();
		assertFalse(q.isFromWikiData());
		assertTrue(q.getSteps().isEmpty());
		assertEquals(19, (int) q.getRequirement().getSkills().get("CRAFTING"));
		assertTrue(q.getRequirement().getQuests().contains("ERNEST_THE_CHICKEN"));
		assertEquals(Arrays.asList(11575), q.getChunks());
	}

	@Test
	void wikiStepsAndRewardsAreUsedWhenPresent()
	{
		GpsData d = GpsDataStore.load(fixtureDir("full")); // TEST_QUEST with 2 steps and 300 cooking xp
		QuestCatalog c = QuestCatalog.build(d, Collections.emptyMap());
		QuestModel q = c.get("TEST_QUEST").get();
		assertTrue(q.isFromWikiData());
		assertEquals(2, q.getSteps().size());
		assertEquals(300, (int) q.getRewards().getXp().get("COOKING"));
	}

	@Test
	void prerequisiteClosureIsOrderedAndCycleSafe()
	{
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("C", helper(null, Arrays.asList("B"), null));
		h.put("B", helper(null, Arrays.asList("A"), null));
		h.put("A", helper(null, Arrays.asList("C"), null)); // cycle
		h.put("D", helper(null, Arrays.asList("MISSING"), null));
		QuestCatalog c = QuestCatalog.build(GpsData.empty(null), h);
		List<String> closure = c.prerequisitesClosure("C");
		assertEquals(new HashSet<>(Arrays.asList("A", "B")), new HashSet<>(closure));
		assertEquals(Collections.emptyList(), c.prerequisitesClosure("D"));
		assertEquals(Collections.emptyList(), c.prerequisitesClosure("NOPE"));
	}

	@Test
	void shippedQuestRequirementsFileLoads()
	{
		Map<String, QuestHelperEntry> h = QuestHelperEntry.loadFromResource();
		assertTrue(h.size() > 100);
		assertTrue(h.containsKey("ANIMAL_MAGNETISM"));
	}
}
```

(`fixtureDir` copies the M1 `GpsDataStoreTest.fixture` helper; put a shared `GpsTestFixtures.dir(String)` in `src/test/java/com/chunkblazer/gps/GpsTestFixtures.java` in this task.)

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.model.QuestCatalogTest` — Expected: compile errors.

- [ ] **Step 3: Implement** `QuestCatalog.build`: for each helper entry create a `QuestModel` (requirement from skills/quests, chunks from helper); for each `QuestDef` in data create or overwrite: requirement = wiki requirement merged over helper (`helper.merge(wiki)` using strictest-wins), steps from the def, rewards from the def, `fromWikiData=true`. `prerequisitesClosure` is an iterative DFS post-order with a visited set; ids absent from the catalog are skipped but not errors. `QuestHelperEntry.loadFromResource` uses `new Gson().fromJson(reader, new TypeToken<Map<String,QuestHelperEntry>>(){}.getType())` wrapped in try/catch returning `Collections.emptyMap()`.

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/model src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): QuestCatalog merges wiki quests with quest_requirements.json

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 4: `RequirementDeriver` and local overrides

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/eligibility/RequirementDeriver.java`, `Overrides.java`
- Test: `src/test/java/com/chunkblazer/gps/eligibility/RequirementDeriverTest.java`

**Interfaces:**
- Consumes: `NuzlockeTask` getters (`getCompletionType()`, `getLevel()`, `getConstraints()` -> `TaskConstraints` with `getRequiredSkill()`, `getRequiredLevel()`, `getQuest()`, `getMinCombatLevel()`, `getRequiredRegion()`, `getAllowedRegions()`, `hasVarbitConstraints()`, `getVarbitId()`, `getVarpId()`; `getRequiredItems()` -> `RequiredItem` (`getItemIds()`/`getQuantity()` accessors, inspect the class and use its real getters); `getCaIds()`; `getChallenge()`), `QuestCatalog`.
- Produces: `RequirementDeriver(QuestCatalog quests, Overrides overrides)`; `Requirement derive(NuzlockeTask t)`; `Overrides.load(Path dir) -> Overrides` (reads `<dir>/overrides/requirement_overrides.json`: `{"<taskID>": {"skills":{"COOKING":30},"quests":["X"],"items":[{"ids":[1],"qty":1,"name":"N"}],"minMaxHit":0,"unknown":[]}}`, missing file = empty), `Overrides.get(String taskId) -> Optional<Requirement>`; an override REPLACES the derived requirement for that task.
- Derivation rules (each has a test): (1) `skill`: the task `level` (when `completion_type` is a skill type, i.e. in the set `SKILL_THRESHOLD, COOKING, MINING, FISHING, WOODCUTTING, FLETCHING, CRAFTING, AGILITY, THIEVING, CONSTRUCTION, SMITHING, SLAYER, FIREMAKING, HERBLORE, HUNTER, RUNECRAFTING, FARMING, PRAYER`) maps category/type to the skill (`RUNECRAFTING` -> `RUNECRAFT`) or uses `constraints.required_skill` when present; (2) `constraints.required_skill/required_level` always add a skill; (3) `required_items` each add an `ItemNeed` (any-of ids, qty = first quantity or 1); (4) `QUEST_CHECK` with `constraints.quest` adds that quest's whole `QuestCatalog` requirement plus its `prerequisitesClosure` as quests; an unknown quest id adds `unknown("quest data missing: ID")`; (5) `min_combat_level` -> `combatLevel`; (6) `required_region`/`allowed_regions` -> `chunk` (first element for required; allowed = at least one of them: record the first and add `unknown("any of regions")` only if more than one — simple and honest); (7) `completion_type` in `{VARBIT_CHECK, VARP_CHECK, COMBAT_ACHIEVEMENT, RAID_CHALLENGE, NPC_DIALOGUE}` or `constraints.varbit_id`/`varp_id` present adds `unknown("...")` naming the type (these cannot be judged from skills/items alone); (8) `NPC_KILL` with one-hit marker is handled in Task 6, not here.

- [ ] **Step 1: Write failing tests** — build tasks from tiny JSON via the real Gson adapter so the deserializer is exercised:

```java
class RequirementDeriverTest
{
	private static final Gson GSON = new GsonBuilder().create(); // NuzlockeTask has @JsonAdapter, plain Gson works

	private static NuzlockeTask task(String json)
	{
		return GSON.fromJson(json, NuzlockeTask.class);
	}

	private RequirementDeriver deriver()
	{
		return new RequirementDeriver(QuestCatalog.build(GpsData.empty(null), QuestHelperEntry.loadFromResource()), Overrides.empty());
	}

	@Test
	void skillTaskDerivesSkillLevel()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30,\"base_points\":1}"));
		assertEquals(30, (int) r.getSkills().get("COOKING"));
	}

	@Test
	void completionTypeIsCaseInsensitive()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"Cooking\",\"level\":30}"));
		assertEquals(30, (int) r.getSkills().get("COOKING"));
	}

	@Test
	void requiredItemsBecomeItemNeeds()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Plant\",\"taskID\":\"p\",\"completion_type\":\"FARMING\",\"level\":1,\"required_items\":[{\"item\":[\"Potato Seed\"],\"item_ids\":[5318],\"quantity\":[1]}]}"));
		assertEquals(1, r.getItems().size());
		assertTrue(r.getItems().get(0).getAnyOf().contains(5318));
	}

	@Test
	void questCheckPullsQuestRequirementAndPrerequisites()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"ANIMAL_MAGNETISM\"}}"));
		assertTrue(r.getQuests().contains("ERNEST_THE_CHICKEN"));
		assertEquals(19, (int) r.getSkills().get("CRAFTING"));
	}

	@Test
	void unknownQuestIdIsUnknownRequirement()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Q\",\"taskID\":\"q\",\"completion_type\":\"QUEST_CHECK\",\"constraints\":{\"quest\":\"NO_SUCH_QUEST\"}}"));
		assertTrue(r.isUnknown());
	}

	@Test
	void varbitAndCaTasksAreUnknown()
	{
		assertTrue(deriver().derive(task("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\"}")).isUnknown());
		assertTrue(deriver().derive(task("{\"name\":\"C\",\"taskID\":\"c\",\"completion_type\":\"COMBAT_ACHIEVEMENT\",\"ca_ids\":[1]}")).isUnknown());
	}

	@Test
	void overrideReplacesDerived(@TempDir Path tmp) throws Exception
	{
		Files.createDirectories(tmp.resolve("overrides"));
		Files.write(tmp.resolve("overrides/requirement_overrides.json"),
			"{\"c\":{\"skills\":{\"COOKING\":50}}}".getBytes(StandardCharsets.UTF_8));
		RequirementDeriver d = new RequirementDeriver(QuestCatalog.build(GpsData.empty(null), Collections.emptyMap()), Overrides.load(tmp));
		Requirement r = d.derive(task("{\"name\":\"Cook\",\"taskID\":\"c\",\"completion_type\":\"COOKING\",\"level\":30}"));
		assertEquals(50, (int) r.getSkills().get("COOKING"));
	}

	@Test
	void taskWithNothingRequiredIsNone()
	{
		Requirement r = deriver().derive(task("{\"name\":\"Free\",\"taskID\":\"f\",\"completion_type\":\"EQUIP\"}"));
		assertTrue(r.getSkills().isEmpty());
		assertFalse(r.isUnknown());
	}
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.eligibility.RequirementDeriverTest` — Expected: compile errors.
- [ ] **Step 3: Implement** the derivation rules above in `RequirementDeriver.derive` (use `String type = t.getCompletionType() == null ? "" : t.getCompletionType().toUpperCase(Locale.ROOT)`). Skill-type to skill mapping is a static `Map<String,String>` (`"SKILL_THRESHOLD"` takes the skill from `constraints.required_skill`; `"RUNECRAFTING"` -> `"RUNECRAFT"`; other types map to themselves). `Overrides` parses with Gson into `Map<String, OverrideDto>` and converts.
- [ ] **Step 4: Run to verify pass** — Expected: PASS. If a getter name differs from what the tests assume (e.g. `RequiredItem` accessors), fix the implementation and the test helper to the real class; do not edit the model classes.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/eligibility src/test/java/com/chunkblazer/gps/eligibility
git commit -m "feat(gps): RequirementDeriver from catalog fields plus local overrides

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 5: `MaxHitCalculator`

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/combat/CombatStats.java`, `MagicSpells.java`, `MaxHitCalculator.java`
- Test: `src/test/java/com/chunkblazer/gps/combat/MaxHitCalculatorTest.java`

**Interfaces:**
- Produces:
  - `CombatStats` (`@Value` + builder): `int strength, ranged, magic` (boosted level, default 1), `int strengthBonus, rangedStrengthBonus` (equipment `str` bonuses), `int magicDamagePercent` (e.g. 10 for +10%), `Style style` (`enum Style { AGGRESSIVE, CONTROLLED, ACCURATE, DEFENSIVE, RAPID, LONGRANGE }`), `double prayerStrengthMultiplier` (default 1.0; piety 1.23), `double prayerRangedMultiplier` (default 1.0; rigour 1.23), `double voidMelee, voidRanged, voidMagicDamage` multipliers (default 1.0), `double gearMultiplier` (slayer helm/salve, default 1.0).
  - `MaxHitCalculator.melee(CombatStats)`: `effStr = floor(strength * prayerStrengthMultiplier) + 8 + styleBonus` where styleBonus is `3` AGGRESSIVE, `1` CONTROLLED, `0` otherwise; `effStr = floor(effStr * voidMelee)`; `base = floor(0.5 + effStr * (strengthBonus + 64) / 640.0)`; result `floor(base * gearMultiplier)`.
  - `MaxHitCalculator.ranged(CombatStats)`: `effRng = floor(ranged * prayerRangedMultiplier) + 8 + (style ACCURATE ? 3 : 0)`; `effRng = floor(effRng * voidRanged)`; `base = floor(0.5 + effRng * (rangedStrengthBonus + 64) / 640.0)`; result `floor(base * gearMultiplier)`.
  - `MaxHitCalculator.magic(CombatStats, String spell)`: `MagicSpells.baseMaxHit(spell, magicLevel)` then `floor(base * (1 + magicDamagePercent/100.0) * voidMagicDamage)`; unknown spell returns `0`.
  - `MagicSpells.baseMaxHit(String name, int magicLevel) -> int`: standard spellbook table (case-insensitive): strikes Wind 2, Water 4, Earth 6, Fire 8; bolts 9, 10, 11, 12; blasts 13, 14, 15, 16; waves 17, 18, 19, 20; surges 21, 22, 23, 24 (names like `"fire surge"`); powered staves: `"trident of the seas"` = `floor(level/3) - 5`, `"trident of the swamp"` = `floor(level/3) - 2`, `"sanguinesti staff"` = `floor(level/3) - 1`, `"tumeken's shadow"` = `floor(level/3) + 1`... use only the first three plus shadow as `floor(level/3)+1` marked `// approximate` in a comment. Stored in a `Map<String,Integer>` for fixed spells and a small `Map<String,IntUnaryOperator>` for staves.
  - `MaxHitCalculator.best(CombatStats, List<String> availableSpells) -> int` = max of melee, ranged, best magic over the given spells.

- [ ] **Step 1: Write failing tests** (expected values are hand-derived from the formulas above; the implementer must additionally check ONE value locally against the wiki DPS calculator and record it in a comment, since this plan cannot fetch the wiki):

```java
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
		assertEquals(1, MaxHitCalculator.melee(CombatStats.builder().strength(1).style(CombatStats.Style.ACCURATE).build()));
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
		assertEquals(28, MaxHitCalculator.magic(CombatStats.builder().magic(99).build(), "trident of the swamp")); // 33-2=31? see note
		assertEquals(0, MaxHitCalculator.magic(s, "no such spell"));
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
}
```

Correct the trident line before running: `floor(99/3) - 2 = 31`, so assert `31`, not 28 (the test as drafted above is wrong on that one assertion; fix it to `31` in Step 1 so the plan's test is right).

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.combat.MaxHitCalculatorTest` — Expected: compile errors.
- [ ] **Step 3: Implement** exactly the formulas in the Interfaces block with `Math.floor` and `int` casts at the marked points.
- [ ] **Step 4: Run to verify pass** — Expected: PASS. Then add a code comment in the test recording the one value you verified in the wiki DPS calculator and the date.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/combat src/test/java/com/chunkblazer/gps/combat
git commit -m "feat(gps): MaxHitCalculator (melee, ranged, magic)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 6: One-hit tasks

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/combat/OneHitCheck.java`
- Modify: `src/main/java/com/chunkblazer/gps/eligibility/RequirementDeriver.java` (rule 8)
- Test: `src/test/java/com/chunkblazer/gps/combat/OneHitCheckTest.java`, add cases to `RequirementDeriverTest`

**Interfaces:**
- Consumes: `NuzlockeTask`, `MonsterDef` list from `GpsData`.
- Produces:
  - `OneHitCheck.isOneHit(NuzlockeTask t) -> boolean`: completion type `NPC_KILL` (case-insensitive), `constraints.time_in_ticks` present and its first element `== 1`. (A limit like `[20]` is NOT one-hit.)
  - `OneHitCheck.targetHp(NuzlockeTask t, GpsData d) -> OptionalInt`: the maximum `hp` over `MonsterDef`s whose ids intersect `target_npc.npc_ids` (or whose name equals a `target_npc.npc` entry, stripping any `#Variant` suffix); empty when no match.
  - `RequirementDeriver.derive` rule 8: for a one-hit task, if `targetHp` is present add `minMaxHit(hp)`, else `unknown("one-hit target HP unknown (no monster data)")`. `RequirementDeriver` therefore takes `GpsData` too: constructor becomes `RequirementDeriver(QuestCatalog, Overrides, GpsData)`; update Task 4's tests/helper accordingly in this task.

- [ ] **Step 1: Write the failing tests**

```java
class OneHitCheckTest
{
	private static NuzlockeTask task(String json) { return new Gson().fromJson(json, NuzlockeTask.class); }

	private static final String ONE_HIT = "{\"name\":\"Defeat a Level 20 Dark Wizard 1 Hit\",\"taskID\":\"w\",\"completion_type\":\"NPC_Kill\","
		+ "\"target_npc\":{\"npc\":[\"Wizard\"],\"npc_ids\":[510,5088],\"quantity\":[1]},\"constraints\":{\"time_in_ticks\":[1]}}";

	@Test
	void oneTickKillIsOneHit()
	{
		assertTrue(OneHitCheck.isOneHit(task(ONE_HIT)));
	}

	@Test
	void twentyTickKillIsNotOneHit()
	{
		assertFalse(OneHitCheck.isOneHit(task(ONE_HIT.replace("[1]}", "[20]}"))));
	}

	@Test
	void nonKillTaskIsNotOneHit()
	{
		assertFalse(OneHitCheck.isOneHit(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"COOKING\",\"constraints\":{\"time_in_ticks\":[1]}}")));
	}

	@Test
	void hpComesFromMonsterDataMaxOverMatchingIds()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 12), monster(Arrays.asList(5088), "Dark wizard", 14)));
		assertEquals(14, OneHitCheck.targetHp(task(ONE_HIT), d).getAsInt());
	}

	@Test
	void noMonsterDataGivesEmptyAndDeriverMarksUnknown()
	{
		assertFalse(OneHitCheck.targetHp(task(ONE_HIT), GpsData.empty(null)).isPresent());
		RequirementDeriver der = new RequirementDeriver(QuestCatalog.build(GpsData.empty(null), Collections.emptyMap()), Overrides.empty(), GpsData.empty(null));
		assertTrue(der.derive(task(ONE_HIT)).isUnknown());
	}

	@Test
	void withMonsterDataDeriverSetsMinMaxHit()
	{
		GpsData d = GpsData.withMonsters(Arrays.asList(monster(Arrays.asList(510), "Dark wizard", 12)));
		RequirementDeriver der = new RequirementDeriver(QuestCatalog.build(d, Collections.emptyMap()), Overrides.empty(), d);
		assertEquals(12, der.derive(task(ONE_HIT)).getMinMaxHit());
	}
}
```

(`GpsData.withMonsters(List)` is a small test-support factory added to `GpsData` in this task, documented as test/support use; `monster(ids, name, hp)` is a private test helper that builds a `MonsterDef` via Gson from a JSON string.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `OneHitCheck` and rule 8; add `GpsData.withMonsters`.
- [ ] **Step 4: Run** `./gradlew test --tests "com.chunkblazer.gps.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): one-hit task detection and max-hit requirement

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 7: `EligibilityEngine` and the catalog-wide test

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/eligibility/Eligibility.java`, `EligibilityEngine.java`
- Test: `src/test/java/com/chunkblazer/gps/eligibility/EligibilityEngineTest.java`, `CatalogWideDerivationTest.java`

**Interfaces:**
- Consumes: `RequirementDeriver`, `QuestCatalog`, `PlayerState`.
- Produces: `Eligibility { Status status; List<Missing> missing }` (`@Value`; `Status`: `DOABLE` when no missing, `UNKNOWN` when the only missing entries are `Kind.UNKNOWN`, `BLOCKED` otherwise), `EligibilityEngine(RequirementDeriver, QuestCatalog, GpsData)` with `evaluate(NuzlockeTask, PlayerState)` and `evaluateQuest(String questId, PlayerState)` (quest's own requirement + prerequisite quests not yet complete listed as QUEST missing; unknown id -> UNKNOWN). Also `static List<NuzlockeTask> loadCatalogTasks()` in test support `CatalogTestSupport` that reads `tasks_catalog.json.gz` from the classpath and flattens every `"<Area>_Tasks"` list's `tasks` into `NuzlockeTask`s (skip keys starting with `_`; mirror the structure printed by inspecting the file: top-level map file -> `{ "<name>": [ {region_id, Friendly_Name, neighbor_ids, tasks:[...]} ] }`).

- [ ] **Step 1: Write the failing tests**

```java
class EligibilityEngineTest
{
	private EligibilityEngine engine(GpsData data)
	{
		QuestCatalog q = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
		return new EligibilityEngine(new RequirementDeriver(q, Overrides.empty(), data), q, data);
	}

	@Test
	void freshAccountBlockedOnLevel30Cooking()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluate(task("COOKING", 30), PlayerState.freshAccount());
		assertEquals(Eligibility.Status.BLOCKED, e.getStatus());
	}

	@Test
	void doableWhenLevelMet()
	{
		PlayerState s = PlayerState.builder().level("COOKING", 30).build();
		assertEquals(Eligibility.Status.DOABLE, engine(GpsData.empty(null)).evaluate(task("COOKING", 30), s).getStatus());
	}

	@Test
	void varbitTaskIsUnknownNotDoable()
	{
		NuzlockeTask t = new Gson().fromJson("{\"name\":\"V\",\"taskID\":\"v\",\"completion_type\":\"VARBIT_CHECK\"}", NuzlockeTask.class);
		assertEquals(Eligibility.Status.UNKNOWN, engine(GpsData.empty(null)).evaluate(t, PlayerState.freshAccount()).getStatus());
	}

	@Test
	void questListsUncompletedPrerequisites()
	{
		Eligibility e = engine(GpsData.empty(null)).evaluateQuest("ANIMAL_MAGNETISM", PlayerState.freshAccount());
		assertEquals(Eligibility.Status.BLOCKED, e.getStatus());
		assertTrue(e.getMissing().stream().anyMatch(m -> m.getKind() == Missing.Kind.QUEST));
	}

	@Test
	void unknownQuestIsUnknown()
	{
		assertEquals(Eligibility.Status.UNKNOWN, engine(GpsData.empty(null)).evaluateQuest("NOPE", PlayerState.freshAccount()).getStatus());
	}

	@Test
	void missingGpsDataNeverThrows()
	{
		assertDoesNotThrow(() -> engine(GpsData.empty(null)).evaluate(task("NPC_KILL", 0), PlayerState.freshAccount()));
	}

	private static NuzlockeTask task(String type, int level)
	{
		return new Gson().fromJson("{\"name\":\"T\",\"taskID\":\"t\",\"completion_type\":\"" + type + "\",\"level\":" + level + "}", NuzlockeTask.class);
	}
}
```

```java
class CatalogWideDerivationTest
{
	@Test
	void everyCatalogTaskDerivesWithoutThrowing()
	{
		GpsData data = GpsData.empty(null);
		QuestCatalog q = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
		RequirementDeriver d = new RequirementDeriver(q, Overrides.empty(), data);
		List<NuzlockeTask> tasks = CatalogTestSupport.loadCatalogTasks();
		assertTrue(tasks.size() > 4900);
		for (NuzlockeTask t : tasks)
		{
			Requirement r = assertDoesNotThrow(() -> d.derive(t), t.getTaskId());
			assertNotNull(r);
		}
	}

	@Test
	void everyTaskIsEitherRequirementBearingNoneOrExplicitlyUnknown()
	{
		// the "NONE" bucket must be a conscious set: list its completion types so a new unmodelled type is noticed.
		GpsData data = GpsData.empty(null);
		QuestCatalog q = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
		RequirementDeriver d = new RequirementDeriver(q, Overrides.empty(), data);
		Set<String> noneTypes = new TreeSet<>();
		for (NuzlockeTask t : CatalogTestSupport.loadCatalogTasks())
		{
			Requirement r = d.derive(t);
			boolean none = r.getSkills().isEmpty() && r.getQuests().isEmpty() && r.getItems().isEmpty() && r.getChunks().isEmpty()
				&& r.getCombatLevel() == 0 && r.getMinMaxHit() == 0 && !r.isUnknown();
			if (none && t.getCompletionType() != null)
			{
				noneTypes.add(t.getCompletionType().toUpperCase(Locale.ROOT));
			}
		}
		// Only types that legitimately need nothing may appear here. Update this set deliberately.
		assertTrue(EXPECTED_NONE_TYPES.containsAll(noneTypes), "Unexpected requirement-free types: " + noneTypes);
	}

	private static final Set<String> EXPECTED_NONE_TYPES = new HashSet<>(Arrays.asList(
		"EQUIP", "OBTAIN", "NPC_KILL", "AGILITY", "FARMING", "FISHING", "MINING", "WOODCUTTING", "COOKING", "FLETCHING",
		"CRAFTING", "THIEVING", "CONSTRUCTION", "SMITHING", "SLAYER", "FIREMAKING", "HERBLORE", "HUNTER", "RUNECRAFTING", "PRAYER"));
}
```

If the second test fails on first run, inspect the printed set; add types to `EXPECTED_NONE_TYPES` only after confirming the task genuinely needs nothing derivable, otherwise extend the deriver (new test first).

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `Eligibility` and `EligibilityEngine` (`evaluate` = `deriver.derive(t).missing(state)` -> status mapping; `evaluateQuest` = quest requirement `missing` plus `QUEST` entries for each incomplete id in `prerequisitesClosure`), and `CatalogTestSupport.loadCatalogTasks()` (read the gz resource with `GZIPInputStream`, parse with `JsonParser`, iterate as described).
- [ ] **Step 4: Run** `./gradlew test --tests "com.chunkblazer.gps.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): EligibilityEngine with catalog-wide derivation test

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 8: `PlayerStateProvider` adapter and `SimulatedState`

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/adapter/PlayerStateProvider.java`, `SimulatedState.java`
- Test: `src/test/java/com/chunkblazer/gps/adapter/SimulatedStateTest.java`, `PlayerStateProviderTest.java` (Mockito)

**Interfaces:**
- Consumes: RuneLite `Client` (`getRealSkillLevel(Skill)`, `getSkillExperience`, `getItemContainer(InventoryID.INV/WORN)`), `ItemManager` (not needed in M2), `ChunkBlazerPlugin.getUnlockedRegionIds()` (`Set<String>`; the adapter takes a `Supplier<Set<String>>` to stay testable), `QuestRequirements`-style quest state: use `client.getVarpValue`-free approach by delegating to a `Predicate<String> questFinished` supplied by the plugin (the existing `QuestRequirements` class already reads quest state; the plugin passes a method reference such as `questRequirements::isQuestFinished` — open `QuestRequirements.java` and use its real method name; if none is public, add the minimal public wrapper `boolean isFinished(String questEnumName)` there using the same `Quest.valueOf(..).getState(client)` call it already uses).
- Produces:
  - `PlayerStateProvider(Client client, Supplier<Set<String>> unlockedRegionIds, Predicate<String> questFinished, Supplier<Map<Integer,Integer>> bankItems)`; `PlayerState snapshot()` must be called on the client thread (document it); builds levels/xp for all 23 skills, owned items = inventory + worn + bank (bank supplier returns empty until M5), unlocked chunks from the supplier (strings parsed to ints, bad entries skipped), completed quests = every `Quest` enum name for which `questFinished` is true (iterate `Quest.values()`), quest points from the varp the existing code uses (copy from `QuestRequirements`), combat level `client.getLocalPlayer().getCombatLevel()` when the player is non-null, then `maxHit(...)` from `MaxHitCalculator.best` with equipped item stats left as zero in M2 (documented limitation: gear bonuses are wired in M5 with `ItemManager.getItemStats`; the max-hit for real mode is therefore a level-only lower bound, flagged `maxHitApproximate`).
  - `SimulatedState`: mutable editor model wrapping a `PlayerState.Builder`: `setLevel(skill, lvl)`, `toggleQuest(id)`, `setChunk(regionId, boolean)`, `setItem(id, qty)`, `setMaxHit(int)`, `PlayerState build()`, `static SimulatedState fromReal(PlayerState)` (copy to start editing), `static SimulatedState fresh()`.

- [ ] **Step 1: Write failing tests**

```java
class SimulatedStateTest
{
	@Test
	void editsProduceMatchingState()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setLevel("COOKING", 40);
		s.toggleQuest("COOKS_ASSISTANT");
		s.setChunk(12850, true);
		s.setItem(1944, 2);
		PlayerState p = s.build();
		assertEquals(40, p.getLevel("COOKING"));
		assertTrue(p.hasQuest("COOKS_ASSISTANT"));
		assertTrue(p.hasUnlockedChunk(12850));
		assertEquals(2, p.ownedCount(1944));
		s.toggleQuest("COOKS_ASSISTANT");
		assertFalse(s.build().hasQuest("COOKS_ASSISTANT"));
	}

	@Test
	void fromRealCopiesAndDoesNotAlias()
	{
		PlayerState real = PlayerState.builder().level("MINING", 50).build();
		SimulatedState s = SimulatedState.fromReal(real);
		s.setLevel("MINING", 60);
		assertEquals(50, real.getLevel("MINING"));
		assertEquals(60, s.build().getLevel("MINING"));
	}

	@Test
	void levelClampedToValidRange()
	{
		SimulatedState s = SimulatedState.fresh();
		s.setLevel("COOKING", 150);
		assertEquals(99, s.build().getLevel("COOKING"));
		s.setLevel("COOKING", -3);
		assertEquals(1, s.build().getLevel("COOKING"));
	}
}
```

```java
class PlayerStateProviderTest
{
	@Test
	void mapsClientSkillsAndChunksAndSkipsBadChunkIds()
	{
		Client client = mock(Client.class);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getRealSkillLevel(Skill.COOKING)).thenReturn(33);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		when(client.getLocalPlayer()).thenReturn(null);
		PlayerStateProvider p = new PlayerStateProvider(client, () -> new HashSet<>(Arrays.asList("12850", "oops")),
			q -> q.equals("COOKS_ASSISTANT"), Collections::emptyMap);
		PlayerState s = p.snapshot();
		assertEquals(33, s.getLevel("COOKING"));
		assertTrue(s.hasUnlockedChunk(12850));
		assertEquals(1, s.getUnlockedChunks().size());
		assertTrue(s.hasQuest("COOKS_ASSISTANT"));
	}
}
```

(If `Client.getItemContainer` returns null on the mock the provider must tolerate it: assert `ownedCount(1)==0`.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** both classes. Tolerate null containers/players everywhere.
- [ ] **Step 4: Run** `./gradlew test --tests "com.chunkblazer.gps.adapter.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/adapter src/test/java/com/chunkblazer/gps/adapter src/main/java/com/chunkblazer/QuestRequirements.java
git commit -m "feat(gps): PlayerStateProvider adapter and SimulatedState

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

(Only stage `QuestRequirements.java` if you had to add the wrapper.)

### Task 9: GPS panel (basic doable/blocked list) and plugin wiring

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/ui/GpsPanel.java`, `GpsPanelModel.java`
- Modify: `src/main/java/com/chunkblazer/ChunkBlazerPlugin.java` (create provider + engine, register a second `NavigationButton`; minimal), `ChunkBlazerConfig.java` already has `gpsDataDir`
- Test: `src/test/java/com/chunkblazer/gps/ui/GpsPanelModelTest.java`

**Interfaces:**
- Consumes: `EligibilityEngine`, `PlayerStateProvider`, `SimulatedState`, `GpsDataLocator`, catalog tasks from the plugin (the plugin already holds the loaded task list; find the accessor that the task window uses, e.g. search for where `NuzlockeTask` lists are built and reuse it; do not reload the catalog).
- Produces: `GpsPanelModel` (pure, testable): `GpsPanelModel(EligibilityEngine, List<NuzlockeTask> tasks)`; `List<Row> rows(PlayerState s, Filter f)` where `Row { NuzlockeTask task; Eligibility eligibility; }` sorted DOABLE first then UNKNOWN then BLOCKED, then by `basePoints` descending; `Filter { boolean showDoable, showBlocked, showUnknown; String text; }`; `Summary summary(List<Row>)` (counts). `GpsPanel extends PluginPanel`: top row with Real/Simulate toggle, a data-status label from `GpsData.getStatus().describe()`, filter checkboxes + search, scrollable list where each row shows task name, status colour, and one line per `Missing` (blocked rows), plus a quests section using `evaluateQuest` for every `QuestCatalog.all()` quest. Simulate mode shows a minimal level editor (23 skills, JSpinner 1-99) bound to `SimulatedState`; chunk picker and full editor are M5.
- Wiring: in `ChunkBlazerPlugin.startUp` build `GpsData` off the client thread (`executor.submit`), then create the panel; register a nav button "GPS" using the existing icon resource. `shutDown` removes it. Keep the diff to the plugin under about 40 lines; if more is needed, put it in a `GpsModule` class in `com.chunkblazer.gps.ui` and call that.

- [ ] **Step 1: Write failing tests for the pure model**

```java
class GpsPanelModelTest
{
	private static NuzlockeTask task(String id, String type, int level, int points)
	{
		return new Gson().fromJson("{\"name\":\"" + id + "\",\"taskID\":\"" + id + "\",\"completion_type\":\"" + type + "\",\"level\":" + level + ",\"base_points\":" + points + "}", NuzlockeTask.class);
	}

	private GpsPanelModel model(List<NuzlockeTask> tasks)
	{
		GpsData d = GpsData.empty(null);
		QuestCatalog q = QuestCatalog.build(d, QuestHelperEntry.loadFromResource());
		return new GpsPanelModel(new EligibilityEngine(new RequirementDeriver(q, Overrides.empty(), d), q, d), tasks);
	}

	@Test
	void doableFirstThenBlockedAndPointsDescending()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1), task("c", "COOKING", 1, 5));
		List<GpsPanelModel.Row> rows = model(ts).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all());
		assertEquals(Arrays.asList("c", "b", "a"), rows.stream().map(r -> r.getTask().getTaskId()).collect(Collectors.toList()));
	}

	@Test
	void filtersApply()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1));
		GpsPanelModel.Filter f = GpsPanelModel.Filter.all();
		f.setShowBlocked(false);
		assertEquals(1, model(ts).rows(PlayerState.freshAccount(), f).size());
		f = GpsPanelModel.Filter.all();
		f.setText("a");
		assertEquals(1, model(ts).rows(PlayerState.freshAccount(), f).size());
	}

	@Test
	void summaryCounts()
	{
		List<NuzlockeTask> ts = Arrays.asList(task("a", "COOKING", 50, 9), task("b", "COOKING", 1, 1));
		GpsPanelModel m = model(ts);
		GpsPanelModel.Summary s = m.summary(m.rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all()));
		assertEquals(1, s.getDoable());
		assertEquals(1, s.getBlocked());
	}

	@Test
	void emptyTaskListIsFine()
	{
		assertTrue(model(Collections.emptyList()).rows(PlayerState.freshAccount(), GpsPanelModel.Filter.all()).isEmpty());
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `GpsPanelModel` first, then `GpsPanel` and the wiring (UI code is not unit-tested; verify by launching the dev client per `.bat/run-chunkblazer.bat` or the project's usual run path and checking the GPS button opens a list; record what you saw in the commit body).
- [ ] **Step 4: Run** `./gradlew test` (full suite) — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps/ui
git commit -m "feat(gps): GPS panel listing doable/blocked tasks and quests

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

## Self-Review

- Spec coverage: section 5 requirement model (Tasks 1-4), max hit (5), one-hit (6), eligibility + catalog-wide test (7), real/simulate state (8), panel doable/blocked list incl. one-hit and quests (9), 5.1 degradation (Task 7 `missingGpsDataNeverThrows`, Task 6 unknown path), 5.2 quests in the requirement graph (Task 3 + 4 + 7 `evaluateQuest`). Time-limit DPS estimate (spec: "later milestone") is in M4's optional final task.
- Known simplifications recorded: real-mode max hit is a level-only lower bound until gear bonuses are wired in M5; `required_region` with several allowed regions yields an UNKNOWN marker.
