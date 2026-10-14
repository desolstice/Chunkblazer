# Progression GPS: Design Spec

Date: 2026-10-14 | Branch: feature/progression-gps | Status: updated with user decisions 2026-10-14

## 1. Goal
Given the chunks a player can access and their current (or simulated) skills and gear, produce an ordered multi-step plan that progresses the account as fast as possible, where "fast" means minimal total travel time. The plan covers gear and upgrades, skill training, quests, and ChunkBlazer tasks (including one-hit/kill tasks judged by max hit vs NPC HP). Each step carries a reason or its missing requirements.

Non-goals: changing the server catalog schema or the Go server; runtime wiki access by players; collision-map pathfinding (permanently out of scope); publishing or bundling any wiki-derived data; changes to PRIVACY.md.

## 2. Existing systems (relevant)
- Chunks: `NuzlockeChunk.java` (region_id, neighbor_ids, charter/boss flags, `TravelConnection` charter routes). Unlocked set: `ChunkBlazerPlugin.getUnlockedRegionIds()`. A region_id is `(x>>6)<<8 | (y>>6)` for world tile (x, y), so a chunk is a 64x64 tile region.
- Catalog: `src/main/resources/com/chunkblazer/tasks_catalog.json.gz` (about 4,970 tasks; a gzipped map of file name -> `{ "<Area>_Tasks": [ {region_id, Friendly_Name, neighbor_ids, tasks:[...]} ] }`), refreshed via `api/CatalogStore.java`. Task model: `NuzlockeTask.java`, `RequiredItem.java`, `TargetNpc.java`, `TaskConstraints.java` (time_in_ticks, required_skill/level, quest, varbits, equipment rules). `Quest_Tasks.json` holds the quest tasks.
- Quest requirements: `QuestRequirements.java` + `quest_requirements.json` (chunks, prerequisite quests, skill levels), derived from Quest Helper.
- Player state: `client.getRealSkillLevel`, `InventoryID.WORN`/`INV`, `itemManager.getItemStats` (see `modules/EquipModule`, `RaidChallengeModule`). No bank reading today.
- No routing, travel-cost, NPC HP/defence, quest-step, or training-method data exists today.
- UI: `ChunkBlazerPanel.java` side panel; `ChunkBlazerWorldMapOverlay.java`.
- Build: Gradle, Java release 11, JUnit 5 + Mockito, Gson and Lombok available, no new dependencies allowed.

## 3. Architecture
- New package `com.chunkblazer.gps`: pure Java, no `Client`/RuneLite-client dependencies in logic classes, fully JUnit-testable. A thin adapter (`PlayerStateProvider`) maps live client state to a `PlayerState` value object; a simulate provider builds one from user edits.
- `ChunkBlazerPlugin.java` receives only minimal wiring (create adapter, register panel tab, bank cache event hook).
- Data flow: local JSON from the gps-data dir (wiki-derived + local overrides) + catalog -> `RequirementModel` -> `EligibilityEngine` -> `Planner` (with `TravelCostModel`) -> `PlanStep` list -> panel tab.

