# Progression GPS M5: UI, Bank Cache, Gear Bonuses and Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the GPS usable end to end: a bank cache and real gear bonuses feeding `PlayerState`, an ordered plan display (quest steps visibly interleaved) with goal selection and automatic re-planning, a full simulate mode (chunk picker, level, quest and item editors), a world-map highlight of the current step's chunk, and in-app help for the data-missing case.

**Architecture:** Pure view-model classes (`PlanViewModel`, `ChunkPickerModel`, `ReplanTrigger`, `BankSnapshot`, `GearBonuses`, `HighlightState`) hold all logic and are unit-tested; Swing panels and the plugin wiring stay thin. Planning runs on the plugin's `ScheduledExecutorService`, never on the client thread; client state is read on the client thread via `ClientThread.invoke` and handed over as an immutable `PlayerState`.

**Tech Stack:** Java 11, Swing (RuneLite `PluginPanel`), Gson, Lombok, JUnit 5, Mockito (existing). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 5 bank/modes, 8). Depends on M1-M4 (`GpsData`, `GpsDataLocator`, `PlayerStateProvider`, `SimulatedState`, `GpsPanel`, `Planner`, `Replanner`, `Plan`, `PlanStep`).

## Global Constraints

- Planning off the client thread; panel updates on the Swing EDT (`SwingUtilities.invokeLater`); client reads on the client thread.
- Unknown bank contents count as "not owned". Bank contents are cached when the player opens the bank and stored per RuneScape profile through the plugin's existing per-account helpers (`getAccountState`/account writes keyed by `gpsBank`), so multiple accounts stay independent and writes before login are never assumed to land.
- No documentation, README text, or license/attribution text about the scraper or wiki data is added to the repo outside the already-committed spec and plans; the in-app help states only that GPS data was not found at the configured folder.
- Real mode accessible chunks = `getUnlockedRegionIds()` UNION free regions (`isFreeRegion`), as the world-map overlay does.
- `ChunkBlazerPlugin.java` and `ChunkBlazerWorldMapOverlay.java` receive minimal wiring only (target under about 60 added lines in total across both); put logic in `com.chunkblazer.gps.*`.
- Java release 11, tabs, braces on own lines, license header as in `QuestRequirements.java`.

## Review Focus

- Bank never opened (or opened on a different account): owned items must not include phantom bank items, and a bank cache written under account A must not be read under account B. Task 1 tests.
- Player wearing nothing / unknown item ids / `ItemStats` null for an item: gear bonuses must be zero for that slot, never an exception. Task 2 test.
- Burst of events (level-up plus inventory changes in the same tick, or 30 `ItemContainerChanged` events while banking): re-planning must be debounced to at most one plan run per window and never overlap two runs. Task 4 test.
- Plan with zero steps, with only unplannable entries, or with `approximate` steps: the view must render an explanatory row (no blank panel) and mark approximate steps. Task 3 test.
- Logged out / no client player: Real mode must show a clear "log in" state rather than planning from a bogus all-level-1 account. Task 4 test.

## File Structure

New under `src/main/java/com/chunkblazer/gps/` (tests mirror):
- `adapter/BankSnapshot.java` — immutable bank contents + JSON (de)serialisation
- `adapter/GearBonuses.java` — sums equipment stat triples
- `adapter/BankCacheStore.java` — read/write through a `Function`/`BiConsumer` pair (testable without ConfigManager)
- `ui/PlanViewModel.java` — plan -> display rows
- `ui/ReplanTrigger.java` — debounce + single-flight
- `ui/ChunkPickerModel.java` — simulate chunk list
- `ui/PlanHighlight.java` — shared current-step highlight state
- `ui/GpsPlanPanel.java`, `ui/SimulatePanel.java` — Swing views (not unit-tested)
- Modify: `ui/GpsPanel.java` (tabs: Tasks | Plan | Simulate), `adapter/PlayerStateProvider.java` (gear + bank + points), `ChunkBlazerPlugin.java` (bank hook, executor wiring, highlight), `ChunkBlazerWorldMapOverlay.java` (highlight draw)

---

### Task 1: Bank cache

**Files:**
- Create: `adapter/BankSnapshot.java`, `adapter/BankCacheStore.java`
- Test: `src/test/java/com/chunkblazer/gps/adapter/BankSnapshotTest.java`, `BankCacheStoreTest.java`
- Modify: `adapter/PlayerStateProvider.java` (already takes a `Supplier<Map<Integer,Integer>>` bank supplier from M2; no signature change), `ChunkBlazerPlugin.java` (hook)

