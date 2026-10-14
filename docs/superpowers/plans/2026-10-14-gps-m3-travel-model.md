# Progression GPS M3: Travel Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Estimate travel time in game ticks between any two world tiles for a given `PlayerState` (walking over unlocked chunks plus usable transport, including teleports the plan could buy or make), and produce a cost matrix over a node set for the planner.

**Architecture:** Pure-Java package `com.chunkblazer.gps.travel`. Locations are reduced to one tile each (`LocationResolver`). Walking = diagonal-aware tile distance over a region graph of unlocked chunks with a detour factor. Transport edges (data + charter links) are filtered by requirements and ownership. The matrix uses a hub decomposition (walk + all-pairs shortest paths over transport endpoints) so 500 nodes stay fast. No collision-map pathfinding, ever.

**Tech Stack:** Java 11, JUnit 5, Lombok (existing). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 5.1, 6, 7 teleport acquisition). Depends on M1 (`GpsData`, `TransportEdge`, `LocationEntry`, `Tile`) and M2 (`PlayerState`, `Requirement`, `QuestCatalog`, `QuestModel`).

## Global Constraints

- Costs are integer game ticks (0.6 s); walking = `0.5 tick/tile` (run) with detour factor `1.25` between chunks; all constants live in one class `TravelConfig`.
- Chunks the player cannot access are impassable for walking. A region is a 64x64 tile square, `regionId = (x>>6)<<8 | (y>>6)`.
- This tile-distance + chunk-detour estimate is the permanent model; do not add collision pathfinding.
- Consumable teleports are usable if owned or acquirable in-plan; they carry a small constant penalty (`CONSUMABLE_PENALTY_TICKS`), not a coin cost.
- Logic classes: no RuneLite client dependency. Missing data (no `locations.json`, no `transport.json`) must degrade to chunk-centre locations and walking only.
- Java release 11, tabs, braces on own lines, license header as in `QuestRequirements.java`.

## Review Focus

- A node in a chunk the player has not unlocked: cost to or from it must be `INF`, never a huge-but-finite number that a solver could pick. Task 2/6 tests.
- Chunk with several region ids (a ChunkBlazer chunk can span regions): walking between its regions must be allowed and cheap, and neighbour links given on any region must connect the whole chunk. Task 2 test.
- Transport edge whose endpoint chunk is locked, or whose item/quest/skill requirement is unmet: must be excluded, and a purchasable teleport must show up as ACQUIRABLE with its shop, not silently as available. Task 5 test.
- Two nodes on the same tile and a node equal to the start: cost 0, no division by zero or negative values. Task 1/6 tests.
- Unresolvable task location (no data at all): falls back to its home chunk centre flagged approximate, never null or (0,0). Task 4 test.

## File Structure

New under `src/main/java/com/chunkblazer/gps/travel/` (tests mirror under `src/test/java/com/chunkblazer/gps/travel/`):
- `TravelConfig.java` — constants
- `TileMath.java` — region id/coords, diagonal-aware distance
- `ChunkGraph.java`, `ChunkNode.java`, `ChunkNodes.java` (adapter from `NuzlockeChunk`)
- `WalkModel.java` — tile-to-tile walking ticks
- `LocationResolver.java`, `ResolvedLocation.java`
- `TransportModel.java`, `UsableEdge.java`, `Acquisition.java`
- `TravelNode.java`, `CostMatrix.java`, `TravelContext.java`

## Interfaces defined here and used by M4/M5 (do not rename)

```java
TravelConfig.INF == Integer.MAX_VALUE / 4;  WALK_TICKS_PER_TILE = 0.5;  CHUNK_DETOUR = 1.25;
  CONSUMABLE_PENALTY_TICKS = 6;  CHARTER_BASE_TICKS = 60;  CHARTER_TICKS_PER_TILE = 0.1;  NETWORK_KINDS = {FAIRY_RING, SPIRIT_TREE, GLIDER}
TileMath.regionId(int x, int y) -> int;  regionX(int rid) -> int;  regionY(int rid) -> int;  centre(int rid) -> Tile;
  tileDistance(Tile a, Tile b) -> int   // Chebyshev, plane ignored
ChunkNode { List<Integer> regionIds; List<Integer> neighborRegionIds; boolean charter; List<Charter> charters; }  Charter { int destinationRegionId; String name; }
ChunkNodes.from(Collection<NuzlockeChunk>) -> List<ChunkNode>
ChunkGraph.build(List<ChunkNode>) -> ChunkGraph;  ChunkGraph.accessible(Set<Integer> unlockedRegions) -> ChunkGraph.View
  View: boolean isAccessible(int rid); int walkTicks(Tile a, Tile b)  // INF if no path
WalkModel(ChunkGraph.View).ticks(Tile a, Tile b) -> int
ResolvedLocation { Tile tile; boolean approximate; String source; }
LocationResolver(GpsData data, QuestCatalog quests, Map<Integer,Integer> taskHomeRegion?)  // see Task 4
  ResolvedLocation forTask(NuzlockeTask t, int homeRegionId);  ResolvedLocation forQuestStep(String questId, int stepIndex);
  ResolvedLocation forName(String name, int fallbackRegionId);  Optional<ResolvedLocation> nearestBank(Tile from)
UsableEdge { TransportEdge edge; Availability availability; Acquisition acquisition; int ticks; }  Availability = AVAILABLE | ACQUIRABLE
Acquisition { int itemId; String itemName; String shop; Tile shopTile; int price; }   // shopTile may be null (unknown)
TransportModel(GpsData data, List<ChunkNode> chunks, QuestCatalog quests)
  List<UsableEdge> usable(PlayerState s, Set<String> acquiredEdgeIds, boolean includeAcquirable)
TravelNode { String id; Tile tile; }
TravelContext { PlayerState state; Set<Integer> accessibleRegions; Set<String> acquiredEdgeIds; boolean includeAcquirable; }
CostMatrix.build(List<TravelNode> nodes, TravelContext ctx, ChunkGraph graph, TransportModel transport) -> CostMatrix
  int cost(int i, int j);  int size();  List<String> route(int i, int j)  // edge ids used, empty if walking
```