## 4. Data pipeline (offline, local-only, personal use)
**Nothing wiki-derived is ever committed or published.** This tool is for the maintainer's personal use. The scraper script, its raw cache and its processed output stay off GitHub:
- Script: `tools/wiki-scraper/` (Python 3.12, stdlib only). Raw cache: `tools/wiki-scraper/cache/`. These paths are excluded locally via `.git/info/exclude` (NOT the committed `.gitignore`, so the repo never reveals the tool). The committed `.gitignore` already ignores `tools/` for unrelated reasons; the local exclude is the deliberate rule and must be kept.
- Processed output: written outside the repo to the local data dir `~/.runelite/chunkblazer/gps-data/` (i.e. `RuneLite.RUNELITE_DIR/chunkblazer/gps-data/`), overridable by `--out` on the scraper and by a plugin config string `gpsDataDir` (blank = default). Nothing is bundled in `src/main/resources`, nothing is attributed in the README.
- The plugin loads the JSON from that directory at runtime. If a file or the whole directory is absent, the GPS degrades gracefully (section 5.1): the tab explains what is missing, and features that need no wiki data still work.
- Java unit tests never depend on real wiki data: they use small hand-written synthetic fixtures in `src/test/resources/com/chunkblazer/gps/`.
- Each wiki page is fetched exactly once: raw responses are cached persistently (per page title) in `tools/wiki-scraper/cache/`; re-runs read the cache unless `--refresh <page>` is given.
- Strict self-imposed rate limit: 1 request per 5-10 s (6-12/min), exponential backoff on 429/5xx (honouring Retry-After), descriptive User-Agent (tool name + maintainer contact), never a default Python/Java agent. Prefer bulk queries (MediaWiki `action=query` with up to about 20 titles per request, the Bucket API `action=bucket`, the prices `/mapping` endpoint) over per-page fetches.
- The scraper is usable early: fetch + cache + rate limit comes first and the crawl runs in the background while the transform steps (cache -> processed JSON) are written. Transforms read only the cache, so they re-run at no network cost.
- Outputs (`data_version.json` carries `data_version`, `generated_at`, source revisions):
  - `monsters.json`: id, name, hp, defence stats, max hit, attack style, weaknesses.
  - `items.json`: id, name, slot, requirements, acquisition sources (shop with price and location, drop, craft, quest reward), teleport info where the item is a teleport. Equipment bonuses still come from RuneLite `ItemStats` at runtime.
  - `training_methods.json`: per skill, method, level range, location, approximate xp/hr, required items.
  - `transport.json`: teleports and transport nodes (spirit trees, fairy rings, teleport spells/items, charter ships, gliders) with source tile, destination tile, cost in ticks, requirements, and for item teleports the item and charges.
  - `quests.json`: quest definitions (section 5.2) including ordered steps.
  - `locations.json`: representative tile per named target (NPC spawn, object, shop, bank, place) keyed by name and id.
- A local `overrides/` subdirectory of the data dir holds hand-written fixes (`requirement_overrides.json`, `quest_step_overrides.json`, `location_overrides.json`) that win over scraped data. These are local too.
- Players never contact the wiki at runtime; PRIVACY.md is unchanged.

## 5. Requirement model
- `Requirement` = skill levels + quests + items (any-of id sets, quantity) + stats (min max hit, min combat level) + varbit/CA conditions + chunk access.
- Derived at load per `completion_type` from existing catalog fields (level, constraints, required_items, target_npc, quest/varbit fields), merged with overrides and wiki data. Unknown requirements are tagged `UNKNOWN` and surfaced, never silently treated as met.
- One-hit/kill tasks: structured check `playerMaxHit >= npcHp` using `monsters.json`. Tasks with `time_in_ticks` limits also get a "can kill in time" check via a DPS estimate (flagged approximate, later milestone).
- `MaxHitCalculator`: melee first (effective strength level with prayer/style/void/slayer/salve modifiers, equipment strength bonus), then ranged, then magic (spell/powered-staff table from data). Inputs: `PlayerState` levels, equipment bonuses, style/prayer assumptions. Special weapon effects beyond the table are not modelled.
- `PlayerState`: levels, quest completion, inventory, equipment, bank cache, unlocked/simulated chunks.
- Bank: contents cached when the player opens the bank (item container event), stored per RuneScape profile. Unknown bank contents count as "not owned".
- Modes: Real (default) uses the plugin's unlocked chunks and live skills/gear. Simulate lets the user pick chunks and edit levels/items, for planning from a brand-new account.

### 5.1 Graceful degradation without data
- No `gps-data` dir or missing file: the loader returns empty/partial `GpsData` and a `GpsDataStatus` listing what is missing. The GPS tab shows the status and the scraper command to run; it never throws.
- Without `monsters.json`: one-hit/kill checks are `UNKNOWN` (surfaced, not treated as met).
- Without `locations.json`: targets fall back to the centre tile of their chunk (`x = regionX*64+32, y = regionY*64+32`) and steps are flagged `approximate`.
- Without `transport.json`: walking only. Without `quests.json`: quests appear only as the existing catalog quest tasks (atomic, location = chunk centre), with requirements from `quest_requirements.json`.

