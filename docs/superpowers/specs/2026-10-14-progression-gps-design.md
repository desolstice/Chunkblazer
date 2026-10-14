# Progression GPS: Design Spec

Date: 2026-10-14 | Branch: feature/progression-gps | Status: draft for review

## 1. Goal
Given the chunks a player can access and their current (or simulated) skills and gear, produce an ordered multi-step plan that progresses the account as fast as possible, where "fast" means minimal total travel time. The plan covers gear and upgrades, skill training, and ChunkBlazer tasks (including one-hit/kill tasks judged by max hit vs NPC HP). Each step carries a reason or its missing requirements.

Non-goals: changing the server catalog schema or the Go server; runtime wiki access by players; tile-level collision pathfinding; changes to PRIVACY.md.

## 2. Existing systems (relevant)
- Chunks: `NuzlockeChunk.java` (region_id, neighbor_ids, charter/boss flags, `TravelConnection` charter routes). Unlocked set: `ChunkBlazerPlugin.getUnlockedRegionIds()`.
- Catalog: `src/main/resources/com/chunkblazer/tasks_catalog.json.gz` (about 4,970 tasks), refreshed via `api/CatalogStore.java`. Task model: `NuzlockeTask.java`, `RequiredItem.java`, `TargetNpc.java`, `TaskConstraints.java` (time_in_ticks, required_skill/level, quest, varbits, equipment rules).
- Quest requirements: `QuestRequirements.java` + `quest_requirements.json` (chunks, prerequisite quests, skill levels).
- Player state: `client.getRealSkillLevel`, `InventoryID.WORN`/`INV`, `itemManager.getItemStats` (see `modules/EquipModule`, `RaidChallengeModule`). No bank reading today.
- No routing, travel-cost, NPC HP/defence, or training-method data exists today.
- UI: `ChunkBlazerPanel.java` side panel; `ChunkBlazerWorldMapOverlay.java`.

## 3. Architecture
- New package `com.chunkblazer.gps`: pure Java, no `Client`/RuneLite-client dependencies in logic classes, fully JUnit-testable. A thin adapter (`PlayerStateProvider`) maps live client state to a `PlayerState` value object; a simulate provider builds one from user edits.
- `ChunkBlazerPlugin.java` receives only minimal wiring (create adapter, register panel tab, bank cache event hook).
- Data flow: bundled JSON (wiki-derived + overrides) + catalog -> `RequirementModel` -> `EligibilityEngine` -> `Planner` (with `TravelCostModel`) -> `PlanStep` list -> panel tab.

## 4. Data pipeline (offline, maintainer-run)
- Script in `tools/wiki-data/` (not shipped in the plugin jar). Outputs go to `src/main/resources/com/chunkblazer/gps/`.
- Each wiki page is fetched exactly once: raw responses are cached in a git-ignored `tools/wiki-data/cache/`; re-runs read the cache unless `--refresh <page>` is given.
- Strict self-imposed rate limit: 1 request per 5-10 s (6-12/min), exponential backoff on 429/5xx, descriptive User-Agent (tool name + maintainer contact), never a default Java/Python agent. Prefer bulk queries (MediaWiki `action=query` with multiple titles, `/mapping`-style endpoints, Bucket/Semantic queries) over per-page fetches.
- Outputs (versioned, with `data_version` and source revisions):
  - `monsters.json`: id, name, hp, defence stats, max hit.
  - `items.json`: acquisition sources (shop, drop, craft, quest reward), slot and requirements. Equipment bonuses still come from RuneLite `ItemStats` at runtime where available.
  - `training_methods.json`: per skill, method, level range, location, approximate xp/hr, required items.
  - `transport.json`: teleports and transport nodes (spirit trees, fairy rings, teleport spells/items, charter ships, gliders) with source tile, destination tile, cost in ticks, and requirements.
  - `locations.json`: representative tile per task target (NPC spawn, object, shop) keyed by npc id/object id/chunk.
- Players never contact the wiki; PRIVACY.md is unchanged.
- A shipped `requirement_overrides.json` fixes or supplies requirements the derivation cannot infer.

## 5. Requirement model
- `Requirement` = skill levels + quests + items (any-of id sets, quantity) + stats (min max hit, min combat level) + varbit/CA conditions + chunk access.
- Derived at load per `completion_type` from existing catalog fields (level, constraints, required_items, target_npc, quest/varbit fields), merged with overrides and wiki data. Unknown requirements are tagged `UNKNOWN` and surfaced, never silently treated as met.
- One-hit/kill tasks: structured check `playerMaxHit >= npcHp` using `monsters.json`. Tasks with `time_in_ticks` limits also get a "can kill in time" check via a DPS estimate (flagged approximate, later milestone).
- `MaxHitCalculator`: melee first (effective strength level with prayer/style/void/slayer/salve modifiers, equipment strength bonus), then ranged, then magic (spell/powered-staff table from wiki data). Inputs: `PlayerState` levels, equipment bonuses, style/prayer assumptions. Special weapon effects beyond the table are not modelled.
- `PlayerState`: levels, quest completion, inventory, equipment, bank cache, unlocked/simulated chunks.
- Bank: contents cached when the player opens the bank (item container event), stored per RuneScape profile. Unknown bank contents count as "not owned".
- Modes: Real (default) uses the plugin's unlocked chunks and live skills/gear. Simulate lets the user pick chunks and edit levels/items, for planning from a brand-new account.