**Interfaces:**
- Produces:
  - `BankSnapshot { Map<Integer,Integer> items }` with `static BankSnapshot empty()`, `static BankSnapshot of(Map<Integer,Integer>)` (drops non-positive quantities and the placeholder id -1), `String toJson()`, `static BankSnapshot fromJson(String)` (null/blank/invalid -> `empty()`, never throws), `Map<Integer,Integer> asMap()` (unmodifiable).
  - `BankCacheStore(Function<String,String> read, BiConsumer<String,String> write, BooleanSupplier accountAvailable)`: `BankSnapshot load()` (account unavailable -> `empty()`), `void save(BankSnapshot)` (account unavailable -> no write). The plugin passes `this::getAccountState`, an account-state writer, and `this::isAccountStateAvailable` (read those private methods in `ChunkBlazerPlugin` and expose them through method references or tiny package-private wrappers). Key constant `BankCacheStore.KEY = "gpsBank"`.
- Plugin hook (minimal): `@Subscribe onItemContainerChanged(ItemContainerChanged e)`: when `e.getContainerId() == InventoryID.BANK` (use the same container-id constant style as existing code: `InventoryID.BANK`), build the map from `e.getItemContainer().getItems()` (id -> summed quantity, skipping ids <= 0, canonicalised with `itemManager.canonicalize(id)` so noted/placeholder variants merge), call `bankCacheStore.save(BankSnapshot.of(map))` on the executor (not the client thread) and refresh the cached in-memory snapshot used by the provider supplier.

- [ ] **Step 1: Write the failing tests**

```java
class BankSnapshotTest
{
	@Test
	void roundTrip()
	{
		Map<Integer, Integer> m = new HashMap<>();
		m.put(554, 1000);
		m.put(1944, 3);
		BankSnapshot b = BankSnapshot.fromJson(BankSnapshot.of(m).toJson());
		assertEquals(1000, (int) b.asMap().get(554));
		assertEquals(3, (int) b.asMap().get(1944));
	}

	@Test
	void dropsNonPositiveAndPlaceholders()
	{
		Map<Integer, Integer> m = new HashMap<>();
		m.put(-1, 5);
		m.put(0, 5);
		m.put(100, 0);
		m.put(101, 2);
		assertEquals(Collections.singleton(101), BankSnapshot.of(m).asMap().keySet());
	}

	@Test
	void garbageIsEmptyNotException()
	{
		assertTrue(BankSnapshot.fromJson(null).asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("").asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("{not json").asMap().isEmpty());
		assertTrue(BankSnapshot.fromJson("[1,2,3]").asMap().isEmpty());
	}
}
```

```java
class BankCacheStoreTest
{
	@Test
	void savedSnapshotIsLoadedBack()
	{
		Map<String, String> backing = new HashMap<>();
		BankCacheStore s = new BankCacheStore(backing::get, backing::put, () -> true);
		s.save(BankSnapshot.of(Collections.singletonMap(554, 7)));
		assertEquals(7, (int) s.load().asMap().get(554));
		assertTrue(backing.containsKey(BankCacheStore.KEY));
	}

	@Test
	void noAccountMeansNoWriteAndEmptyLoad()
	{
		Map<String, String> backing = new HashMap<>();
		BankCacheStore s = new BankCacheStore(backing::get, backing::put, () -> false);
		s.save(BankSnapshot.of(Collections.singletonMap(554, 7)));
		assertTrue(backing.isEmpty());
		assertTrue(s.load().asMap().isEmpty());
	}

	@Test
	void differentAccountsAreIndependentBecauseBackingIsPerAccount()
	{
		Map<String, String> a = new HashMap<>();
		Map<String, String> b = new HashMap<>();
		new BankCacheStore(a::get, a::put, () -> true).save(BankSnapshot.of(Collections.singletonMap(1, 1)));
		assertTrue(new BankCacheStore(b::get, b::put, () -> true).load().asMap().isEmpty());
	}

	@Test
	void neverOpenedBankLoadsEmpty()
	{
		Map<String, String> backing = new HashMap<>();
		assertTrue(new BankCacheStore(backing::get, backing::put, () -> true).load().asMap().isEmpty());
	}
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "com.chunkblazer.gps.adapter.Bank*"` — Expected: compile errors.
- [ ] **Step 3: Implement** `BankSnapshot` (Gson `Map<Integer,Integer>` via `JsonObject` with string keys; parse defensively and catch `RuntimeException`), `BankCacheStore`, then the plugin hook and a `volatile BankSnapshot bankSnapshot` field loaded once after login (`GameState.LOGGED_IN` handling already exists; add the load next to the existing per-account bootstrap, on the executor) and passed to `PlayerStateProvider` as `() -> bankSnapshot.asMap()`.
- [ ] **Step 4: Run to verify pass** — Expected: PASS. Manual check in the dev client: open the bank, close it, restart, confirm the GPS owned-items reflect the bank (record what you saw in the commit body).
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): per-account bank cache fed by the bank container event

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 2: Gear bonuses in `PlayerStateProvider` (real max hit)