---

### Task 1: `TravelConfig` and `TileMath`

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/travel/TravelConfig.java`, `TileMath.java`
- Test: `src/test/java/com/chunkblazer/gps/travel/TileMathTest.java`

**Interfaces:** Produces `TravelConfig` constants and `TileMath` as listed. `centre(rid)` = `(regionX*64+32, regionY*64+32, plane 0)`. `tileDistance` = `max(|dx|,|dy|)` (diagonal moves cost one step).

- [ ] **Step 1: Write the failing tests**

```java
class TileMathTest
{
	@Test
	void regionIdRoundTrip()
	{
		assertEquals(12850, TileMath.regionId(50 * 64 + 5, 50 * 64 + 63));
		assertEquals(50, TileMath.regionX(12850));
		assertEquals(50, TileMath.regionY(12850));
	}

	@Test
	void knownRegion()
	{
		// Lumbridge spawn (3222, 3218) is region 12850
		assertEquals(12850, TileMath.regionId(3222, 3218));
	}

	@Test
	void centreOfRegion()
	{
		Tile c = TileMath.centre(12850);
		assertEquals(50 * 64 + 32, (int) c.getX());
		assertEquals(50 * 64 + 32, (int) c.getY());
		assertEquals(0, c.getPlane());
	}

	@Test
	void distanceIsDiagonalAware()
	{
		assertEquals(10, TileMath.tileDistance(tile(0, 0), tile(10, 10)));
		assertEquals(10, TileMath.tileDistance(tile(0, 0), tile(10, 3)));
		assertEquals(0, TileMath.tileDistance(tile(5, 5), tile(5, 5)));
	}

	private static Tile tile(int x, int y)
	{
		return new Tile(x, y, 0, false, null);
	}
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.travel.TileMathTest` — Expected: compile errors.
- [ ] **Step 3: Implement**

```java
public final class TileMath
{
	private TileMath() {}

	public static int regionId(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	public static int regionX(int rid) { return rid >> 8; }

	public static int regionY(int rid) { return rid & 0xFF; }

	public static Tile centre(int rid)
	{
		return new Tile(regionX(rid) * 64 + 32, regionY(rid) * 64 + 32, 0, false, null);
	}

	public static int tileDistance(Tile a, Tile b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}
}
```

`TravelConfig` is a final class of `public static final` constants exactly as in the interface block (plus `public static final Set<String> NETWORK_KINDS`).

- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/travel src/test/java/com/chunkblazer/gps/travel
git commit -m "feat(gps): TravelConfig and TileMath

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 2: `ChunkGraph` (region graph with access filter)

**Files:**
- Create: `ChunkNode.java`, `ChunkNodes.java`, `ChunkGraph.java` (in `gps/travel/`)
- Test: `src/test/java/com/chunkblazer/gps/travel/ChunkGraphTest.java`

**Interfaces:**
- Consumes: `NuzlockeChunk` getters (it is a Lombok `@Data` class: `getRegionIds()`, `getNeighborIds()`, `getChunkType()` or `isCharter()`, `getTravelConnections()` -> `TravelConnection{getDestinationId(), getTravelName()}`; verify the exact names by opening the class).
- Produces: `ChunkNode`, `ChunkNodes.from`, `ChunkGraph.build/accessible`, `ChunkGraph.View.isAccessible/walkTicks`. Graph vertices are region ids. Edges: (a) every pair of regions inside one chunk is connected (they are one chunk); (b) each region links to each of its chunk's `neighborRegionIds` (undirected). Edge weight = `tileDistance(centre(a), centre(b))`. `View.walkTicks(a, b)`: if either tile's region is not accessible (or not in the graph) -> `INF`; same region -> `ceil(dist * 0.5)`; otherwise shortest region path P via Dijkstra among accessible regions, cost = `ceil((dist(a, centre(P1)) + sum dist(centre(Pi), centre(Pi+1)) + dist(centre(Pn), b)) * 0.5 * CHUNK_DETOUR)`; no path -> `INF`.
- Regions not in any chunk but accessible (e.g. region of an unlocked chunk that is missing from the graph) are treated as isolated singletons: walking inside them works, walking out does not.

- [ ] **Step 1: Write the failing tests** using a hand-built fixture (regions on a line: A=12850 (50,50), B=12851 (50,51), C=12852 (50,52), plus a two-region chunk D1=13106 (51,50) / D2=13362 (52,50) and an island E=20000):

```java
class ChunkGraphTest
{
	private static ChunkNode node(List<Integer> regions, List<Integer> neighbors)
	{
		return new ChunkNode(regions, neighbors, false, Collections.emptyList());
	}