### 5.2 Quests are first-class
Quests award task points and large XP and are often the most efficient use of time, so they are modelled in full rather than as an opaque task.
- `QuestDef`: id, name, requirements (skills, prerequisite quests, quest points, combat level, items needed overall), `steps` (ordered `QuestStep` chain), `rewards` (xp per skill, quest points, task points, lamp-style choice rewards, unlocks such as teleports/areas/items).
- `QuestStep`: index, text, location tile (or `approximate` fallback), step requirements (items to have, skills for that step), items it consumes or produces, `kind` (talk/kill/use/travel/other).
- Quest steps become precedence-constrained nodes in the planner's routing problem: step N+1 requires step N; the final step also applies the quest rewards. They are NOT one atomic block, so the solver may do step 2 of a quest, then other nearby tasks, then step 3.
- Quest XP rewards feed back into requirement satisfaction: the planner tracks a projected `PlayerState`; a node needing a skill level the quest reward provides depends on that quest's completion node. Quest-granted unlocks (teleports, areas, items) add transport edges or ownership to the projection.
- Source of step data: the scraper reads each quest's wiki quick guide (`<Quest>/Quick guide`) plus the quest infobox/rewards, and cross-checks requirements with the existing `quest_requirements.json`, which is also the fallback when a quick guide fails to parse.
- Step location resolution, in order: (1) explicit coordinates/map templates in the guide text; (2) a named NPC/object/place link in the step resolved through `locations.json`; (3) `quest_step_overrides.json`; (4) carry forward the previous step's location, flagged `approximate`; (5) centre of the nearest chunk in the quest's `chunks` list from `quest_requirements.json`, flagged `approximate`. Unresolved steps are counted in the scraper validation report so the maintainer can add overrides.