**Files:**
- Create: `adapter/GearBonuses.java`
- Modify: `adapter/PlayerStateProvider.java`, `PlayerState.java` (`maxHitApproximate` stays false once gear is wired)
- Test: `src/test/java/com/chunkblazer/gps/adapter/GearBonusesTest.java`, extend `PlayerStateProviderTest`

**Interfaces:**
- Consumes: RuneLite `ItemManager.getItemStats(int itemId)` -> `ItemStats` (`getEquipment()` -> `ItemEquipmentStats` with `getStr()`, `getRstr()`, `getMdmg()`), worn container (`InventoryID.WORN`).
- Produces: `GearBonuses` (`@Value`: `int strength, rangedStrength; double magicDamagePercent`) with `static GearBonuses sum(List<Triple> worn)` where `Triple { int str; int rstr; double mdmg }` (a plain pure value so the logic is testable without RuneLite types); `PlayerStateProvider` converts each worn item's stats to `Triple` (null stats or null equipment -> skipped) and builds `CombatStats` for `MaxHitCalculator.best`: style defaults `AGGRESSIVE` (melee) / `ACCURATE` (ranged), boosted level = real level (no boost), prayer multipliers 1.0, spells = the standard-spellbook names `MagicSpells.available(magicLevel)` (add that helper: every fixed spell whose level requirement is met; level requirements table: strikes 1/5/9/13, bolts 17/23/29/35, blasts 41/47/53/59, waves 62/66/70/75, surges 81/85/90/95, in wind/water/earth/fire order).
- The provider constructor gains an `ItemManager` parameter (update M2 call sites and tests).

- [ ] **Step 1: Write the failing tests**

```java
class GearBonusesTest
{
	@Test
	void sumsAcrossSlots()
	{
		GearBonuses g = GearBonuses.sum(Arrays.asList(new GearBonuses.Triple(82, 0, 0), new GearBonuses.Triple(8, 0, 0), new GearBonuses.Triple(0, 0, 10)));
		assertEquals(90, g.getStrength());
		assertEquals(0, g.getRangedStrength());
		assertEquals(10.0, g.getMagicDamagePercent(), 0.0001);
	}

	@Test
	void emptyIsZero()
	{
		GearBonuses g = GearBonuses.sum(Collections.emptyList());
		assertEquals(0, g.getStrength());
	}
}
```

Extend `PlayerStateProviderTest`: mock `ItemManager.getItemStats(4151)` to return stats with `str=82`, a worn container holding id 4151, strength level 99; assert `snapshot().getMaxHit() >= 25` (whip melee formula from M2: 25), and a second test where `getItemStats` returns `null` for the worn id still yields a snapshot (max hit from levels only) without exception. Also a `MagicSpellsTest` asserting `MagicSpells.available(1)` contains `"wind strike"` and not `"fire strike"`, `available(13)` contains `"fire strike"`, `available(99)` contains `"fire surge"`.

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement.** Inventory/worn containers are read on the client thread inside `snapshot()` (already required).
- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.chunkblazer.gps.*"` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): real max hit from worn gear stats

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 3: `PlanViewModel` (ordered display with quest interleaving visible)

**Files:**
- Create: `ui/PlanViewModel.java`
- Test: `src/test/java/com/chunkblazer/gps/ui/PlanViewModelTest.java`