	private ChunkGraph graph()
	{
		return ChunkGraph.build(Arrays.asList(
			node(Arrays.asList(12850), Arrays.asList(12851, 13106)),
			node(Arrays.asList(12851), Arrays.asList(12850, 12852)),
			node(Arrays.asList(12852), Arrays.asList(12851)),
			node(Arrays.asList(13106, 13362), Arrays.asList(12850)),
			node(Arrays.asList(20000), Collections.emptyList())));
	}

	private static Tile at(int rid)
	{
		return TileMath.centre(rid);
	}

	@Test
	void sameRegionWalkIsHalfTickPerTile()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850)));
		Tile a = new Tile(3200, 3200, 0, false, null);
		Tile b = new Tile(3220, 3205, 0, false, null);
		assertEquals(10, v.walkTicks(a, b)); // 20 tiles * 0.5
		assertEquals(0, v.walkTicks(a, a));
	}

	@Test
	void adjacentChunksWalkWithDetour()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 12851)));
		// centres are 64 tiles apart: 64 * 0.5 * 1.25 = 40
		assertEquals(40, v.walkTicks(at(12850), at(12851)));
	}

	@Test
	void lockedChunkIsInfinite()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(12851)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12851), at(12850)));
	}

	@Test
	void pathMustGoThroughUnlockedChunksOnly()
	{
		// C is only reachable through B; with B locked there is no path
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 12852)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(12852)));
		ChunkGraph.View all = graph().accessible(new HashSet<>(Arrays.asList(12850, 12851, 12852)));
		assertEquals(80, all.walkTicks(at(12850), at(12852))); // 128 tiles * 0.5 * 1.25
	}

	@Test
	void regionsOfOneChunkConnectAndNeighbourOnAnyRegionConnectsWholeChunk()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 13106, 13362)));
		assertTrue(v.walkTicks(at(13106), at(13362)) < TravelConfig.INF);
		assertTrue(v.walkTicks(at(12850), at(13362)) < TravelConfig.INF); // via the chunk's neighbour link on 13106
	}

	@Test
	void unknownRegionAndIslandHandled()
	{
		ChunkGraph.View v = graph().accessible(new HashSet<>(Arrays.asList(12850, 20000, 99999)));
		assertEquals(TravelConfig.INF, v.walkTicks(at(12850), at(20000)));
		assertEquals(2, v.walkTicks(new Tile(78 * 64 + 1, 32 * 64 + 1, 0, false, null), new Tile(78 * 64 + 4, 32 * 64 + 1, 0, false, null)));
		assertTrue(v.isAccessible(99999));
	}
}
```

(Region 20000 = 0x4E20 is regionX 78, regionY 32, so the real test uses tiles `(78*64+1, 32*64+1)` and `(78*64+4, 32*64+1)`; expected cost 2.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement.** `ChunkGraph.build`: maintain `Map<Integer, Set<Integer>> adj`; for each node link all its regions pairwise, and every region to every neighbor id (both directions). `accessible(Set<Integer>)` returns a `View` that Dijkstra-searches (binary heap `PriorityQueue<long[]>` or small class) restricted to accessible regions; results cached per (fromRegion,toRegion) region pair in a `HashMap<Long,Integer>` of region-path distances in tiles (the tile-to-first-centre and last-centre-to-tile legs are added per call). Use `Math.ceil` once on the final value. `ChunkNodes.from` reads the `NuzlockeChunk` getters and builds `ChunkNode`s (charter list from `getTravelConnections()`, empty when null).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/travel src/test/java/com/chunkblazer/gps/travel
git commit -m "feat(gps): ChunkGraph with accessible-region walking costs

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 3: `WalkModel`

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/travel/WalkModel.java`
- Test: `src/test/java/com/chunkblazer/gps/travel/WalkModelTest.java`

**Interfaces:** `WalkModel(ChunkGraph.View view)`; `int ticks(Tile a, Tile b)` delegates to `view.walkTicks`; additionally treats a tile whose coordinates are unknown (`!isKnown()`) as `INF` and plane differences as walkable (planes are ignored; stairs cost is folded into the constant `PLANE_CHANGE_TICKS = 8` added once per plane difference). Null tile -> `INF`.

- [ ] **Step 1: Write the failing tests**