## 6. Travel-cost model
No travel model exists today, so one is defined here. Costs are in game ticks (0.6 s). This is an approximation and the approximations are explicit.
- Each target is reduced to one representative world tile (task target, quest step, training spot, shop, bank). No collision-map pathfinding: this tile-distance plus chunk-detour estimate is the permanent travel model, and no later milestone will add real pathfinding.
- Walking cost between two tiles in the same chunk = diagonal-aware tile distance times a run factor (about 0.5 tick/tile). Between chunks, cost is the sum over the shortest chunk path via `neighbor_ids`, with a detour penalty factor for water, walls and gates. Chunks the player cannot access are impassable for walking.
- Transport edges from `transport.json` have a fixed tick cost (including cast/animation/wait) and requirements. An edge is usable only if the player state satisfies it (level, quest, runes/items, access to the endpoints' chunks). Charter links come from `NuzlockeChunk.TravelConnection` with an estimated sailing time.
- Cost matrix: shortest paths (Dijkstra) over walking plus usable transport edges, on the reduced node set (nodes, banks, transport nodes). Consumable teleports are available if owned (inventory or known bank), or if the plan contains an acquisition step for them (section 7); they carry a small constant penalty, not a coin cost.
- Not modelled: run energy, agility shortcuts not in transport data, random events, combat/skilling duration (shown separately as information), banking trips beyond a constant, click latency.

## 7. Planner / solver
- Nodes: tasks, quest steps, training blocks, gear acquisitions and teleport acquisitions, each with a location, informational duration and requirement set. Edge cost: section 6 travel cost.
- Objective: minimise total travel time of a visiting order over the selected nodes, subject to precedence constraints (get item before use, train to level before the dependent task, quest step N before N+1, unlock chunk before entering). Precedence edges are derived from the requirement graph against a projected `PlayerState` (XP/level changes from training blocks and quest rewards, items obtained, unlocks).
- Quests interleave: quest steps are ordinary precedence-constrained nodes (a chain), so other tasks near a step's location may be done between consecutive steps. A quest is selected as a whole (all its steps and any prerequisite quests) when its rewards (task points, XP that unlocks other selected nodes) justify the travel.
- Teleport acquisition: unowned teleport items (or the runes for a teleport spell) may be bought or made as explicit plan steps (`ACQUIRE_TELEPORT` at the shop/craft location) when the estimated travel saved across the plan exceeds the acquisition detour by a threshold; the acquisition node precedes the first edge that uses it.
- Step selection by goal mode (default: maximise task points / unlock the next chunk; alternatives: train a chosen skill, maximise gear). Gear, training, quest and acquisition steps are inserted only when they unlock or reward something in the selected set.
- Algorithm (TSP-like with precedence): nearest-neighbour construction restricted to precedence-ready nodes, then 2-opt/or-opt local improvement that rejects moves violating precedence. Open path from the current position, deterministic with a seed, time-bounded (target under 1 s for about 500 nodes) and run off the client thread. Heuristic, no optimality guarantee; exact solving is out of scope.
- Re-planning after each completed step or state change (level-up, item obtained), starting from the current position.
- Output: `PlanStep{type, target, location, travelTicks, reason, missingRequirements, approximate, questId?, stepIndex?}` in order.

## 8. UI
- New "GPS" tab in the side panel: Real/Simulate toggle, goal selector, ordered step list with reason and missing requirements per step, estimated total travel time, data version footer (or the data-missing status). Simulate mode exposes a chunk picker and level editor.
- If cheap: highlight the current step's chunk on the world map overlay.

## 9. Milestones (each ships something usable; one plan file each in docs/superpowers/plans/)
1. M1 Local data pipeline: scraper (fetch, persistent cache, rate limit, User-Agent) first so the crawl can start early; then transforms to the processed JSON (items, monsters, quests with steps, transport, locations, training), validation, and the Java loader with graceful degradation.
2. M2 Requirement model + eligibility + max hit: `PlayerState`, derived requirements, overrides, `MaxHitCalculator`, quest definitions in the requirement graph, panel list of doable/blocked tasks (including one-hit tasks), basic simulate mode.
3. M3 Travel model: representative locations (including quest step locations), chunk-graph walking cost, transport edges, charter links, ownable/acquirable teleports, cost matrix.
4. M4 Planner: precedence-aware nearest-neighbour + 2-opt, quest chains with interleaving and reward feedback, teleport acquisition, goal modes, re-planning, determinism tests.
5. M5 UI and polish: ordered plan display (quest steps grouped but interleaved), simulate chunk picker and level editor, bank cache, world-map highlight, docs.

## 10. Testing
Pure-Java unit tests per class using synthetic fixtures: max hit against known values, requirement derivation on real catalog samples (the catalog is in the repo), travel matrix on a small fixture graph, planner precedence, quest interleaving and determinism. A catalog-wide test asserts every task derives a requirement or an explicit `UNKNOWN`. Python scraper tests live beside the (excluded) script.

## 11. Decisions
Binding (from the user, 2026-10-14):
- Personal use only; nothing wiki-derived is bundled or published; script, cache and output are never committed; local exclusion via `.git/info/exclude`.
- Tile-distance + chunk-detour travel estimate is permanent; no collision pathfinding milestone.
- Plans may buy/make teleport items the player does not own as explicit steps when that saves enough travel.
- Quests are first-class: ordered step chains, interleavable, rewards feed requirement satisfaction.

Made on the user's behalf (may be overruled):
- Output is an ordered plan list in a new side-panel tab with a reason or missing requirements per step; world-map highlight only if cheap.
- Requirements are derived at load from existing catalog fields plus local overrides plus local wiki-derived data; no server catalog schema or Go server change.
- New code lives in `com.chunkblazer.gps`, pure Java and client-independent, with JUnit tests; `ChunkBlazerPlugin.java` gets minimal wiring only.
- Bank contents are cached when the player opens the bank; unknown bank contents count as "not owned".
- Scraper language is Python 3.12 stdlib-only; processed output goes directly to the RuneLite data dir.

## 12. Remaining open questions
None blocking.