## 6. Travel-cost model
No travel model exists today, so one is defined here. Costs are in game ticks (0.6 s). This is an approximation and the approximations are explicit.
- Each target is reduced to one representative world tile (task target, training spot, shop, bank). No collision-map pathfinding.
- Walking cost between two tiles in the same chunk = diagonal-aware tile distance times a run factor (about 0.5 tick/tile). Between chunks, cost is the sum over the shortest chunk path via `neighbor_ids`, with a detour penalty factor for water, walls and gates. Chunks the player cannot access are impassable for walking.
- Transport edges from `transport.json` have a fixed tick cost (including cast/animation/wait) and requirements. An edge is usable only if the player state satisfies it (level, quest, runes/items, access to the endpoints' chunks). Charter links come from `NuzlockeChunk.TravelConnection` with an estimated sailing time.
- Cost matrix: shortest paths (Dijkstra) over walking plus usable transport edges, on the reduced node set (task locations, banks, transport nodes). Consumable teleports are available only if owned (inventory or known bank) and carry a small constant penalty, not a coin cost.
- Not modelled: run energy, agility shortcuts not in transport data, random events, combat/skilling duration (shown separately as information), banking trips beyond a constant, click latency.

## 7. Planner / solver
- Nodes: tasks, training blocks and gear acquisitions, each with a location, informational duration and requirement set. Edge cost: section 6 travel cost.
- Objective: minimise total travel time of a visiting order over the selected steps, subject to precedence constraints (get item before use, train to level before the dependent task, unlock chunk before entering).
- Step selection by goal mode (default: maximise task points / unlock the next chunk; alternatives: train a chosen skill, maximise gear). Gear and training steps are inserted only when they unlock tasks in the selected set, derived from the requirement graph.
- Algorithm (TSP-like with precedence): nearest-neighbour construction restricted to precedence-ready nodes, then 2-opt/or-opt local improvement that rejects moves violating precedence. Open path from the current position (not a cycle), deterministic with a seed, time-bounded (target under 1 s for about 500 nodes) and run off the client thread. This is a heuristic with no optimality guarantee; exact solving is out of scope.
- Re-planning after each completed step or state change (level-up, item obtained), starting from the current position.
- Output: `PlanStep{type, target, location, travelTicks, reason, missingRequirements, approximate}` in order.

## 8. UI
- New "GPS" tab in the side panel: Real/Simulate toggle, goal selector, ordered step list with reason and missing requirements per step, estimated total travel time, data version footer. Simulate mode exposes a chunk picker and level editor.
- If cheap: highlight the current step's chunk on the world map overlay.

## 9. Milestones (each ships something usable)
1. M1 Data pipeline: offline wiki fetch script (cache, rate limit, User-Agent), bundled JSON schemas, loaders and validation tests.
2. M2 Requirement model + eligibility + max hit: `PlayerState`, derived requirements, overrides, `MaxHitCalculator`, panel list of doable/blocked tasks (including one-hit tasks), basic simulate mode.
3. M3 Travel model: representative locations, chunk-graph walking cost, transport edges, charter links, cost matrix.
4. M4 Planner: precedence-aware nearest-neighbour + 2-opt, goal modes, re-planning, determinism tests.
5. M5 UI and polish: ordered plan display, simulate chunk picker and level editor, bank cache, world-map highlight, docs.

## 10. Testing
Pure-Java unit tests per class: max hit against known wiki values, requirement derivation on real catalog samples, travel matrix on a small fixture graph, planner precedence and determinism. A catalog-wide test asserts every task derives a requirement or an explicit `UNKNOWN`.

## 11. Open to override (decisions made on the user's behalf)
- Output is an ordered plan list in a new side-panel tab with a reason or missing requirements per step; world-map highlight of the current step only if cheap.
- Requirements are derived at load from existing catalog fields plus a shipped overrides file plus wiki-derived monster/item/transport data; no server catalog schema or Go server change.
- New code lives in `com.chunkblazer.gps`, pure Java and client-independent, with JUnit tests; `ChunkBlazerPlugin.java` gets minimal wiring only.
- Bank contents are cached when the player opens the bank; unknown bank contents count as "not owned".

## 12. Remaining open questions
- Wiki content is CC BY-NC-SA: is bundling derived data acceptable, with attribution in the README?
- Is a tile-distance estimate with chunk detours accurate enough, or should real collision-map pathfinding be a later milestone?
- Should unowned but purchasable teleport items be allowed in plans?