```java
class WalkModelTest
{
	private WalkModel model()
	{
		ChunkGraph g = ChunkGraph.build(Arrays.asList(new ChunkNode(Arrays.asList(12850), Collections.emptyList(), false, Collections.emptyList())));
		return new WalkModel(g.accessible(new HashSet<>(Arrays.asList(12850))));
	}

	@Test
	void planeChangeAddsConstant()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		Tile up = new Tile(3200, 3200, 1, false, null);
		assertEquals(TravelConfig.PLANE_CHANGE_TICKS, model().ticks(a, up));
	}

	@Test
	void unknownOrNullTileIsInfinite()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		assertEquals(TravelConfig.INF, model().ticks(a, new Tile(null, null, 0, true, "NONE")));
		assertEquals(TravelConfig.INF, model().ticks(null, a));
	}

	@Test
	void sameTileIsZero()
	{
		Tile a = new Tile(3200, 3200, 0, false, null);
		assertEquals(0, model().ticks(a, a));
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** (add `PLANE_CHANGE_TICKS = 8` to `TravelConfig`):

```java
public int ticks(Tile a, Tile b)
{
	if (a == null || b == null || !a.isKnown() || !b.isKnown())
	{
		return TravelConfig.INF;
	}
	int walk = view.walkTicks(a, b);
	if (walk >= TravelConfig.INF)
	{
		return TravelConfig.INF;
	}
	return walk + Math.abs(a.getPlane() - b.getPlane()) * TravelConfig.PLANE_CHANGE_TICKS;
}
```

- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/travel src/test/java/com/chunkblazer/gps/travel
git commit -m "feat(gps): WalkModel with plane-change cost

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 4: `LocationResolver` (tasks, quest steps, names, banks)

**Files:**
- Create: `ResolvedLocation.java`, `LocationResolver.java`
- Test: `src/test/java/com/chunkblazer/gps/travel/LocationResolverTest.java`

**Interfaces:**
- Consumes: `GpsData.getLocations()` (`LocationEntry{name, kind, ids, x, y, plane}`), `QuestCatalog.get(id)` (`QuestModel.getSteps()` with `QuestStep.getLocation()` and `getChunks()`), `NuzlockeTask.getTargetNpc()` (`getName()`, `getNpcIds()`), `TileMath.centre`.
- Produces: `ResolvedLocation` (`@Value`: `Tile tile; boolean approximate; String source`), `LocationResolver(GpsData data, QuestCatalog quests)` with:
  - `forTask(NuzlockeTask t, int homeRegionId)`: (1) a `LocationEntry` whose `ids` intersects `target_npc.npc_ids` (source `NPC_ID`); (2) an entry whose name equals the target NPC name with any `#Variant` suffix stripped (case-insensitive, source `NAME`); (3) for `QUEST_CHECK` tasks: the location of the quest's FIRST step (source `QUEST_START`; the full step chain is routed by M4, this is only the representative tile when a quest is treated atomically); (4) the centre of `homeRegionId` (`approximate=true`, source `CHUNK_CENTRE`).
  - `forQuestStep(String questId, int stepIndex)`: the step's `location` when `isKnown()` (approximate flag copied from `Tile.isApproximate()`), else the centre of the quest's first chunk in `QuestModel.getChunks()` (approximate), else `Optional`-style failure: returns a `ResolvedLocation` with tile `null` and source `NONE` (callers treat as unroutable).
  - `forName(String name, int fallbackRegionId)`: entry by exact then case-insensitive name, else chunk centre approximate.
  - `nearestBank(Tile from)`: entries of kind `BANK` minimising `tileDistance`; empty when none.
  - Accessing multiple matching entries: choose the closest to `homeRegionId`'s centre (stable tie-break by name) so results are deterministic.

- [ ] **Step 1: Write the failing tests**

```java
class LocationResolverTest
{
	private GpsData dataWith(String locationsJson)
	{
		// builds GpsData via GpsDataStore from a temp dir containing locations.json with the given entries
		return GpsTestFixtures.dataWithLocations(locationsJson);
	}

	private static NuzlockeTask task(String json) { return new Gson().fromJson(json, NuzlockeTask.class); }

	@Test
	void npcIdMatchWins()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Cow\",\"kind\":\"NPC\",\"ids\":[2790],\"x\":3253,\"y\":3270,\"plane\":0}]}");
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		ResolvedLocation l = r.forTask(task("{\"name\":\"Kill cow\",\"taskID\":\"k\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"Cow\"],\"npc_ids\":[2790],\"quantity\":[1]}}"), 12850);
		assertEquals("NPC_ID", l.getSource());
		assertFalse(l.isApproximate());
		assertEquals(3253, (int) l.getTile().getX());
	}

	@Test
	void nameMatchStripsVariantSuffix()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Desert Lizard\",\"kind\":\"NPC\",\"ids\":[],\"x\":3400,\"y\":3000,\"plane\":0}]}");
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		ResolvedLocation l = r.forTask(task("{\"name\":\"Lizard\",\"taskID\":\"l\",\"completion_type\":\"NPC_KILL\",\"target_npc\":{\"npc\":[\"Desert Lizard#Yellow\"],\"npc_ids\":[460],\"quantity\":[1]}}"), 12850);
		assertEquals("NAME", l.getSource());
	}

	@Test
	void noDataFallsBackToChunkCentreApproximate()
	{
		GpsData d = GpsData.empty(null);
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		ResolvedLocation l = r.forTask(task("{\"name\":\"x\",\"taskID\":\"x\",\"completion_type\":\"COOKING\"}"), 12850);
		assertTrue(l.isApproximate());
		assertEquals("CHUNK_CENTRE", l.getSource());
		assertEquals(TileMath.centre(12850), l.getTile());
	}

	@Test
	void questStepUsesKnownLocationElseQuestChunk()
	{
		GpsData d = GpsDataStore.load(GpsTestFixtures.dir("full")); // TEST_QUEST: step 0 known, step 1 unknown
		Map<String, QuestHelperEntry> h = new HashMap<>();
		h.put("TEST_QUEST", new QuestHelperEntry(Arrays.asList(12850), null, null));
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, h));
		assertEquals(3208, (int) r.forQuestStep("TEST_QUEST", 0).getTile().getX());
		ResolvedLocation s1 = r.forQuestStep("TEST_QUEST", 1);
		assertTrue(s1.isApproximate());
		assertEquals(TileMath.centre(12850), s1.getTile());
		assertNull(r.forQuestStep("NOPE", 0).getTile());
		assertEquals("NONE", r.forQuestStep("NOPE", 0).getSource());
	}

	@Test
	void nearestBankPicksClosest()
	{
		GpsData d = dataWith("{\"schema\":1,\"entries\":[{\"name\":\"Far Bank\",\"kind\":\"BANK\",\"ids\":[],\"x\":3000,\"y\":3000,\"plane\":0},{\"name\":\"Near Bank\",\"kind\":\"BANK\",\"ids\":[],\"x\":3205,\"y\":3205,\"plane\":0}]}");
		LocationResolver r = new LocationResolver(d, QuestCatalog.build(d, Collections.emptyMap()));
		assertEquals(3205, (int) r.nearestBank(new Tile(3200, 3200, 0, false, null)).get().getTile().getX());
		assertFalse(new LocationResolver(GpsData.empty(null), QuestCatalog.build(GpsData.empty(null), Collections.emptyMap())).nearestBank(new Tile(1, 1, 0, false, null)).isPresent());
	}
}
```