**Interfaces:**
- Consumes: `Plan`, `PlanStep`, `NodeType`.
- Produces: `PlanViewModel.build(Plan plan, GpsDataStatus status, String dataVersion) -> View` with `View { List<Row> rows; String header; String footer; }` and `Row { int order; String title; String subtitle; String badge; String travelText; boolean approximate; boolean unplannable; Kind kind }` (`Kind`: `STEP`, `QUEST_STEP`, `TRAINING`, `ACQUIRE`, `UNPLANNABLE`, `INFO`).
  - Quest steps: `title` = step label, `badge` = quest display name with progress `"Cook's Assistant 2/5"` (progress counts the quest's steps present in the plan; derive the quest total from `stepIndex` and the count of that quest's steps in the plan), so interleaved steps of the same quest carry the same badge text and visibly belong together.
  - `travelText`: `formatTicks(int)` -> `"0s"`, `"12s"` (`ticks*0.6` rounded), `"1m 05s"`, `"1h 02m"`; `INF` -> `"unreachable"`.
  - `header`: `"<n> steps, travel ~<total>"`; `footer`: `"GPS data <version>"` or the status description when data is missing.
  - Empty plan with no unplannable: one `INFO` row `"Nothing to plan: complete a goal, unlock chunks, or change the goal."`; plan with only unplannable entries: an `INFO` row `"No plannable steps; see blocked items below."` followed by `UNPLANNABLE` rows with the missing-requirement text lines joined.
  - Approximate steps get `approximate=true` and the subtitle suffix `" (location approximate)"`.

- [ ] **Step 1: Write the failing tests**