(`GpsTestFixtures.dataWithLocations(String)` writes `locations.json` to a temp dir and calls `GpsDataStore.load`; add it to the shared `GpsTestFixtures` from M2 Task 3.)

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** with two indexes built in the constructor: `Map<Integer, List<LocationEntry>> byId` and `Map<String, List<LocationEntry>> byLowerName`, plus the list of banks.
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/travel src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): LocationResolver for tasks, quest steps and banks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 5: `TransportModel` (usable edges, charter links, acquirable teleports)

**Files:**
- Create: `Acquisition.java`, `UsableEdge.java`, `TransportModel.java`
- Test: `src/test/java/com/chunkblazer/gps/travel/TransportModelTest.java`

**Interfaces:**
- Consumes: `GpsData.getTransport()` (`TransportEdge{id, kind, name, from, to, ticks, requires, consumesItem}`), `GpsData.getItems()` (`ItemDef.sources` with `type == "SHOP"`, `shop`, `price`), `LocationResolver`-style shop tile lookup via `GpsData.getLocations()` (kind `SHOP`, by shop name), `ChunkNode.charters`, `PlayerState`.
- Produces: `TransportModel(GpsData data, List<ChunkNode> chunks, QuestCatalog quests)`; `List<UsableEdge> usable(PlayerState s, Set<String> acquiredEdgeIds, boolean includeAcquirable)`.
- Rules:
  1. Skill and quest requirements of an edge use the M2 `Requirement` (`Requirement.builder()` from `requires.skills/quests`); unmet skill/quest -> excluded.
  2. Endpoint chunks: `to` tile's region must be in `s.getUnlockedChunks()`; if `from != null` its region must be too; else excluded.
  3. Item requirements (`requires.items`, any-of ids with qty): if all owned -> AVAILABLE; else if `acquiredEdgeIds` contains the edge id -> AVAILABLE (the plan buys it); else if every missing item has a SHOP source in `ItemDef.sources` and `includeAcquirable` -> ACQUIRABLE with an `Acquisition` for the cheapest shop source of the first missing item (shop tile from locations when known, else null); else excluded.
  4. `ticks` = edge ticks plus `CONSUMABLE_PENALTY_TICKS` when `consumesItem`.
  5. Charter: for each `ChunkNode` with `charters`, each connection yields an AVAILABLE edge `charter:<fromRegion>-<toRegion>` from `centre(fromRegion)` to `centre(toRegion)` with `ticks = CHARTER_BASE_TICKS + ceil(tileDistance * CHARTER_TICKS_PER_TILE)`; both regions must be unlocked.
  6. Quest-granted unlocks (M2 `PlayerState.withUnlock("TELEPORT:<name>")`): an edge whose id appears in `requires` as a quest requirement is covered by rule 1; an edge whose `name` matches an unlock token `TELEPORT:<name>` is usable without its item requirement (the reward item/ability grants it).
  7. Edge order in the result is deterministic: sorted by id.

- [ ] **Step 1: Write failing tests** with a synthetic `GpsData` built in `GpsTestFixtures.dataWith(transportJson, itemsJson, locationsJson)` (add to fixtures class) and a `ChunkNode` list:

```java
class TransportModelTest
{
	private static final String TELE = "{\"schema\":1,\"edges\":[{\"id\":\"varrock_tele\",\"kind\":\"SPELL\",\"name\":\"Varrock Teleport\",\"from\":null,"
		+ "\"to\":{\"x\":3213,\"y\":3424,\"plane\":0},\"ticks\":8,\"requires\":{\"skills\":{\"MAGIC\":25},\"quests\":[],\"items\":[{\"ids\":[554],\"qty\":1}]},\"consumesItem\":true}]}";
	private static final String ITEMS = "{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},"
		+ "\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Aubury's Rune Shop\",\"price\":17}],\"teleport\":null}]}";
	private static final String LOCS = "{\"schema\":1,\"entries\":[{\"name\":\"Aubury's Rune Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}";
	private static final int VARROCK = TileMath.regionId(3213, 3424);

	private TransportModel model()
	{
		GpsData d = GpsTestFixtures.dataWith(TELE, ITEMS, LOCS);
		return new TransportModel(d, Collections.emptyList(), QuestCatalog.build(d, Collections.emptyMap()));
	}

	private PlayerState state(int magic, boolean haveRune)
	{
		PlayerState.Builder b = PlayerState.builder().level("MAGIC", magic).chunk(VARROCK);
		if (haveRune) b.item(554, 5);
		return b.build();
	}

	@Test
	void availableWhenOwnedAndPenaltyApplied()
	{
		List<UsableEdge> e = model().usable(state(30, true), Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
		assertEquals(8 + TravelConfig.CONSUMABLE_PENALTY_TICKS, e.get(0).getTicks());
	}

	@Test
	void notOwnedIsAcquirableWithShop()
	{
		List<UsableEdge> e = model().usable(state(30, false), Collections.emptySet(), true);
		assertEquals(UsableEdge.Availability.ACQUIRABLE, e.get(0).getAvailability());
		assertEquals("Aubury's Rune Shop", e.get(0).getAcquisition().getShop());
		assertEquals(3253, (int) e.get(0).getAcquisition().getShopTile().getX());
	}

	@Test
	void acquirableExcludedWhenNotRequested()
	{
		assertTrue(model().usable(state(30, false), Collections.emptySet(), false).isEmpty());
	}

	@Test
	void acquiredEdgeBecomesAvailable()
	{
		List<UsableEdge> e = model().usable(state(30, false), Collections.singleton("varrock_tele"), false);
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
	}

	@Test
	void skillTooLowOrChunkLockedExcluded()
	{
		assertTrue(model().usable(state(10, true), Collections.emptySet(), true).isEmpty());
		PlayerState noChunk = PlayerState.builder().level("MAGIC", 30).item(554, 5).build();
		assertTrue(model().usable(noChunk, Collections.emptySet(), true).isEmpty());
	}

	@Test
	void unlockTokenGrantsEdgeWithoutItems()
	{
		PlayerState s = PlayerState.builder().level("MAGIC", 30).chunk(VARROCK).build().withUnlock("TELEPORT:Varrock Teleport");
		List<UsableEdge> e = model().usable(s, Collections.emptySet(), false);
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
	}

	@Test
	void charterEdgeFromChunkNodes()
	{
		int a = TileMath.regionId(3000, 3000), b = TileMath.regionId(2000, 3000);
		ChunkNode port = new ChunkNode(Arrays.asList(a), Collections.emptyList(), true,
			Arrays.asList(new ChunkNode.Charter(b, "Sail west")));
		TransportModel m = new TransportModel(GpsData.empty(null), Arrays.asList(port), QuestCatalog.build(GpsData.empty(null), Collections.emptyMap()));
		PlayerState s = PlayerState.builder().chunk(a).chunk(b).build();
		List<UsableEdge> e = m.usable(s, Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertTrue(e.get(0).getEdge().getId().startsWith("charter:"));
		assertTrue(e.get(0).getTicks() >= TravelConfig.CHARTER_BASE_TICKS);
		assertTrue(m.usable(PlayerState.builder().chunk(a).build(), Collections.emptySet(), true).isEmpty());
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** per the rules (build `TransportEdge` objects for charters through the Gson-constructed DTO or add a package-visible factory `TransportEdge.charter(...)` next to the DTO with a clear javadoc).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps
git commit -m "feat(gps): TransportModel with ownable/acquirable teleports and charter edges

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 6: `CostMatrix` (hub decomposition)

**Files:**
- Create: `TravelNode.java`, `TravelContext.java`, `CostMatrix.java`
- Test: `src/test/java/com/chunkblazer/gps/travel/CostMatrixTest.java`

**Interfaces:**
- Consumes: `ChunkGraph.View` (via `WalkModel`), `TransportModel.usable`, `TravelNode`s.
- Produces: `TravelContext(PlayerState state, Set<String> acquiredEdgeIds, boolean includeAcquirable)` (accessible regions derived from `state.getUnlockedChunks()`), and `CostMatrix.build(nodes, ctx, graph, transport)` with `cost(i,j)`, `size()`, `route(i,j)`.
- Algorithm: let `W(a,b) = WalkModel.ticks`. Hubs `H` = distinct transport endpoint tiles (`edge.to`, and `edge.from` when non-null) plus network hubs: for edges of kind in `NETWORK_KINDS` every `to` tile is a network entry; add virtual hub vertex per kind with edges `to-tile ->(0)-> hubKind` and `hubKind ->(edge.ticks)-> to-tile`; `from==null` non-network edges (spells, item teleports) are "anywhere" edges: add a virtual vertex `ANYWHERE` with `ANYWHERE ->(edge.ticks)-> to-tile` and every node/hub reaches `ANYWHERE` at cost 0. All-pairs shortest path `T` over `H ∪ virtual` using `W` between real tiles and the edges above (Floyd-Warshall, `H` is a few hundred at most). Then for each node `i`: `dA[i][t] = min_s(W(i, s) + T[s][t])` over hubs `s`, with the ANYWHERE vertex treated as `s` with `W(i, ANYWHERE) = 0`; `cost(i,j) = min(W(i,j), min_t(dA[i][t] + W(t, j)))`. Diagonal is 0. Symmetry is NOT assumed (one-way transport). `route(i,j)` re-derives the best `(s,t)` pair by the same minimisation and returns the edge ids along the hub path (empty when walking is best). Unknown-location nodes (`tile == null`) have `INF` to everything.

- [ ] **Step 1: Write failing tests** on a fixture: nodes A (3200,3200), B (3400,3200) in the same unlocked region set; a teleport edge `from:null` to a tile next to B; walking A->B 100 tiles*0.5*1.25 within-chunk rules apply (same region = 0.5/tile). Use two regions so detour applies.

```java
class CostMatrixTest
{
	private static Tile t(int x, int y) { return new Tile(x, y, 0, false, null); }