```java
class PlanViewModelTest
{
	private static PlanStep step(int order, NodeType t, String label, int ticks, String questId, Integer idx, boolean approx)
	{
		return new PlanStep(order, t, label, new Tile(3200, 3200, 0, false, null), approx, ticks, "reason", Collections.emptyList(), questId, idx, Collections.emptyList());
	}

	@Test
	void formatTicks()
	{
		assertEquals("0s", PlanViewModel.formatTicks(0));
		assertEquals("12s", PlanViewModel.formatTicks(20));      // 20 * 0.6
		assertEquals("1m 05s", PlanViewModel.formatTicks(108));   // 64.8 -> 65s
		assertEquals("1h 02m", PlanViewModel.formatTicks(6200));  // 3720s
		assertEquals("unreachable", PlanViewModel.formatTicks(TravelConfig.INF));
	}

	@Test
	void interleavedQuestStepsShareABadgeWithProgress()
	{
		Plan p = new Plan(Arrays.asList(
			step(1, NodeType.QUEST_STEP, "Talk to Cook", 10, "COOKS_ASSISTANT", 0, false),
			step(2, NodeType.TASK, "Cook shrimp", 5, null, null, false),
			step(3, NodeType.QUEST_STEP, "Hand in egg", 8, "COOKS_ASSISTANT", 1, false)), 23, Collections.emptyList(), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(3, v.getRows().size());
		assertEquals(v.getRows().get(0).getBadge(), v.getRows().get(2).getBadge());
		assertTrue(v.getRows().get(0).getBadge().contains("2"));
		assertNull(v.getRows().get(1).getBadge());
	}

	@Test
	void approximateStepsMarked()
	{
		Plan p = new Plan(Arrays.asList(step(1, NodeType.TASK, "x", 5, null, null, true)), 5, Collections.emptyList(), "v1");
		PlanViewModel.Row r = PlanViewModel.build(p, null, "v1").getRows().get(0);
		assertTrue(r.isApproximate());
		assertTrue(r.getSubtitle().contains("approximate"));
	}

	@Test
	void emptyPlanExplainsItself()
	{
		PlanViewModel.View v = PlanViewModel.build(new Plan(Collections.emptyList(), 0, Collections.emptyList(), "v1"), null, "v1");
		assertEquals(1, v.getRows().size());
		assertEquals(PlanViewModel.Kind.INFO, v.getRows().get(0).getKind());
	}

	@Test
	void onlyUnplannableShowsInfoThenBlockedRows()
	{
		Plan p = new Plan(Collections.emptyList(), 0,
			Arrays.asList(new Plan.Unplannable("Task A", Arrays.asList(new Missing(Missing.Kind.SKILL, "Cooking 30 (you have 1)")))), "v1");
		PlanViewModel.View v = PlanViewModel.build(p, null, "v1");
		assertEquals(PlanViewModel.Kind.INFO, v.getRows().get(0).getKind());
		assertEquals(PlanViewModel.Kind.UNPLANNABLE, v.getRows().get(1).getKind());
		assertTrue(v.getRows().get(1).getSubtitle().contains("Cooking 30"));
	}

	@Test
	void footerShowsStatusWhenDataMissing()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), false, new ArrayList<>(), new ArrayList<>());
		assertTrue(PlanViewModel.build(new Plan(Collections.emptyList(), 0, Collections.emptyList(), null), st, null).getFooter().contains("not found"));
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement.** Quest display name: title-case the id with underscores replaced by spaces unless `QuestCatalog` provides a name (pass an optional `Function<String,String> questName`; default to the title-cased id).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/ui src/test/java/com/chunkblazer/gps/ui
git commit -m "feat(gps): PlanViewModel with quest badges, formatted travel time and empty states

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 4: Plan panel, goal selector and debounced re-planning

**Files:**
- Create: `ui/ReplanTrigger.java`, `ui/GpsPlanPanel.java`
- Modify: `ui/GpsPanel.java` (add the Plan tab), `ChunkBlazerPlugin.java` (event wiring)
- Test: `src/test/java/com/chunkblazer/gps/ui/ReplanTriggerTest.java`

**Interfaces:**
- Produces `ReplanTrigger(LongSupplier clockMillis, long windowMillis, Runnable action)`:
  - `void request()` marks dirty; if no run is in flight and `now - lastRunStart >= windowMillis`, runs `action` immediately; otherwise remembers that a run is pending.
  - `void tick()` (called by a 1 s `executor.scheduleWithFixedDelay`): if dirty, no run in flight and the window has elapsed, run once.
  - `void finished()` must be called by the action when planning completes (single-flight); a request made while in flight sets dirty and re-runs after `finished()` once the window allows.
  - Window default 1500 ms. Thread-safe (`synchronized`).
- `GpsPlanPanel`: goal combo (Max points / Unlock next chunk / Train skill + skill and target-level inputs / Max gear), Real/Simulate state source (shared with the Tasks tab), "Plan now" button, "Auto re-plan" checkbox, list rendered from `PlanViewModel` (each row: order, title, badge, travel text, subtitle with reason; blocked rows dimmed; approximate marked with a tilde), header and footer labels. When Real mode and the client is not logged in (`snapshot` unavailable), show `"Log in to plan from your real account, or switch to Simulate."` and do not call the planner.
- Plugin wiring: subscribe to `StatChanged` (level up only: `event.getLevel()` increased vs the cached level), completed-task notification (reuse the existing task-completion callback if present), `ItemContainerChanged` for the inventory container, and call `replanTrigger.request()` only when "Auto re-plan" is on. The action: `clientThread.invoke(() -> snapshot = provider.snapshot(); position = player world tile)` then `executor.submit(() -> { plan = Replanner/Planner...; SwingUtilities.invokeLater(() -> panel.show(plan)); trigger.finished(); })`.

- [ ] **Step 1: Write the failing tests**

```java
class ReplanTriggerTest
{
	private final AtomicLong clock = new AtomicLong(0);
	private final AtomicInteger runs = new AtomicInteger();

	private ReplanTrigger trigger()
	{
		return new ReplanTrigger(clock::get, 1500, runs::incrementAndGet);
	}

	@Test
	void firstRequestRunsImmediately()
	{
		ReplanTrigger t = trigger();
		t.request();
		assertEquals(1, runs.get());
	}

	@Test
	void burstWithinWindowRunsOnceThenOnceMore()
	{
		ReplanTrigger t = trigger();
		t.request();
		t.finished();
		for (int i = 0; i < 30; i++)
		{
			clock.addAndGet(10);
			t.request();
		}
		assertEquals(1, runs.get());   // still inside the window
		clock.set(1600);
		t.tick();
		assertEquals(2, runs.get());   // the 30 requests collapse into one run
		t.finished();
		clock.set(5000);
		t.tick();
		assertEquals(2, runs.get());   // nothing dirty any more
	}