	private static final String TELE = "{\"schema\":1,\"edges\":[{\"id\":\"tele\",\"kind\":\"SPELL\",\"name\":\"Tele\",\"from\":null,"
		+ "\"to\":{\"x\":3300,\"y\":3200,\"plane\":0},\"ticks\":10,\"requires\":{\"skills\":{},\"quests\":[],\"items\":[]},\"consumesItem\":false}]}";

	private CostMatrix build(boolean withTeleport, boolean unlockB)
	{
		int ra = TileMath.regionId(3200, 3200), rb = TileMath.regionId(3400, 3200); // 50 and 52 -> not adjacent
		int mid = TileMath.regionId(3300, 3200);                                   // 51
		List<ChunkNode> chunks = Arrays.asList(
			new ChunkNode(Arrays.asList(ra), Arrays.asList(mid), false, Collections.emptyList()),
			new ChunkNode(Arrays.asList(mid), Arrays.asList(ra, rb), false, Collections.emptyList()),
			new ChunkNode(Arrays.asList(rb), Arrays.asList(mid), false, Collections.emptyList()));
		GpsData d = withTeleport ? GpsTestFixtures.dataWith(TELE, "{\"schema\":1,\"items\":[]}", "{\"schema\":1,\"entries\":[]}") : GpsData.empty(null);
		PlayerState.Builder b = PlayerState.builder().chunk(ra).chunk(mid);
		if (unlockB) b.chunk(rb);
		PlayerState s = b.build();
		ChunkGraph g = ChunkGraph.build(chunks);
		TransportModel tm = new TransportModel(d, chunks, QuestCatalog.build(d, Collections.emptyMap()));
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("B", t(3400, 3200)));
		return CostMatrix.build(nodes, new TravelContext(s, Collections.emptySet(), false), g, tm);
	}

	@Test
	void diagonalIsZero()
	{
		CostMatrix m = build(false, true);
		assertEquals(0, m.cost(0, 0));
		assertEquals(0, m.cost(1, 1));
	}

	@Test
	void walkingOnlyThroughUnlockedChunks()
	{
		CostMatrix m = build(false, true);
		assertTrue(m.cost(0, 1) > 0 && m.cost(0, 1) < TravelConfig.INF);
		assertTrue(m.route(0, 1).isEmpty());
	}

	@Test
	void lockedDestinationIsInfinite()
	{
		CostMatrix m = build(false, false);
		assertEquals(TravelConfig.INF, m.cost(0, 1));
	}

	@Test
	void teleportShortensTheTripAndRouteNamesIt()
	{
		CostMatrix walk = build(false, true);
		CostMatrix tele = build(true, true);
		assertTrue(tele.cost(0, 1) < walk.cost(0, 1));
		assertEquals(Collections.singletonList("tele"), tele.route(0, 1));
	}

	@Test
	void unknownTileNodeIsUnroutable()
	{
		List<TravelNode> nodes = Arrays.asList(new TravelNode("A", t(3200, 3200)), new TravelNode("X", null));
		CostMatrix m = CostMatrix.build(nodes, new TravelContext(PlayerState.freshAccount(), Collections.emptySet(), false),
			ChunkGraph.build(Collections.emptyList()), new TransportModel(GpsData.empty(null), Collections.emptyList(), QuestCatalog.build(GpsData.empty(null), Collections.emptyMap())));
		assertEquals(TravelConfig.INF, m.cost(0, 1));
		assertEquals(TravelConfig.INF, m.cost(1, 0));
	}
}
```

- [ ] **Step 2: Run to verify failure** — Expected: compile errors.
- [ ] **Step 3: Implement** the algorithm above. Use `int[][]`. Guard all additions with `INF` saturation (`add(a,b) = a>=INF||b>=INF ? INF : a+b`).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps/travel src/test/java/com/chunkblazer/gps/travel
git commit -m "feat(gps): CostMatrix with hub decomposition and route reconstruction

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 7: Matrix performance, determinism and real-chunk smoke test

**Files:**
- Test: `src/test/java/com/chunkblazer/gps/travel/CostMatrixPerfTest.java`, `RealChunkGraphTest.java`

**Interfaces:** Consumes everything above; no new production code unless a test exposes a defect (fix the defect with its own failing test first).

- [ ] **Step 1: Write the tests**

```java
class CostMatrixPerfTest
{
	@Test
	void fiveHundredNodesWithTwoHundredHubsBuildsQuickly()
	{
		// synthetic: 60x60 grid of chunks (3600 regions), 200 teleport edges, 500 random nodes (seeded)
		SyntheticWorld w = SyntheticWorld.create(new Random(42), 60, 200, 500);
		long t0 = System.nanoTime();
		CostMatrix m = CostMatrix.build(w.nodes, w.ctx, w.graph, w.transport);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertEquals(500, m.size());
		assertTrue(ms < 3000, "matrix took " + ms + " ms");
	}