	@Test
	void neverOverlapsRuns()
	{
		ReplanTrigger t = trigger();
		t.request();                   // run 1 in flight, not finished
		clock.set(10_000);
		t.request();
		t.tick();
		assertEquals(1, runs.get());   // single flight
		t.finished();
		t.tick();
		assertEquals(2, runs.get());   // pending request runs after finish
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `ReplanTrigger`, then the panel and plugin wiring (UI untested; verify in the dev client: change a level in-game with auto re-plan on and confirm the list refreshes once; record in the commit body).
- [ ] **Step 4: Run** `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): plan tab with goal selector and debounced single-flight re-planning

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 5: Simulate mode (chunk picker, level, quest and item editors)

**Files:**
- Create: `ui/ChunkPickerModel.java`, `ui/SimulatePanel.java`
- Modify: `adapter/SimulatedState.java` (add `setMaxHit`, `setTaskPoints` if missing), `ui/GpsPanel.java` (Simulate tab)
- Test: `src/test/java/com/chunkblazer/gps/ui/ChunkPickerModelTest.java`

**Interfaces:**
- Consumes: the plugin's `NuzlockeChunk` list (`getRegionIds()`, `getFriendlyName()`, `getArea()`), `SimulatedState`.
- Produces `ChunkPickerModel`:
  - `static ChunkPickerModel of(List<ChunkEntry> entries)` where `ChunkEntry { int regionId; String name; String area }` (built from `NuzlockeChunk`s by `ChunkPickerModel.fromChunks(Collection<NuzlockeChunk>)`: one entry per region id of each chunk, name = friendly name or `"Region <id>"`).
  - `List<ChunkEntry> filter(String text, String area)` (case-insensitive substring on name or id; `area == null` or `"All"` = any), `List<String> areas()` sorted, `Set<Integer> selected()`, `void set(int regionId, boolean on)`, `void setAll(List<ChunkEntry> visible, boolean on)`, `void selectAreaFrom(Set<Integer> real)` (seed selection from the real unlocked set), `int count()`.
- `SimulatePanel` hosts: starting point buttons ("Brand-new account" -> `SimulatedState.fresh()` plus the starting free regions; "Copy my real account" -> `SimulatedState.fromReal(provider.snapshot())`), the chunk picker (search box, area combo, checkbox list, select-all-visible), the 23-skill level spinner grid (from M2), quest toggle list (`QuestCatalog.all()` names, search box), item editor (search `GpsData.getItems()` by name, quantity spinner, add/remove list; disabled with a tooltip "Needs GPS data" when `items.json` is absent), max-hit and task-points spinners. Edits call `SimulatedState` setters and trigger `ReplanTrigger.request()` when auto re-plan is on.

- [ ] **Step 1: Write the failing tests**

```java
class ChunkPickerModelTest
{
	private ChunkPickerModel model()
	{
		return ChunkPickerModel.of(Arrays.asList(
			new ChunkEntry(12850, "Lumbridge", "Misthalin"),
			new ChunkEntry(12853, "Varrock", "Misthalin"),
			new ChunkEntry(11828, "Falador", "Asgarnia")));
	}

	@Test
	void filterByTextAndArea()
	{
		ChunkPickerModel m = model();
		assertEquals(1, m.filter("lumb", null).size());
		assertEquals(2, m.filter("", "Misthalin").size());
		assertEquals(1, m.filter("12853", "All").size());
		assertEquals(0, m.filter("zzz", null).size());
	}

	@Test
	void areasSortedUnique()
	{
		assertEquals(Arrays.asList("Asgarnia", "Misthalin"), model().areas());
	}

	@Test
	void selectionAndSelectAllVisible()
	{
		ChunkPickerModel m = model();
		m.set(12850, true);
		assertEquals(Collections.singleton(12850), m.selected());
		m.setAll(m.filter("", "Misthalin"), true);
		assertEquals(new HashSet<>(Arrays.asList(12850, 12853)), m.selected());
		m.setAll(m.filter("", "Misthalin"), false);
		assertTrue(m.selected().isEmpty());
	}

	@Test
	void seedFromRealIgnoresUnknownRegions()
	{
		ChunkPickerModel m = model();
		m.selectAreaFrom(new HashSet<>(Arrays.asList(12850, 99999)));
		assertEquals(Collections.singleton(12850), m.selected());
	}

	@Test
	void duplicateRegionEntriesCollapse()
	{
		ChunkPickerModel m = ChunkPickerModel.of(Arrays.asList(new ChunkEntry(1, "A", "X"), new ChunkEntry(1, "A", "X")));
		assertEquals(1, m.count());
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** the model, then the panel. Verify in the dev client: Simulate -> brand-new account -> a short plan appears; add a chunk, level 30 Cooking, and see the plan change; record what you saw.
- [ ] **Step 4: Run** `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): simulate mode with chunk picker and state editors

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 6: World-map highlight of the current step (only if cheap)

**Files:**
- Create: `ui/PlanHighlight.java`
- Modify: `ChunkBlazerWorldMapOverlay.java` (draw), `ChunkBlazerPlugin.java` (own a `PlanHighlight`, set it from the plan panel's selected step)
- Test: `src/test/java/com/chunkblazer/gps/ui/PlanHighlightTest.java`

**Cost gate:** if the overlay change needs more than about 25 added lines in `ChunkBlazerWorldMapOverlay.java`, stop and ship without the highlight (record the decision in the commit message of Task 7); the pure class and its tests are still committed.

**Interfaces:** `PlanHighlight` (thread-safe): `void set(int regionId, String label)`, `void clear()`, `OptionalInt regionId()`, `String label()`. `PlanHighlight.regionOf(PlanStep)` -> `OptionalInt` via `TileMath.regionId` of the step's tile (empty when the tile is unknown). The overlay, in the existing per-region loop (`regionId` is already computed in `render`), checks `plugin.getPlanHighlight().regionId()` and strokes that region's rectangle with a distinct colour (use an existing colour constant style; thick 3px stroke) after the normal fill, and draws the label at the rectangle top-left when zoomed in enough that other labels already draw.

- [ ] **Step 1: Write the failing tests**

```java
class PlanHighlightTest
{
	@Test
	void setClearAndRead()
	{
		PlanHighlight h = new PlanHighlight();
		assertFalse(h.regionId().isPresent());
		h.set(12850, "Cook's Assistant 1/3");
		assertEquals(12850, h.regionId().getAsInt());
		assertEquals("Cook's Assistant 1/3", h.label());
		h.clear();
		assertFalse(h.regionId().isPresent());
	}

	@Test
	void regionOfStepUsesTile()
	{
		PlanStep s = new PlanStep(1, NodeType.TASK, "x", new Tile(3222, 3218, 0, false, null), false, 0, "", Collections.emptyList(), null, null, Collections.emptyList());
		assertEquals(12850, PlanHighlight.regionOf(s).getAsInt());
	}

	@Test
	void unknownTileHasNoRegion()
	{
		PlanStep s = new PlanStep(1, NodeType.TASK, "x", new Tile(null, null, 0, true, "NONE"), true, 0, "", Collections.emptyList(), null, null, Collections.emptyList());
		assertFalse(PlanHighlight.regionOf(s).isPresent());
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** the pure class, wire the plan panel (selecting a row, or the first row after a plan, sets the highlight; leaving the tab clears it), and the overlay draw. Verify in the dev client with the world map open.
- [ ] **Step 4: Run** `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): world-map highlight for the selected plan step

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 7: Data-missing help, polish and end-to-end verification

**Files:**
- Modify: `ui/GpsPanel.java` (data-missing state), `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (Status line only)
- Test: extend `PlanViewModelTest` (data-missing wording), add `src/test/java/com/chunkblazer/gps/GpsEndToEndTest.java`

**Interfaces:** the data-missing banner text comes from `GpsDataStatus.describe()` plus the line `"Configured folder: <path> (change it in the ChunkBlazer settings: GPS data folder)."` built by a pure `GpsHelpText.forStatus(GpsDataStatus, Path)` in `gps/ui` (tested). No README or wiki/license text is added anywhere.

- [ ] **Step 1: Write the failing tests**

```java
class GpsHelpTextTest
{
	@Test
	void missingDirectoryMentionsPathAndSetting()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("C:/x/gps"), false, new ArrayList<>(), new ArrayList<>());
		String t = GpsHelpText.forStatus(st, Paths.get("C:/x/gps"));
		assertTrue(t.contains("not found"));
		assertTrue(t.contains("GPS data folder"));
		assertTrue(t.contains("gps"));
	}

	@Test
	void completeDataNeedsNoHelp()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), true, new ArrayList<>(), new ArrayList<>());
		assertEquals("", GpsHelpText.forStatus(st, Paths.get("x")));
	}

	@Test
	void partialDataListsMissingAndInvalid()
	{
		GpsDataStatus st = new GpsDataStatus(Paths.get("x"), true, Arrays.asList("transport.json"), Arrays.asList("items.json"));
		String t = GpsHelpText.forStatus(st, Paths.get("x"));
		assertTrue(t.contains("transport.json"));
		assertTrue(t.contains("items.json"));
	}
}
```

```java
class GpsEndToEndTest
{
	@Test
	void freshAccountWithFixtureDataProducesAPlanAndNoUnplannableCrash()
	{
		// real catalog tasks + synthetic GPS fixture data + real quest_requirements.json, fresh account, Lumbridge unlocked
		GpsData data = GpsDataStore.load(GpsTestFixtures.dir("full"));
		List<TaskRef> tasks = CatalogTestSupport.loadCatalogTaskRefs();
		PlanRequest req = EndToEndSupport.request(data, tasks, PlayerState.freshAccount().withUnlockedChunk(12850), GoalMode.maxPoints());
		Plan p = new Planner(req).plan();
		assertNotNull(p);
		assertTrue(p.getTotalTravelTicks() < TravelConfig.INF);
		assertTrue(Solver.valid(EndToEndSupport.orderOf(p, req), EndToEndSupport.predsOf(req)));
	}

	@Test
	void emptyDataStillPlansFromCatalogOnly()
	{
		List<TaskRef> tasks = CatalogTestSupport.loadCatalogTaskRefs();
		PlanRequest req = EndToEndSupport.request(GpsData.empty(null), tasks, PlayerState.freshAccount().withUnlockedChunk(12850), GoalMode.maxPoints());
		assertDoesNotThrow(() -> new Planner(req).plan());
	}

	@Test
	void everyGoalModeRunsWithoutException()
	{
		List<TaskRef> tasks = CatalogTestSupport.loadCatalogTaskRefs();
		for (GoalMode g : Arrays.asList(GoalMode.maxPoints(), GoalMode.unlockNextChunk(), GoalMode.trainSkill("COOKING", 30), GoalMode.maxGear()))
		{
			PlanRequest req = EndToEndSupport.request(GpsData.empty(null), tasks, PlayerState.freshAccount().withUnlockedChunk(12850), g);
			assertDoesNotThrow(() -> new Planner(req).plan());
		}
	}
}
```

(`CatalogTestSupport.loadCatalogTaskRefs()` extends the M2/M3 support class: for each catalog chunk, one `TaskRef(task, firstRegionId, unlockCost)` per task. `EndToEndSupport.request(...)` builds the `ChunkGraph` from `ChunkNodes.from(loadCatalogChunks())`, a `TransportModel`, `QuestCatalog`, `RequirementDeriver`, and sane defaults; `orderOf`/`predsOf` expose the solver inputs for the validity assertion: implement them by re-running `Selector` and mapping `Plan` step labels back to node indices, written fully as part of this step.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** `GpsHelpText`, wire it into `GpsPanel` (shown above the tabs when `status.isComplete()` is false), set the spec status line to `Status: implemented through M5` (one-line edit), and fix any defect the end-to-end tests expose with a minimal failing test first.
- [ ] **Step 4: Run the full suite and a manual QA pass**

Run: `./gradlew test`
Expected: PASS.
Manual QA in the dev client (write results in the commit body): (1) no data folder: GPS tab shows the help banner, Tasks tab still lists doable/blocked from the catalog, Plan tab plans from catalog-only with approximate locations; (2) with data: plan shows quest steps with shared badges, interleaved; (3) switching Real/Simulate works; (4) logged out shows the log-in message; (5) bank open then restart keeps owned items; (6) 500-node plan completes within a couple of seconds without freezing the client (UI stays responsive since planning is off-thread).
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer src/test/java/com/chunkblazer/gps docs/superpowers/specs/2026-10-14-progression-gps-design.md
git commit -m "feat(gps): data-missing help, end-to-end tests and final polish

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

## Self-Review

- Spec coverage: section 5 bank cache and Real/Simulate modes (Tasks 1, 4, 5); real max hit inputs (Task 2); section 8 GPS tab with ordered plan, goal selector, reasons/missing, total travel time, data-version footer, simulate chunk picker and level editor, world-map highlight "if cheap" with an explicit cost gate (Tasks 3-6); graceful degradation surfaced in UI (Task 7); quest interleaving made visible via shared badges (Task 3).
- Deliberately not done (per user decisions): no README/attribution/license text, no bundled data, no pathfinding, nothing published.
- Known limitations recorded: equipment style/prayer assumptions are fixed (aggressive/accurate, no prayer) in Real mode; simulate mode exposes max hit directly instead.