	@Test
	void sameInputGivesIdenticalMatrix()
	{
		SyntheticWorld w1 = SyntheticWorld.create(new Random(7), 20, 50, 100);
		SyntheticWorld w2 = SyntheticWorld.create(new Random(7), 20, 50, 100);
		CostMatrix a = CostMatrix.build(w1.nodes, w1.ctx, w1.graph, w1.transport);
		CostMatrix b = CostMatrix.build(w2.nodes, w2.ctx, w2.graph, w2.transport);
		for (int i = 0; i < a.size(); i++)
			for (int j = 0; j < a.size(); j++)
				assertEquals(a.cost(i, j), b.cost(i, j));
	}
}
```

`SyntheticWorld` is a test-only helper class in the same test package: builds a `gridSize x gridSize` grid of single-region `ChunkNode`s with 4-neighbour links, an all-unlocked `PlayerState`, `edgeCount` `SPELL` edges with `from:null` and random destination tiles, and `nodeCount` random `TravelNode`s, entirely from the seeded `Random`.

```java
class RealChunkGraphTest
{
	@Test
	void shippedChunksBuildAndStartingAreaIsWalkable()
	{
		List<NuzlockeChunk> chunks = CatalogTestSupport.loadCatalogChunks();
		List<ChunkNode> nodes = ChunkNodes.from(chunks);
		assertTrue(nodes.size() > 100);
		Set<Integer> all = new HashSet<>();
		for (ChunkNode n : nodes) all.addAll(n.getRegionIds());
		ChunkGraph.View v = ChunkGraph.build(nodes).accessible(all);
		// Lumbridge (12850) to Varrock (12853) is walkable once everything is unlocked
		int ticks = v.walkTicks(TileMath.centre(12850), TileMath.centre(12853));
		assertTrue(ticks > 0 && ticks < TravelConfig.INF, "ticks=" + ticks);
	}

	@Test
	void everyNeighbourReferenceResolvesToAKnownRegion()
	{
		List<ChunkNode> nodes = ChunkNodes.from(CatalogTestSupport.loadCatalogChunks());
		Set<Integer> known = new HashSet<>();
		for (ChunkNode n : nodes) known.addAll(n.getRegionIds());
		List<String> dangling = new ArrayList<>();
		for (ChunkNode n : nodes)
			for (int nb : n.getNeighborRegionIds())
				if (!known.contains(nb)) dangling.add(n.getRegionIds() + "->" + nb);
		// informational guard: dangling links are tolerated by the graph but should be rare
		assertTrue(dangling.size() < nodes.size() / 10, "dangling neighbour links: " + dangling);
	}
}
```

(`CatalogTestSupport.loadCatalogChunks()` is added to the M2 support class: same traversal as `loadCatalogTasks`, deserialising each `{region_id, ..., tasks}` object into `NuzlockeChunk` with `new Gson().fromJson(obj, NuzlockeChunk.class)`; if the Lumbridge-to-Varrock assertion fails because the catalog's region ids differ from 12850/12853, replace them with two adjacent chunks from the loaded list found programmatically: the first chunk and its first neighbour.)

- [ ] **Step 2: Run to verify** — `./gradlew test --tests "com.chunkblazer.gps.travel.*"` — Expected: FAIL first only if a defect exists; otherwise PASS. If the perf bound fails, profile `CostMatrix.build` (the `dA` loop is the hot spot) and optimise (precompute `W(i, hub)` once per node row; early-continue on `INF`) with the test as the guard.
- [ ] **Step 3: Fix any defect** found, each with a minimal failing test added first.
- [ ] **Step 4: Run the full suite** — `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/chunkblazer/gps src/main/java/com/chunkblazer/gps
git commit -m "test(gps): cost matrix performance, determinism and real chunk graph checks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

## Self-Review

- Spec coverage: section 6 walking/chunk detour/transport/charter/cost matrix/consumable penalty (Tasks 1-6); section 5.1 chunk-centre fallback and walking-only degradation (Tasks 4, 5, 6 `GpsData.empty` cases); quest step locations (Task 4 `forQuestStep`); acquirable teleports as the data M4 needs (Task 5 `ACQUIRABLE` + `acquiredEdgeIds` what-if); permanence of the model stated in Global Constraints.
- Deliberately left to M4: deciding WHICH acquisitions are worth it (needs the whole plan) and precedence handling; this milestone only exposes `usable(..., acquiredEdgeIds, includeAcquirable)` and `CostMatrix.build` as the what-if primitive.
- Types used in later tasks match the interface block (`Tile(Integer x, Integer y, int plane, boolean approximate, String source)` from M1; `QuestHelperEntry(List<Integer> chunks, List<String> quests, Map<String,Integer> skills)` from M2).
