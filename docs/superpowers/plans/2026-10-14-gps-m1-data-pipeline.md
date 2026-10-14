# Progression GPS M1: Local Data Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the raw wiki cache produced by the existing (separately owned, already running) scraper fetch layer into the processed JSON the plugin loads at runtime (monsters, items, quests with ordered steps and resolved locations, transport, locations, training), plus the Java loader that degrades gracefully when data is absent.

**Architecture:** The fetch layer (Python stdlib, `tools/wiki-scraper/`, persistent raw cache, at most 10 requests/min) already exists and is NOT part of this plan. A new local-only Python package `tools/wiki-scraper/gps_process/` reads that cache through a read-only `RawCache` adapter, runs pure transform functions, and writes JSON files to `~/.runelite/chunkblazer/gps-data/`. The plugin side adds `com.chunkblazer.gps.data` (DTOs + `GpsDataStore`) that reads that directory with Gson and never throws.

**Tech Stack:** Python 3.12 (stdlib: json, re, argparse, unittest), Java 11 release, Gson, Lombok, JUnit 5 (all already present; no new dependencies).

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 4, 5.1, 5.2).

## Global Constraints

- Nothing wiki-derived is ever committed or published: the scraper, its raw cache, the processing code and the processed output stay off GitHub. Exclusion is via `.git/info/exclude` (owned by the fetch agent; this plan does not edit it), NOT the committed `.gitignore`.
- The fetch layer's rules stand and processing must not break them: each page fetched once (persistent raw cache), strict self rate limit, descriptive User-Agent, bulk queries. Processing code NEVER makes network requests and never writes to the raw cache; anything missing from the cache is requested from the fetch agent.
- Processed output default: `~/.runelite/chunkblazer/gps-data/`; plugin config `gpsDataDir` overrides (blank = default). Nothing in `src/main/resources`.
- Java: release 11, Gson + Lombok only, no new dependencies, logic classes have no `Client` dependency. Java tests use synthetic hand-written fixtures in `src/test/resources/com/chunkblazer/gps/`, never real wiki data.
- Do not edit the committed `.gitignore` or `tools/wiki-scraper/` files owned by the fetch agent. Never `git add` anything under `tools/`.
- The plans and spec are committed; the scraper and processing code are not. Commit steps below that touch only Java/docs use normal `git add` of explicit paths; Python tasks have NO commit step (local-only) and end with the test run.
- Processing is ordered so it can be written and unit-tested against synthetic fixtures while the crawl is still running; the real `build` run waits for the cache (Task 12).

## Review Focus

- Raw-cache entry truncated or corrupt (the crawl was killed mid-write) or the cache directory empty: `RawCache` must skip it and never raise; transforms see fewer pages, not a crash. Tested in Task 1.
- A wiki page that does not exist (title guessed wrong) or has no recognisable sections: transforms must skip it, count it in the validation report, and never crash. Tested in Tasks 7, 8 and 12.
- Bucket rows with missing/renamed fields or non-numeric values: skipped or defaulted, never an exception. Tested in Task 6.
- Quest steps whose location cannot be resolved: kept in order, flagged approximate or `NONE`, and counted in the validation report. Tested in Task 8.
- `gps-data` directory missing, a file empty, or a file containing invalid JSON: the Java loader must return partial data plus a status and never throw. Tested in Task 13.

## File Structure

Local only (excluded from git; the fetch layer's files in `tools/wiki-scraper/` already exist and are not listed):
- `tools/wiki-scraper/gps_process/__init__.py`
- `tools/wiki-scraper/gps_process/rawcache.py` — read-only `RawCache` over the fetch layer's cache (Task 1)
- `tools/wiki-scraper/gps_process/wikitext.py` — shared wikitext helpers (strip markup, links, templates, tables)
- `tools/wiki-scraper/gps_process/build_*.py` — one transform per output (`items`, `monsters`, `quests`, `steps`, `locations`, `transport`, `training`), `build.py` orchestrator
- `tools/wiki-scraper/gps_process/validate.py` — validation report + `data_version.json`
- `tools/wiki-scraper/process.py` — entry point (`build`, `validate`)
- `tools/wiki-scraper/gps_process/tests/` — unittest tests and `fixtures/` (tiny hand-written samples)
- `tools/wiki-scraper/gps_process/NOTES.md` — cache layout, real field names, bucket names, template names

Committed (Java):
- `src/main/java/com/chunkblazer/gps/data/*.java` — DTOs
- `src/main/java/com/chunkblazer/gps/data/GpsDataStore.java`, `GpsData.java`, `GpsDataStatus.java`
- `src/test/java/com/chunkblazer/gps/data/GpsDataStoreTest.java`
- `src/test/resources/com/chunkblazer/gps/data/` — synthetic fixtures
- `src/main/java/com/chunkblazer/ChunkBlazerConfig.java` — `gpsDataDir` string item

## Data contracts (source of truth; scraper writes, Java reads)

All files are UTF-8 JSON objects with `"schema": 1`. `Tile` is `{"x":3200,"y":3200,"plane":0}`. Skill names are the RuneLite `Skill` enum names (`ATTACK`, `HITPOINTS`, `COOKING`, ...). Quest ids are the RuneLite `Quest` enum names (`COOKS_ASSISTANT`) so they match `quest_requirements.json`.

`data_version.json`: `{"schema":1,"data_version":"2026-10-14T12:00:00Z","generated_at":"...","counts":{"monsters":0,"items":0,"quests":0,"quest_steps":0,"transport":0,"locations":0,"training":0},"unresolved_quest_steps":0}`

`monsters.json`: `{"schema":1,"monsters":[{"ids":[2790,2791],"name":"Cow","hp":8,"combatLevel":2,"maxHit":1,"attackStyles":["melee"],"defenceLevel":2,"defence":{"stab":0,"slash":0,"crush":0,"magic":-21,"ranged":0}}]}`

`items.json`: `{"schema":1,"items":[{"id":1291,"name":"Bronze longsword","members":false,"slot":"weapon","requirements":{"skills":{"ATTACK":1},"quests":[]},"sources":[{"type":"SHOP","shop":"Varrock Sword Shop","price":26},{"type":"CRAFT","skill":"SMITHING","level":4},{"type":"DROP","npc":"Goblin"},{"type":"QUEST_REWARD","quest":"COOKS_ASSISTANT"}],"teleport":null}]}` where `teleport` is `null` or `{"destination":"Varrock","charges":1}`.

`quests.json`: `{"schema":1,"quests":[{"id":"COOKS_ASSISTANT","name":"Cook's Assistant","requirements":{"skills":{},"quests":[],"questPoints":0,"combatLevel":0,"items":[{"name":"Egg","ids":[1944],"qty":1}]},"rewards":{"xp":{"COOKING":300},"choiceXp":[{"amount":5000,"count":1,"skills":["ANY"]}],"questPoints":1,"unlocks":[{"type":"TELEPORT","name":"Explorer's ring"}]},"steps":[{"index":0,"text":"Talk to the Cook in Lumbridge Castle.","kind":"TALK","location":{"x":3208,"y":3214,"plane":0,"approximate":false,"source":"MAP_TEMPLATE"},"items":[]}]}]}`. `location.source` is one of `MAP_TEMPLATE`, `NAMED_LINK`, `OVERRIDE`, `CARRIED`, `CHUNK_CENTRE`. `kind` is one of `TALK`, `KILL`, `USE`, `TRAVEL`, `OTHER`.

`transport.json`: `{"schema":1,"edges":[{"id":"varrock_teleport","kind":"SPELL","name":"Varrock Teleport","from":null,"to":{"x":3213,"y":3424,"plane":0},"ticks":5,"requires":{"skills":{"MAGIC":25},"quests":[],"items":[{"ids":[554],"qty":1}]},"consumesItem":false}]}`. `from:null` means usable from anywhere. `kind` is one of `SPELL`, `ITEM`, `SPIRIT_TREE`, `FAIRY_RING`, `GLIDER`, `CHARTER`, `OTHER`.

`locations.json`: `{"schema":1,"entries":[{"name":"Cook","kind":"NPC","ids":[4626],"x":3208,"y":3214,"plane":0}]}` with `kind` one of `NPC`, `OBJECT`, `SHOP`, `BANK`, `PLACE`.

`training_methods.json`: `{"schema":1,"methods":[{"skill":"COOKING","name":"Cook shrimps","minLevel":1,"maxLevel":30,"xpPerHour":30000,"location":{"x":3211,"y":3215,"plane":0},"items":[{"name":"Raw shrimps","ids":[317],"qty":0}],"approximate":true}]}`

Local `overrides/` files (hand-written, optional): `quest_step_overrides.json` = `{"COOKS_ASSISTANT":{"0":{"x":3208,"y":3214,"plane":0}}}`; `location_overrides.json` = `{"Cook":{"x":3208,"y":3214,"plane":0}}`; `transport_seed.json` = same shape as `transport.json` (its edges are merged in, seed wins on equal `id`); `requirement_overrides.json` is defined in M2.

---

## Tasks

Order matters: the fetch layer already exists and its crawl is already running (not planned here). Task 1 (raw-cache reader) comes first; Tasks 5-12 are transforms written and unit-tested against synthetic fixtures while the crawl runs, and the real `build` run happens at the end of Task 12 once the cache is complete. Tasks 13-14 are the Java side, independent of the crawl, and can run in parallel with everything else. Tasks 2-4 are intentionally empty (they belonged to the fetch layer).

### Existing component: the fetch layer (owned by the scraper agent, NOT planned here)

A separate agent is building and running the wiki scraper's FETCH layer right now (Python stdlib, in `tools/wiki-scraper/`, persistent raw cache, at most 10 requests/min, descriptive User-Agent, backoff on 429/5xx, MediaWiki batched queries and possibly the Bucket API). That agent owns `tools/wiki-scraper/` (its code, its cache, its tests) and `.git/info/exclude`. This plan builds on it and must not edit those. Tasks 1-4 of the earlier draft of this plan (rate limiter, cache, fetcher, API helpers, manifest, crawl) are therefore removed; the numbering of the remaining tasks is kept so cross-references stay valid. The one thing this plan needs from the fetch layer is a way to READ its raw cache, handled in Task 1 below.

Processing code lives in a separate, equally local-only package `tools/wiki-scraper/gps_process/` (excluded by the same `tools/wiki-scraper/` rule, so it never reaches git) with its entry point `tools/wiki-scraper/process.py` (`python process.py build|validate`). If the fetch agent objects to files appearing under its directory, relocate to `tools/wiki-process/` and add that path to `.git/info/exclude` through the fetch agent or the coordinator (do not edit the exclude file directly).

Coordination requests to the fetch agent (record the answers in `tools/wiki-scraper/gps_process/NOTES.md`):
1. Cache layout and record format (so Task 1's adapter can be written against reality).
2. Which groups it fetches: quest pages, `<Quest>/Quick guide` pages, monsters, items/prices mapping, shops, transport pages (spirit trees, fairy rings, gliders, teleports), training pages, and NPC/object/place pages linked from quick guides. Anything missing is requested from that agent (Task 12 produces the list of link targets to fetch).
3. The real Bucket names and field names it saw, and the real coordinate template names in quick guides/NPC pages (Tasks 6-9 use best-guess names that must be corrected to these).

### Task 1: Raw-cache reader adapter and processing scaffolding

**Files:**
- Create: `tools/wiki-scraper/gps_process/__init__.py` (empty), `tools/wiki-scraper/gps_process/rawcache.py`, `tools/wiki-scraper/gps_process/NOTES.md`, `tools/wiki-scraper/process.py`
- Create: `tools/wiki-scraper/gps_process/tests/__init__.py`, `tests/test_rawcache.py`, `tests/fixtures/` (tiny hand-written raw-cache samples in the fetch layer's real format)

**Interfaces:**
- Consumes: the fetch layer's on-disk cache (read-only; this code never writes to it and never makes network requests).
- Produces (`rawcache.py`) a read-only facade used by every transform:

```python
class RawCache:
    @staticmethod
    def open(root: str) -> "RawCache": ...
    def pages(self) -> dict[str, str | None]:
        """title -> wikitext; None for a page the wiki reported missing."""
    def page(self, title: str) -> str | None: ...
    def bucket_rows(self, bucket_name: str) -> list[dict]:
        """all cached rows of a Bucket query, concatenated across pages (empty list if never fetched)."""
    def mapping(self) -> list[dict]:
        """the prices /mapping rows (id, name, members, ...), or []"""
    def titles(self) -> list[str]: ...
```

`process.py` parses `--raw DIR` (default `tools/wiki-scraper/cache`, or whatever the fetch layer uses; confirm), `--out DIR` (default `~/.runelite/chunkblazer/gps-data`) and the subcommands `build` and `validate`, implemented in Tasks 6-12 (`gps_process.build.build_all(args)`, `gps_process.validate.run(args)`).

- [ ] **Step 1: Read the fetch layer's cache code and one real cache file; write what you find in `NOTES.md`** (layout, key scheme, record fields such as body/url/fetched_at, how batched `action=query` responses are stored (per title or per request), how Bucket pages and the mapping are stored). Do not guess: open the files.

- [ ] **Step 2: Write the failing test against a hand-built fixture in that exact format**

Create `tests/fixtures/` by writing 4 tiny cache entries in the fetch layer's real format (copy the structure of real entries, but hand-type the content, and keep it synthetic): one wikitext page ("Cook's Assistant" with a two-line body), one missing page, one Bucket page with two monster rows, one mapping with one item. Then:

```python
# tests/test_rawcache.py
import os, unittest
from gps_process.rawcache import RawCache

FIX = os.path.join(os.path.dirname(__file__), "fixtures")

class RawCacheTests(unittest.TestCase):
    def setUp(self):
        self.c = RawCache.open(FIX)

    def test_pages_and_missing(self):
        pages = self.c.pages()
        self.assertIn("Cook's Assistant", pages)
        self.assertIsInstance(pages["Cook's Assistant"], str)
        self.assertIsNone(pages["No Such Page"])

    def test_page_lookup(self):
        self.assertIsNone(self.c.page("never fetched"))
        self.assertIn("Cook", self.c.page("Cook's Assistant"))

    def test_bucket_rows(self):
        rows = self.c.bucket_rows("infobox_monster")
        self.assertEqual(len(rows), 2)
        self.assertEqual(self.c.bucket_rows("never_fetched"), [])

    def test_mapping(self):
        self.assertEqual(self.c.mapping()[0]["id"], 1291)

    def test_empty_directory_is_empty_not_crash(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            c = RawCache.open(d)
            self.assertEqual(c.pages(), {})
            self.assertEqual(c.mapping(), [])
            self.assertEqual(c.bucket_rows("x"), [])

    def test_corrupt_entry_skipped(self):
        import tempfile, json
        with tempfile.TemporaryDirectory() as d:
            # write one corrupt file in the same place/shape the fetch layer uses (see NOTES.md)
            RawCacheTestHelpers.write_corrupt(d)
            self.assertEqual(RawCache.open(d).pages(), {})
```

(`RawCacheTestHelpers.write_corrupt` is a small helper in the test module that writes a truncated JSON file into the layout found in Step 1.)

- [ ] **Step 3: Run to verify failure**

Run: `cd tools/wiki-scraper && python -m unittest discover -s gps_process/tests -t . -v`
Expected: ImportError (rawcache missing).

- [ ] **Step 4: Implement `RawCache` against the real layout** (read-only; tolerate corrupt/truncated entries by skipping them; never raise on a missing directory). Add `process.py`:

```python
# process.py
import argparse, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_RAW = os.path.join(HERE, "cache")   # change if the fetch layer uses another directory
DEFAULT_OUT = os.path.join(os.path.expanduser("~"), ".runelite", "chunkblazer", "gps-data")

def main(argv=None):
    p = argparse.ArgumentParser(prog="process.py")
    p.add_argument("command", choices=["build", "validate"])
    p.add_argument("--raw", default=DEFAULT_RAW)
    p.add_argument("--out", default=DEFAULT_OUT)
    args = p.parse_args(argv)
    if args.command == "build":
        from gps_process.build import build_all
        return build_all(args)
    from gps_process.validate import run
    return run(args)

if __name__ == "__main__":
    sys.exit(main())
```

(In later tasks, `args.cache` means `args.raw`; use `RawCache.open(args.raw)`.)

- [ ] **Step 5: Run to verify pass**

Run: `cd tools/wiki-scraper && python -m unittest discover -s gps_process/tests -t . -v`
Expected: PASS. Confirm with `git status` that nothing under `tools/` appears. No commit (local-only).

### Task 2: Reserved

Fetch layer (rate limiter, cache, fetcher): owned by the scraper agent. Nothing to do.

### Task 3: Reserved

Fetch layer (bulk API helpers, CLI): owned by the scraper agent. Nothing to do.

### Task 4: Reserved (crawl)

Probe, manifest and crawl: owned by the scraper agent, already in progress. Before starting Task 7/8/9, check with it (or inspect the cache) that quest pages, quick guides and the NPC/object pages for the link targets (Task 12) have been fetched; the transforms below can be written and unit-tested against synthetic fixtures meanwhile.

### Task 5: Shared wikitext helpers

**Files:**
- Create: `tools/wiki-scraper/gps_process/wikitext.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_wikitext.py`

**Interfaces:**
- Produces:
  - `links(text: str) -> list[str]` — page targets of `[[Target|label]]` / `[[Target#frag|x]]`, in order, ignoring `File:`/`Category:`.
  - `strip_markup(text: str) -> str` — plain text: templates removed (except that `{{plink|X}}`/`{{SCP|Skill|N}}`-style templates yield their first meaningful arg), links to labels, bold/italic and HTML tags removed, whitespace collapsed.
  - `templates(text: str, name: str) -> list[dict[str, str]]` — every `{{name|a=b|c}}` (case-insensitive name, nested braces balanced) as dict with positional args keyed `"1"`, `"2"`, ... and named args by name.
  - `sections(text: str) -> dict[str, str]` — heading text -> section body (any `==` level).
  - `tables(text: str) -> list[list[dict[str, str]]]` — each `{| ... |}` table as a list of row dicts keyed by header cell text (stripped via `strip_markup`).
  - `SKILLS: dict[str, str]` mapping wiki display name (`"Hitpoints"`) -> enum (`"HITPOINTS"`).

- [ ] **Step 1: Failing tests**

```python
# tests/test_wikitext.py
import unittest
from gps_process import wikitext as w

class WikitextTests(unittest.TestCase):
    def test_links(self):
        self.assertEqual(w.links("Talk to [[Cook]] in [[Lumbridge Castle#Kitchen|the kitchen]] [[File:x.png]]"),
                         ["Cook", "Lumbridge Castle"])

    def test_strip_markup(self):
        s = "'''Talk''' to the [[Cook|chef]]{{tooltip|x|y}}<br/> now"
        self.assertEqual(w.strip_markup(s), "Talk to the chef now")

    def test_templates_nested_and_named(self):
        t = w.templates("a {{Map|x=3208|y=3214|extra={{inner|1}}}} b {{map|3|4}}", "map")
        self.assertEqual(t[0]["x"], "3208")
        self.assertEqual(t[0]["y"], "3214")
        self.assertEqual(t[1]["1"], "3")

    def test_sections(self):
        s = w.sections("intro\n==Rewards==\n* 300 XP\n===Sub===\ntext\n==Other==\nz")
        self.assertIn("300 XP", s["Rewards"])
        self.assertEqual(s["Other"].strip(), "z")

    def test_tables(self):
        wt = "{|\n! Code !! Location\n|-\n| [[AIQ]] || [[Asgarnian Ice Dungeon]]\n|-\n| BIP || Ardougne Zoo\n|}"
        rows = w.tables(wt)[0]
        self.assertEqual(rows[0], {"Code": "AIQ", "Location": "Asgarnian Ice Dungeon"})
        self.assertEqual(rows[1]["Code"], "BIP")

    def test_skills_map(self):
        self.assertEqual(w.SKILLS["Hitpoints"], "HITPOINTS")
        self.assertEqual(len(w.SKILLS), 23)
```

- [ ] **Step 2: Run to verify failure**

Run: `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_wikitext -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/wikitext.py
import re

SKILLS = {n: n.upper() for n in [
    "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
    "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility",
    "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction"]}

_LINK = re.compile(r"\[\[([^\]\|#]+)(?:#[^\]\|]*)?(?:\|[^\]]*)?\]\]")

def links(text):
    return [m.group(1).strip() for m in _LINK.finditer(text)
            if not m.group(1).lower().startswith(("file:", "category:", "image:"))]

def _remove_templates(text):
    out, depth, i = [], 0, 0
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1; i += 2
        elif text.startswith("}}", i) and depth:
            depth -= 1; i += 2
        else:
            if depth == 0:
                out.append(text[i])
            i += 1
    return "".join(out)

def strip_markup(text):
    text = _remove_templates(text)
    text = re.sub(r"\[\[(?:[^\]\|]*\|)?([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"\[https?://\S+ ([^\]]*)\]", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = text.replace("'''", "").replace("''", "")
    return re.sub(r"\s+", " ", text).strip()

def _split_top(s, sep="|"):
    parts, depth_t, depth_l, cur, i = [], 0, 0, [], 0
    while i < len(s):
        if s.startswith("{{", i): depth_t += 1; cur.append("{{"); i += 2; continue
        if s.startswith("}}", i): depth_t -= 1; cur.append("}}"); i += 2; continue
        if s.startswith("[[", i): depth_l += 1; cur.append("[["); i += 2; continue
        if s.startswith("]]", i): depth_l -= 1; cur.append("]]"); i += 2; continue
        if s[i] == sep and depth_t == 0 and depth_l == 0:
            parts.append("".join(cur)); cur = []
        else:
            cur.append(s[i])
        i += 1
    parts.append("".join(cur))
    return parts

def templates(text, name):
    found, low, i = [], text.lower(), 0
    key = "{{" + name.lower()
    while True:
        i = low.find(key, i)
        if i < 0:
            return found
        j, depth = i, 0
        while j < len(text):
            if text.startswith("{{", j): depth += 1; j += 2
            elif text.startswith("}}", j):
                depth -= 1; j += 2
                if depth == 0: break
            else: j += 1
        body = text[i + 2:j - 2]
        head, *args = _split_top(body)
        if head.strip().lower() != name.lower():
            i += 2; continue
        d, pos = {}, 0
        for a in args:
            if "=" in a and not a.strip().startswith(("[[", "{{")) and re.match(r"\s*[\w \-]+=", a):
                k, v = a.split("=", 1); d[k.strip()] = v.strip()
            else:
                pos += 1; d[str(pos)] = a.strip()
        found.append(d)
        i = j

def sections(text):
    out, cur, buf = {}, None, []
    for line in text.splitlines():
        m = re.match(r"^(={2,6})\s*(.+?)\s*\1\s*$", line)
        if m:
            if cur is not None: out[cur] = "\n".join(buf)
            cur, buf = m.group(2), []
        elif cur is not None:
            buf.append(line)
    if cur is not None: out[cur] = "\n".join(buf)
    return out

def tables(text):
    result = []
    for tm in re.finditer(r"\{\|(.*?)\n\|\}", text, re.S):
        rows, header, cur = [], None, None
        for line in tm.group(1).splitlines():
            line = line.strip()
            if line.startswith("!"):
                header = [strip_markup(c) for c in re.split(r"!!|\|\|", line[1:])]
            elif line.startswith("|-"):
                cur = None
            elif line.startswith("|") and header and not line.startswith(("|+", "|}")):
                cells = [strip_markup(c) for c in line[1:].split("||")]
                rows.append(dict(zip(header, cells)))
        result.append(rows)
    return result
```

- [ ] **Step 4: Run tests**

Run: `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_wikitext -v` — Expected: PASS. Fix any regex edge until green (the `templates` named-arg detection is the likeliest).

No commit (local-only).

### Task 6: Build `items.json` and `monsters.json`

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_items.py`, `build_monsters.py`, `tools/wiki-scraper/gps_process/build.py` (orchestrator, extended by later tasks)
- Test: `tools/wiki-scraper/gps_process/tests/test_build_items_monsters.py`

**Interfaces:**
- Consumes: `RawCache` (Task 1): `mapping()`, `bucket_rows(name)`, `pages()`.
- Produces:
  - `build_monsters(rows: list[dict]) -> dict` — pure, returns the `monsters.json` object (contract above). Bucket rows are the dicts from `wiki.bucket`; parsing uses the field names recorded in NOTES.md (default names from `DEFAULT_BUCKETS`). Rows with no numeric hitpoints are skipped. `id` may be a JSON-list string `"[2790,2791]"` or a single value.
  - `build_items(mapping_rows, shop_rows, bonus_rows, drop_rows) -> dict` — pure, returns the `items.json` object.
  - `build.build_all(args) -> int` — opens `RawCache.open(args.raw)`, calls every `build_*` that exists, writes outputs into `args.out` atomically, returns 0.

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_items_monsters.py
import unittest
from gps_process.build_monsters import build_monsters
from gps_process.build_items import build_items

class MonsterTests(unittest.TestCase):
    def test_basic_monster_and_skip_invalid(self):
        rows = [
            {"page_name": "Cow", "name": "Cow", "hitpoints": "8", "combat_level": "2", "max_hit": "1",
             "attack_style": ["Crush"], "defence_level": "2", "stab_defence_bonus": "0",
             "slash_defence_bonus": "0", "crush_defence_bonus": "0", "magic_defence_bonus": "-21",
             "ranged_defence_bonus": "0", "id": ["2790", "2791"]},
            {"page_name": "Broken", "name": "Broken", "hitpoints": "n/a"},
        ]
        out = build_monsters(rows)
        self.assertEqual(out["schema"], 1)
        self.assertEqual(len(out["monsters"]), 1)
        m = out["monsters"][0]
        self.assertEqual((m["name"], m["hp"], m["maxHit"], m["ids"]), ("Cow", 8, 1, [2790, 2791]))
        self.assertEqual(m["defence"]["magic"], -21)
        self.assertEqual(m["attackStyles"], ["crush"])

class ItemTests(unittest.TestCase):
    def test_item_with_shop_source(self):
        mapping = [{"id": 1291, "name": "Bronze longsword", "members": False, "limit": 15000, "value": 26}]
        shops = [{"page_name": "Varrock Sword Shop", "sold_item": "Bronze longsword", "store_sell_price": "26"}]
        out = build_items(mapping, shops, [], [])
        it = out["items"][0]
        self.assertEqual((it["id"], it["name"], it["members"]), (1291, "Bronze longsword", False))
        self.assertEqual(it["sources"], [{"type": "SHOP", "shop": "Varrock Sword Shop", "price": 26}])

    def test_item_without_sources_still_listed(self):
        out = build_items([{"id": 1, "name": "X", "members": True}], [], [], [])
        self.assertEqual(out["items"][0]["sources"], [])
        self.assertIsNone(out["items"][0]["teleport"])
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_items_monsters -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_monsters.py
import re

def _int(v, default=None):
    try:
        return int(str(v).replace(",", "").strip())
    except (ValueError, TypeError):
        return default

def _ids(v):
    if isinstance(v, list):
        vals = v
    else:
        vals = re.findall(r"\d+", str(v or ""))
    return [i for i in (_int(x) for x in vals) if i is not None]

def build_monsters(rows):
    monsters = []
    for r in rows:
        hp = _int(r.get("hitpoints"))
        if hp is None:
            continue
        styles = r.get("attack_style") or []
        if isinstance(styles, str):
            styles = [styles]
        monsters.append({
            "ids": _ids(r.get("id")),
            "name": r.get("name") or r.get("page_name"),
            "hp": hp,
            "combatLevel": _int(r.get("combat_level"), 0),
            "maxHit": _int(r.get("max_hit"), 0),
            "attackStyles": [s.lower() for s in styles],
            "defenceLevel": _int(r.get("defence_level"), 0),
            "defence": {"stab": _int(r.get("stab_defence_bonus"), 0), "slash": _int(r.get("slash_defence_bonus"), 0),
                        "crush": _int(r.get("crush_defence_bonus"), 0), "magic": _int(r.get("magic_defence_bonus"), 0),
                        "ranged": _int(r.get("ranged_defence_bonus"), 0)},
        })
    return {"schema": 1, "monsters": monsters}
```

```python
# gps_process/build_items.py
from .build_monsters import _int

def build_items(mapping_rows, shop_rows, bonus_rows, drop_rows):
    by_name_shop = {}
    for r in shop_rows:
        price = _int(r.get("store_sell_price"))
        if r.get("sold_item") and price is not None:
            by_name_shop.setdefault(r["sold_item"], []).append(
                {"type": "SHOP", "shop": r.get("page_name") or r.get("sold_by"), "price": price})
    slot_by_name = {r.get("page_name"): (r.get("slot") or r.get("equipment_slot")) for r in bonus_rows}
    drops_by_name = {}
    for r in drop_rows:
        if r.get("item_name") and r.get("page_name"):
            drops_by_name.setdefault(r["item_name"], []).append({"type": "DROP", "npc": r["page_name"]})
    items = []
    for m in mapping_rows:
        name = m["name"]
        items.append({
            "id": m["id"], "name": name, "members": bool(m.get("members", False)),
            "slot": slot_by_name.get(name),
            "requirements": {"skills": {}, "quests": []},
            "sources": by_name_shop.get(name, []) + drops_by_name.get(name, [])[:10],
            "teleport": None,
        })
    return {"schema": 1, "items": items}
```

```python
# gps_process/build.py
import json, os
from .rawcache import RawCache

def write_json(out_dir, name, obj):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name)
    with open(path + ".tmp", "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
    os.replace(path + ".tmp", path)

def build_all(args):
    cache = RawCache.open(args.raw)
    from .build_monsters import build_monsters
    from .build_items import build_items
    counts = {}
    mapping_rows = cache.mapping()
    mon = build_monsters(cache.bucket_rows("infobox_monster"))
    write_json(args.out, "monsters.json", mon); counts["monsters"] = len(mon["monsters"])
    items = build_items(mapping_rows, cache.bucket_rows("storeline"),
                        cache.bucket_rows("infobox_bonuses"), cache.bucket_rows("dropsline"))
    write_json(args.out, "items.json", items); counts["items"] = len(items["items"])
    print("built", counts)
    return 0
```

Note: after the crawl, if the real bucket rows have a different shape than the tests (NOTES.md from Task 4), adjust the field lookups here and add a test row mirroring the real shape.

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_items_monsters -v` — Expected: PASS.

No commit (local-only).

### Task 7: Build quest definitions (requirements, rewards)

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_quests.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_build_quests.py`, fixtures `tests/fixtures/cooks_assistant.wikitext`, `tests/fixtures/quest_requirements_sample.json`

**Interfaces:**
- Consumes: `wikitext.sections/strip_markup/links/SKILLS`; page wikitext from cache ns `page`; the repo file `src/main/resources/com/chunkblazer/quest_requirements.json` (read from disk, path passed in).
- Produces:
  - `quest_enum_id(title: str) -> str` — `"Cook's Assistant"` -> `"COOKS_ASSISTANT"` (uppercase, remove apostrophes, non-alphanumerics to `_`, collapse).
  - `parse_rewards(wikitext: str) -> dict` — `{"xp": {...}, "choiceXp": [...], "questPoints": int, "unlocks": []}` from the quest page's `Rewards` section.
  - `parse_requirements(wikitext: str, fallback: dict | None) -> dict` — from the `Requirements` section; skill lines like `* 10 [[Cooking]]` / `{{SCP|Cooking|10}}`; quest links under a line containing "completed" or "quests"; falls back to `fallback` (the `quest_requirements.json` entry: `skills`, `quests`) when the section yields nothing.
  - `build_quest(title, wikitext, fallback) -> dict` — `QuestDef` without steps (steps added in Task 8; `steps: []`).

- [ ] **Step 1: Create fixtures and failing tests**

`tests/fixtures/cooks_assistant.wikitext` (hand-written, imitating wiki style; NOT copied from the wiki):

```
==Requirements==
* None
==Rewards==
* 1 [[Quest points|Quest point]]
* 300 [[Cooking]] experience
* 20 [[coins]]
* Access to the [[Lumbridge Castle]] range
```

```python
# tests/test_build_quests.py
import os, unittest
from gps_process import build_quests as bq

FIX = os.path.join(os.path.dirname(__file__), "fixtures")

class QuestTests(unittest.TestCase):
    def test_enum_id(self):
        self.assertEqual(bq.quest_enum_id("Cook's Assistant"), "COOKS_ASSISTANT")
        self.assertEqual(bq.quest_enum_id("Dragon Slayer II"), "DRAGON_SLAYER_II")
        self.assertEqual(bq.quest_enum_id("Recipe for Disaster/Freeing King Awowogei"),
                         "RECIPE_FOR_DISASTER_FREEING_KING_AWOWOGEI")

    def test_rewards(self):
        wt = open(os.path.join(FIX, "cooks_assistant.wikitext"), encoding="utf-8").read()
        r = bq.parse_rewards(wt)
        self.assertEqual(r["xp"], {"COOKING": 300})
        self.assertEqual(r["questPoints"], 1)

    def test_lamp_reward_is_choice(self):
        wt = "==Rewards==\n* 2 [[Quest points]]\n* 5,000 [[experience]] in any skill (one lamp)\n"
        r = bq.parse_rewards(wt)
        self.assertEqual(r["choiceXp"], [{"amount": 5000, "count": 1, "skills": ["ANY"]}])

    def test_requirements_parsed_from_section(self):
        wt = "==Requirements==\n* 20 [[Cooking]]\n* {{SCP|Mining|15}}\n* Completion of [[Dragon Slayer I]]\n"
        req = bq.parse_requirements(wt, None)
        self.assertEqual(req["skills"], {"COOKING": 20, "MINING": 15})
        self.assertEqual(req["quests"], ["DRAGON_SLAYER_I"])

    def test_requirements_fallback_to_quest_helper_data(self):
        req = bq.parse_requirements("no sections here", {"skills": {"RANGED": 30}, "quests": ["ERNEST_THE_CHICKEN"]})
        self.assertEqual(req["skills"], {"RANGED": 30})
        self.assertEqual(req["quests"], ["ERNEST_THE_CHICKEN"])

    def test_page_with_no_sections_is_not_a_crash(self):
        q = bq.build_quest("Weird", "garbage", None)
        self.assertEqual(q["id"], "WEIRD")
        self.assertEqual(q["steps"], [])
        self.assertEqual(q["rewards"]["xp"], {})
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_quests -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_quests.py
import re
from . import wikitext as w

def quest_enum_id(title):
    t = title.upper().replace("'", "").replace("\u2019", "")
    return re.sub(r"[^A-Z0-9]+", "_", t).strip("_")

def _num(s):
    return int(s.replace(",", ""))

_SKILL_NAMES = "|".join(w.SKILLS)

def parse_rewards(wikitext):
    body = w.sections(wikitext).get("Rewards", "")
    xp, choice, qp, unlocks = {}, [], 0, []
    for line in body.splitlines():
        if not line.lstrip().startswith("*"):
            continue
        text = w.strip_markup(line.lstrip("* ").strip())
        m = re.search(r"([\d,]+)\s+(" + _SKILL_NAMES + r")\s+(?:experience|XP)", text, re.I)
        if m:
            key = w.SKILLS[m.group(2).capitalize()]
            xp[key] = xp.get(key, 0) + _num(m.group(1)); continue
        m = re.search(r"([\d,]+)\s+experience\s+in\s+any\s+skill", text, re.I)
        if m:
            choice.append({"amount": _num(m.group(1)), "count": 1, "skills": ["ANY"]}); continue
        m = re.search(r"(\d+)\s+quest points?", text, re.I)
        if m:
            qp += int(m.group(1)); continue
        m = re.search(r"(?:access to|ability to use|unlock(?:s|ed)?)\s+(.+)", text, re.I)
        if m:
            unlocks.append({"type": "OTHER", "name": m.group(1).strip()})
    return {"xp": xp, "choiceXp": choice, "questPoints": qp, "unlocks": unlocks}

def parse_requirements(wikitext, fallback):
    body = w.sections(wikitext).get("Requirements", "")
    skills, quests = {}, []
    for line in body.splitlines():
        if not line.lstrip().startswith("*"):
            continue
        for t in w.templates(line, "SCP"):
            name, lvl = t.get("1", ""), t.get("2", "")
            if name in w.SKILLS and lvl.isdigit():
                skills[w.SKILLS[name]] = int(lvl)
        m = re.search(r"(\d+)\s+\[\[(" + _SKILL_NAMES + r")(?:\|[^\]]*)?\]\]", line)
        if m:
            skills[w.SKILLS[m.group(2)]] = int(m.group(1))
        elif re.search(r"complet|finish|started", line, re.I):
            quests += [quest_enum_id(l) for l in w.links(line)]
    if not skills and not quests and fallback:
        return {"skills": dict(fallback.get("skills", {})), "quests": list(fallback.get("quests", [])),
                "questPoints": 0, "combatLevel": 0, "items": []}
    return {"skills": skills, "quests": quests, "questPoints": 0, "combatLevel": 0, "items": []}

def build_quest(title, wikitext, fallback):
    return {"id": quest_enum_id(title), "name": title,
            "requirements": parse_requirements(wikitext, fallback),
            "rewards": parse_rewards(wikitext), "steps": []}
```

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_quests -v` — Expected: PASS. Then compare one real cached quest page (from `status`-confirmed cache) against NOTES.md; if the real Rewards/Requirements format differs, add a fixture line copied in shape (retyped, not pasted) and extend the regexes until the new test passes.

No commit (local-only).

### Task 8: Quest steps from quick guides, with location resolution

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_steps.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_build_steps.py`

**Interfaces:**
- Consumes: `wikitext.templates/links/strip_markup/sections`; locations index (Task 9 output shape: dict name -> `{"x","y","plane"}`); `quest_requirements.json` `chunks` list for chunk-centre fallback; overrides dict `quest_step_overrides.json` shape.
- Produces:
  - `chunk_centre(region_id: int) -> dict` — `{"x": (rid>>8)*64+32, "y": (rid & 255)*64+32, "plane": 0}`.
  - `parse_steps(guide_wikitext: str) -> list[dict]` — ordered raw steps: `{"index", "text", "kind", "links": [...], "coords": {"x","y","plane"}|None, "items": [str]}`. A step is a top-level bullet (`*` but not `**`); sub-bullets (`**`) are appended to the previous step's text. `kind` from keywords: `Talk to` -> TALK, `Kill|Defeat|Fight` -> KILL, `Use|Search|Open|Pick|Mine|Cut|Dig` -> USE, `Travel|Go to|Teleport|Climb|Walk|Enter` -> TRAVEL, else OTHER. `coords` from the first `Map`/`Locline`/`Coord` template with numeric `x`/`y` (parameter names per NOTES.md).
  - `resolve_locations(steps, locations: dict, overrides: dict, quest_chunks: list[int]) -> tuple[list[dict], int]` — returns steps with `location` set per the spec ladder (MAP_TEMPLATE, NAMED_LINK, OVERRIDE, CARRIED, CHUNK_CENTRE) and the count of steps resolved only by CARRIED or CHUNK_CENTRE (approximate). Overrides win over everything; `quest_chunks` empty and nothing to carry yields `location: null`, `approximate: true`, source `NONE`.
  - `attach_steps(quest, guide_wikitext, locations, overrides, quest_chunks) -> int` — fills `quest["steps"]`, returns the approximate count; a `None`/empty guide leaves `steps == []` and returns 0 (counted by validation as "quest without steps").

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_steps.py
import unittest
from gps_process import build_steps as bs

GUIDE = """==Quick guide==
* Talk to [[Cook]] in [[Lumbridge Castle]] {{Map|x=3208|y=3214|plane=0}}
** He wants an egg.
* Pick up a [[Bucket]] and fill it at the [[Dairy cow]].
* Go to [[Mill Lane Mill]].
"""

LOCS = {"Dairy cow": {"x": 3253, "y": 3270, "plane": 0}, "Cook": {"x": 3000, "y": 3000, "plane": 0}}

class StepTests(unittest.TestCase):
    def test_parse_steps(self):
        s = bs.parse_steps(GUIDE)
        self.assertEqual(len(s), 3)
        self.assertEqual(s[0]["kind"], "TALK")
        self.assertEqual(s[0]["coords"], {"x": 3208, "y": 3214, "plane": 0})
        self.assertIn("He wants an egg", s[0]["text"])
        self.assertEqual(s[1]["links"], ["Bucket", "Dairy cow"])
        self.assertEqual(s[2]["kind"], "TRAVEL")

    def test_resolution_ladder(self):
        steps = bs.parse_steps(GUIDE)
        out, approx = bs.resolve_locations(steps, LOCS, {}, [12850])
        self.assertEqual(out[0]["location"]["source"], "MAP_TEMPLATE")       # template wins over link
        self.assertEqual((out[0]["location"]["x"], out[0]["location"]["y"]), (3208, 3214))
        self.assertEqual(out[1]["location"]["source"], "NAMED_LINK")         # Dairy cow resolved
        self.assertEqual(out[1]["location"]["x"], 3253)
        self.assertEqual(out[2]["location"]["source"], "CARRIED")            # Mill Lane Mill unknown
        self.assertTrue(out[2]["location"]["approximate"])
        self.assertEqual(approx, 1)

    def test_override_wins(self):
        steps = bs.parse_steps(GUIDE)
        out, _ = bs.resolve_locations(steps, LOCS, {"0": {"x": 1, "y": 2, "plane": 0}}, [])
        self.assertEqual(out[0]["location"]["source"], "OVERRIDE")
        self.assertEqual(out[0]["location"]["x"], 1)

    def test_first_step_unresolved_falls_back_to_chunk_centre(self):
        steps = bs.parse_steps("* Do a thing somewhere.\n")
        out, approx = bs.resolve_locations(steps, {}, {}, [12850])
        self.assertEqual(out[0]["location"]["source"], "CHUNK_CENTRE")
        self.assertEqual((out[0]["location"]["x"], out[0]["location"]["y"]), (50 * 64 + 32, 50 * 64 + 32))
        self.assertEqual(approx, 1)

    def test_nothing_known_is_none_not_crash(self):
        out, approx = bs.resolve_locations(bs.parse_steps("* Mystery.\n"), {}, {}, [])
        self.assertIsNone(out[0]["location"]["x"] if out[0]["location"] else None)
        self.assertEqual(out[0]["location"]["source"], "NONE")

    def test_missing_guide_is_not_a_crash(self):
        q = {"steps": []}
        self.assertEqual(bs.attach_steps(q, None, {}, {}, []), 0)
        self.assertEqual(q["steps"], [])

    def test_chunk_centre(self):
        self.assertEqual(bs.chunk_centre(12850), {"x": 50 * 64 + 32, "y": 50 * 64 + 32, "plane": 0})
```

(12850 = 0x3232 = 50<<8 | 50, so both coordinates are 50.)

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_steps -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_steps.py
import re
from . import wikitext as w

KIND_RULES = [("TALK", r"^\s*(talk|speak|ask)\b"), ("KILL", r"^\s*(kill|defeat|fight)\b"),
              ("TRAVEL", r"^\s*(travel|go to|teleport|climb|walk|enter|head)\b"),
              ("USE", r"^\s*(use|search|open|pick|mine|cut|dig|fill|read|take|buy|grab)\b")]
COORD_TEMPLATES = ["Map", "Locline", "Coord"]  # confirm names in NOTES.md after the probe

def chunk_centre(rid):
    return {"x": (rid >> 8) * 64 + 32, "y": (rid & 255) * 64 + 32, "plane": 0}

def _kind(text):
    for kind, rx in KIND_RULES:
        if re.search(rx, text, re.I):
            return kind
    return "OTHER"

def _coords(line):
    for name in COORD_TEMPLATES:
        for t in w.templates(line, name):
            if t.get("x", "").isdigit() and t.get("y", "").isdigit():
                return {"x": int(t["x"]), "y": int(t["y"]), "plane": int(t.get("plane", "0") or 0)}
    return None

def parse_steps(guide):
    steps = []
    for line in (guide or "").splitlines():
        if re.match(r"^\*\*", line) and steps:
            steps[-1]["text"] += " " + w.strip_markup(line.lstrip("* "))
            steps[-1]["links"] += w.links(line)
            if steps[-1]["coords"] is None:
                steps[-1]["coords"] = _coords(line)
        elif re.match(r"^\*[^*]", line):
            plain = w.strip_markup(line.lstrip("* "))
            steps.append({"index": len(steps), "text": plain, "kind": _kind(plain), "links": w.links(line),
                          "coords": _coords(line), "items": []})
    return steps

def resolve_locations(steps, locations, overrides, quest_chunks):
    approx, prev, out = 0, None, []
    for s in steps:
        loc = None
        ov = overrides.get(str(s["index"]))
        if ov:
            loc = {**ov, "approximate": False, "source": "OVERRIDE"}
        elif s["coords"]:
            loc = {**s["coords"], "approximate": False, "source": "MAP_TEMPLATE"}
        else:
            for name in s["links"]:
                if name in locations:
                    e = locations[name]
                    loc = {"x": e["x"], "y": e["y"], "plane": e.get("plane", 0),
                           "approximate": False, "source": "NAMED_LINK"}
                    break
        if loc is None and prev is not None:
            loc = {**{k: prev[k] for k in ("x", "y", "plane")}, "approximate": True, "source": "CARRIED"}
        if loc is None and quest_chunks:
            loc = {**chunk_centre(quest_chunks[0]), "approximate": True, "source": "CHUNK_CENTRE"}
        if loc is None:
            loc = {"x": None, "y": None, "plane": 0, "approximate": True, "source": "NONE"}
        if loc["approximate"]:
            approx += 1
        prev = loc if loc["x"] is not None else prev
        out.append({**s, "location": loc})
    return out, approx

def attach_steps(quest, guide, locations, overrides, quest_chunks):
    if not guide:
        return 0
    raw = parse_steps(guide)
    resolved, approx = resolve_locations(raw, locations, overrides, quest_chunks)
    quest["steps"] = [{"index": s["index"], "text": s["text"], "kind": s["kind"],
                       "location": s["location"], "items": s["items"]} for s in resolved]
    return approx
```

Note on `test_resolution_ladder`: step 1's link "Bucket" is not in LOCS, "Dairy cow" is, so NAMED_LINK; step 2 ("Mill Lane Mill") is unknown so it carries step 1's location. A step with `source: NONE` has `x: None`; Task 13's Java DTO must treat null `x` as "no location".

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_steps -v` — Expected: PASS. Then check against one real cached quick guide (see NOTES.md for its real bullet/template style) and add a test fixture shaped like it if the format differs.

No commit (local-only).

### Task 9: Build `locations.json`

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_locations.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_build_locations.py`

**Interfaces:**
- Consumes: cache pages for group `locations` and Bucket rows if NOTES.md identified a coordinate-bearing bucket; `wikitext.templates`.
- Produces:
  - `locations_from_pages(pages: dict[str, str]) -> list[dict]` — for every page with a coordinate template (`COORD_TEMPLATES` from `build_steps`, named args `x`,`y`,`plane`), one entry `{name: <page title>, kind, ids: [], x, y, plane}`; `kind` is `BANK` if the title contains "bank", `SHOP` if it contains "shop"/"store", else `PLACE`/`NPC` by `Infobox NPC` presence.
  - `merge_overrides(entries: list[dict], overrides: dict) -> list[dict]` — `location_overrides.json` shape; override replaces coords of an existing entry or adds a `PLACE`.
  - `build_locations(pages, overrides) -> dict` — the `locations.json` object.

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_locations.py
import unittest
from gps_process.build_locations import locations_from_pages, merge_overrides, build_locations

class LocTests(unittest.TestCase):
    def test_entry_from_map_template(self):
        pages = {"Varrock West Bank": "{{Infobox Bank}} {{Map|x=3185|y=3436|plane=0}}"}
        e = locations_from_pages(pages)[0]
        self.assertEqual((e["name"], e["kind"], e["x"], e["y"], e["plane"]), ("Varrock West Bank", "BANK", 3185, 3436, 0))

    def test_page_without_coords_skipped(self):
        self.assertEqual(locations_from_pages({"Nothing": "text"}), [])

    def test_override_replaces_and_adds(self):
        entries = [{"name": "Cook", "kind": "NPC", "ids": [], "x": 1, "y": 1, "plane": 0}]
        out = merge_overrides(entries, {"Cook": {"x": 5, "y": 6, "plane": 0}, "Lumbridge": {"x": 9, "y": 9, "plane": 0}})
        by = {e["name"]: e for e in out}
        self.assertEqual((by["Cook"]["x"], by["Cook"]["y"]), (5, 6))
        self.assertEqual(by["Lumbridge"]["kind"], "PLACE")

    def test_build_has_schema(self):
        self.assertEqual(build_locations({}, {})["schema"], 1)
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_locations -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_locations.py
from . import wikitext as w
from .build_steps import COORD_TEMPLATES

def _kind(title, text):
    t = title.lower()
    if "bank" in t: return "BANK"
    if "shop" in t or "store" in t: return "SHOP"
    if "{{infobox npc" in text.lower(): return "NPC"
    return "PLACE"

def locations_from_pages(pages):
    out = []
    for title, text in pages.items():
        if not text:
            continue
        for name in COORD_TEMPLATES:
            hit = next((t for t in w.templates(text, name)
                        if t.get("x", "").isdigit() and t.get("y", "").isdigit()), None)
            if hit:
                out.append({"name": title, "kind": _kind(title, text), "ids": [],
                            "x": int(hit["x"]), "y": int(hit["y"]), "plane": int(hit.get("plane", "0") or 0)})
                break
    return out

def merge_overrides(entries, overrides):
    by = {e["name"]: dict(e) for e in entries}
    for name, o in overrides.items():
        if name in by:
            by[name].update({"x": o["x"], "y": o["y"], "plane": o.get("plane", 0)})
        else:
            by[name] = {"name": name, "kind": "PLACE", "ids": [], "x": o["x"], "y": o["y"], "plane": o.get("plane", 0)}
    return list(by.values())

def build_locations(pages, overrides):
    return {"schema": 1, "entries": merge_overrides(locations_from_pages(pages), overrides)}
```

- [ ] **Step 4: Run tests** — PASS. `NAMED_LINK` resolution only hits if the NPC/object/place pages the quick guides link to are in the raw cache; Task 12 emits that list of link targets (`link_targets.txt`) for the fetch agent to crawl. Note that need in `gps_process/NOTES.md`.

No commit (local-only).

### Task 10: Build `transport.json` (seed + parsed tables)

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_transport.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_build_transport.py`

**Interfaces:**
- Consumes: `wikitext.tables`; locations index (name -> tile); `transport_seed.json` (same shape as `transport.json`).
- Produces:
  - `fairy_ring_edges(page_wikitext: str, locations: dict) -> list[dict]` — from the code table (columns recognised by header names containing "code" and "location"); each destination ring yields an edge `{"id": "fairy_ring_<code lower>", "kind": "FAIRY_RING", "name": "Fairy ring <CODE>", "from": null, "to": <tile>, "ticks": 8, "requires": {"skills":{},"quests":["FAIRYTALE_II__CURE_A_QUEEN"],"items":[]}, "consumesItem": false}` only when the destination name resolves in `locations` (unresolved codes are counted, not emitted). Fairy ring `from: null` is an approximation recorded in NOTES.md (any ring to any ring; the travel model in M3 treats `FAIRY_RING` kind specially as "must start at a ring").
  - `merge_seed(parsed: list[dict], seed: list[dict]) -> list[dict]` — seed wins on equal `id`.
  - `build_transport(pages: dict[str,str], locations: dict, seed: list[dict]) -> dict`.

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_transport.py
import unittest
from gps_process.build_transport import fairy_ring_edges, merge_seed, build_transport

PAGE = "{|\n! Code !! Location\n|-\n| AIQ || Asgarnian Ice Dungeon\n|-\n| ZZZ || Nowhere Known\n|}"
LOCS = {"Asgarnian Ice Dungeon": {"x": 3010, "y": 9560, "plane": 0}}

class TransportTests(unittest.TestCase):
    def test_fairy_ring_edges_only_resolved(self):
        edges = fairy_ring_edges(PAGE, LOCS)
        self.assertEqual([e["id"] for e in edges], ["fairy_ring_aiq"])
        self.assertEqual(edges[0]["to"], {"x": 3010, "y": 9560, "plane": 0})
        self.assertEqual(edges[0]["kind"], "FAIRY_RING")

    def test_seed_wins(self):
        parsed = [{"id": "a", "ticks": 10}]
        seed = [{"id": "a", "ticks": 3}, {"id": "b", "ticks": 4}]
        out = merge_seed(parsed, seed)
        self.assertEqual({e["id"]: e["ticks"] for e in out}, {"a": 3, "b": 4})

    def test_build_has_schema_and_handles_missing_pages(self):
        out = build_transport({"Fairy ring": None}, LOCS, [])
        self.assertEqual(out, {"schema": 1, "edges": []})
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_transport -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_transport.py
from . import wikitext as w

def fairy_ring_edges(page, locations):
    edges = []
    for table in w.tables(page or ""):
        for row in table:
            code = next((v for k, v in row.items() if "code" in k.lower()), "").strip()
            dest = next((v for k, v in row.items() if "location" in k.lower()), "").strip()
            if len(code) == 3 and dest in locations:
                t = locations[dest]
                edges.append({"id": "fairy_ring_" + code.lower(), "kind": "FAIRY_RING", "name": "Fairy ring " + code.upper(),
                              "from": None, "to": {"x": t["x"], "y": t["y"], "plane": t.get("plane", 0)},
                              "ticks": 8, "requires": {"skills": {}, "quests": ["FAIRYTALE_II__CURE_A_QUEEN"], "items": []},
                              "consumesItem": False})
    return edges

def merge_seed(parsed, seed):
    by = {e["id"]: e for e in parsed}
    for e in seed:
        by[e["id"]] = e
    return list(by.values())

def build_transport(pages, locations, seed):
    parsed = fairy_ring_edges(pages.get("Fairy ring"), locations)
    return {"schema": 1, "edges": merge_seed(parsed, seed)}
```

Teleport spells/items, spirit trees, gliders and charter ships come from the local hand-written `overrides/transport_seed.json` (in the data dir). Seed these during this task from the transport pages the crawl fetched (read them, type the edges by hand): at minimum the standard-spellbook teleports (Varrock, Lumbridge, Falador, Camelot, Ardougne, Watchtower), Lumbridge Home Teleport, the Ring of dueling/Games necklace/Amulet of glory item teleports with `consumesItem: true`, spirit tree network, gnome gliders, and every charter port that exists as a `NuzlockeChunk.TravelConnection` (M3 builds charter edges from the plugin's own chunk data, so these need no scraping). Use realistic tick costs (spell: 5 ticks cast + 3 animation = 8; spirit tree 6; glider 15).

- [ ] **Step 4: Run tests** — PASS.

No commit (local-only).

### Task 11: Build `training_methods.json`

**Files:**
- Create: `tools/wiki-scraper/gps_process/build_training.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_build_training.py`

**Interfaces:**
- Consumes: `wikitext.tables/strip_markup/SKILLS`; pages group `training`.
- Produces:
  - `parse_level_range(s: str) -> tuple[int, int] | None` — `"1-15"`, `"15 - 30"`, `"70+"` (max 99) -> ints.
  - `parse_xp_hr(s: str) -> int | None` — `"30,000"`, `"~45k"`, `"20k-30k"` (midpoint) -> int.
  - `methods_from_page(skill_enum: str, page: str) -> list[dict]` — from every table with columns whose headers contain "level" and "method" or "activity"; `approximate: true`; `location: null` (locations are attached by `attach_locations` when the method name contains a resolvable place link, else stay null).
  - `build_training(pages: dict[str, str]) -> dict`.

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_training.py
import unittest
from gps_process.build_training import parse_level_range, parse_xp_hr, methods_from_page, build_training

class TrainingTests(unittest.TestCase):
    def test_level_range(self):
        self.assertEqual(parse_level_range("1-15"), (1, 15))
        self.assertEqual(parse_level_range("15 - 30"), (15, 30))
        self.assertEqual(parse_level_range("70+"), (70, 99))
        self.assertIsNone(parse_level_range("n/a"))

    def test_xp_hr(self):
        self.assertEqual(parse_xp_hr("30,000"), 30000)
        self.assertEqual(parse_xp_hr("~45k"), 45000)
        self.assertEqual(parse_xp_hr("20k-30k"), 25000)
        self.assertIsNone(parse_xp_hr("varies"))

    def test_methods_from_table(self):
        page = "{|\n! Levels !! Method !! Experience per hour\n|-\n| 1-15 || Chop trees || 8,000\n|-\n| bad || x || y\n|}"
        ms = methods_from_page("WOODCUTTING", page)
        self.assertEqual(len(ms), 1)
        m = ms[0]
        self.assertEqual((m["skill"], m["name"], m["minLevel"], m["maxLevel"], m["xpPerHour"]), ("WOODCUTTING", "Chop trees", 1, 15, 8000))
        self.assertTrue(m["approximate"])

    def test_missing_page_ok(self):
        self.assertEqual(build_training({"Woodcutting training": None}), {"schema": 1, "methods": []})
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_build_training -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_process/build_training.py
import re
from . import wikitext as w

def parse_level_range(s):
    m = re.match(r"\s*(\d{1,2})\s*[-\u2013]\s*(\d{1,2})", s or "")
    if m: return int(m.group(1)), int(m.group(2))
    m = re.match(r"\s*(\d{1,2})\s*\+", s or "")
    if m: return int(m.group(1)), 99
    return None

def _k(tok):
    tok = tok.replace(",", "").replace("~", "").strip().lower()
    mult = 1000 if tok.endswith("k") else 1
    tok = tok.rstrip("k")
    try:
        return int(float(tok) * mult)
    except ValueError:
        return None

def parse_xp_hr(s):
    parts = re.split(r"\s*[-\u2013]\s*", (s or "").strip())
    vals = [v for v in (_k(p) for p in parts) if v is not None]
    if not vals: return None
    return sum(vals[:2]) // len(vals[:2])

def methods_from_page(skill, page):
    out = []
    for table in w.tables(page or ""):
        for row in table:
            lvl = next((v for k, v in row.items() if "level" in k.lower()), None)
            name = next((v for k, v in row.items() if "method" in k.lower() or "activity" in k.lower()), None)
            xp = next((v for k, v in row.items() if "experience" in k.lower() or "xp" in k.lower()), "")
            rng = parse_level_range(lvl)
            if rng and name:
                out.append({"skill": skill, "name": name, "minLevel": rng[0], "maxLevel": rng[1],
                            "xpPerHour": parse_xp_hr(xp), "location": None, "items": [], "approximate": True})
    return out

def build_training(pages):
    methods = []
    for title, text in pages.items():
        skill = w.SKILLS.get(title.replace(" training", ""))
        if skill and text:
            methods += methods_from_page(skill, text)
    return {"schema": 1, "methods": methods}
```

- [ ] **Step 4: Run tests** — PASS.

No commit (local-only).

### Task 12: Wire `build`, validation report, `data_version.json`

**Files:**
- Modify: `tools/wiki-scraper/gps_process/build.py` (call every builder)
- Create: `tools/wiki-scraper/gps_process/validate.py`
- Test: `tools/wiki-scraper/gps_process/tests/test_validate.py`

- Consumes: all `build_*` functions and `RawCache`.
- Consumes: all `build_*` functions and `load_bucket_rows`.
- Produces:
  - `build.build_all(args)` now also reads cached pages (`RawCache.pages()`), `quest_requirements.json` (path `<repo>/src/main/resources/com/chunkblazer/quest_requirements.json`, found relative to the script dir), overrides from `<out>/overrides/*.json` (missing = `{}`), builds locations first, then quests+steps, transport, training, writes `quests.json`, `transport.json`, `locations.json`, `training_methods.json`, and `data_version.json`.
  - `validate.report(out_dir: str) -> dict` — `{"files_missing": [...], "counts": {...}, "quests_without_steps": [...], "unresolved_quest_steps": int, "approximate_quest_steps": int, "monsters_without_ids": int}`.
  - `link_targets(cache) -> list[str]` (see below): quick-guide link targets not yet in the raw cache, written to `link_targets.txt` for the fetch agent.
  - `validate.run(args) -> int` prints the report; exit 1 if any core file (`monsters.json`, `items.json`, `quests.json`, `transport.json`, `locations.json`) is missing or `quests_without_steps` is larger than 25 percent of quests.

- [ ] **Step 1: Failing tests**

```python
# tests/test_validate.py
import json, os, tempfile, unittest
from gps_process import validate

def write(d, name, obj):
    with open(os.path.join(d, name), "w", encoding="utf-8") as fh: json.dump(obj, fh)

class ValidateTests(unittest.TestCase):
    def test_reports_missing_and_counts(self):
        with tempfile.TemporaryDirectory() as d:
            write(d, "quests.json", {"schema": 1, "quests": [
                {"id": "A", "steps": [{"location": {"approximate": True, "source": "CARRIED"}},
                                      {"location": {"approximate": False, "source": "MAP_TEMPLATE"}}]},
                {"id": "B", "steps": []}]})
            r = validate.report(d)
            self.assertIn("monsters.json", r["files_missing"])
            self.assertEqual(r["quests_without_steps"], ["B"])
            self.assertEqual(r["approximate_quest_steps"], 1)
            self.assertEqual(r["counts"]["quests"], 2)

    def test_empty_dir_does_not_crash(self):
        with tempfile.TemporaryDirectory() as d:
            r = validate.report(d)
            self.assertEqual(len(r["files_missing"]), 5)

    def test_invalid_json_counts_as_missing(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "items.json"), "w") as fh: fh.write("{not json")
            self.assertIn("items.json", validate.report(d)["files_missing"])
```

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest gps_process.tests.test_validate -v` — Expected: ImportError.

- [ ] **Step 3: Implement `validate.py`, extend `build_all`, add `add_guide_links`**

```python
# gps_process/validate.py
import json, os

CORE = ["monsters.json", "items.json", "quests.json", "transport.json", "locations.json"]

def _load(d, name):
    try:
        with open(os.path.join(d, name), encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None

def report(out_dir):
    missing, counts = [], {}
    for name in CORE:
        obj = _load(out_dir, name)
        if obj is None:
            missing.append(name); continue
        key = {"monsters.json": "monsters", "items.json": "items", "quests.json": "quests",
               "transport.json": "edges", "locations.json": "entries"}[name]
        counts[{"edges": "transport", "entries": "locations"}.get(key, key)] = len(obj.get(key, []))
    quests = (_load(out_dir, "quests.json") or {}).get("quests", [])
    no_steps = [q["id"] for q in quests if not q.get("steps")]
    steps = [s for q in quests for s in q.get("steps", [])]
    approx = sum(1 for s in steps if (s.get("location") or {}).get("approximate"))
    unresolved = sum(1 for s in steps if (s.get("location") or {}).get("source") == "NONE")
    mons = (_load(out_dir, "monsters.json") or {}).get("monsters", [])
    return {"files_missing": missing, "counts": counts, "quests_without_steps": no_steps,
            "approximate_quest_steps": approx, "unresolved_quest_steps": unresolved,
            "monsters_without_ids": sum(1 for m in mons if not m.get("ids"))}

def run(args):
    r = report(args.out)
    print(json.dumps(r, indent=1))
    quests = r["counts"].get("quests", 0)
    bad = bool(r["files_missing"]) or (quests and len(r["quests_without_steps"]) > quests * 0.25)
    return 1 if bad else 0
```

In `build.py`, extend `build_all` (after the existing monsters/items lines) with: `pages = cache.pages()`; load `location_overrides`, `step_overrides`, `transport_seed` from `<out>/overrides/` (missing = empty); `locs = build_locations(pages, location_overrides)`; `loc_index = {e["name"]: e for e in locs["entries"]}`; quest titles = every page title `T` such that `T + "/Quick guide"` is in `pages` or `T` appears in `quest_requirements.json` by `quest_enum_id`; for each: `q = build_quest(title, pages.get(title) or "", fallback)` with `fallback = quest_requirements.get(quest_enum_id(title))`, then `attach_steps(q, pages.get(title + "/Quick guide"), loc_index, step_overrides.get(q["id"], {}), (fallback or {}).get("chunks", []))`; write `quests.json`, `transport.json` (`build_transport(pages, loc_index, seed)`), `locations.json`, `training_methods.json`; finally write `data_version.json` with `data_version = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())`, the counts, and `unresolved_quest_steps` from `validate.report`. Keep each writer through `write_json`. Also write `link_targets.txt` into the raw-cache parent's `gps_process/` working dir (not into the data dir): `link_targets(cache) -> list[str]`, one title per line, for the fetch agent:

```python
def link_targets(cache):
    from . import wikitext as w
    have = set(cache.titles())
    out = []
    for title, text in cache.pages().items():
        if text and title.endswith("/Quick guide"):
            for l in w.links(text):
                if l not in have and l not in out:
                    out.append(l)
    return out
```

(`link_targets` has its own test in `test_validate.py`: a quick-guide fixture linking to `Cook` and to an already-cached page returns only `Cook`, once.) Hand `link_targets.txt` to the fetch agent as an extra page group to crawl (bulk, 20 per request, rate limit as usual); after it finishes, re-run `build`.

- [ ] **Step 4: Run all tests, then the real build**

Run: `cd tools/wiki-scraper && python -m unittest discover -s gps_process/tests -t . -v` — Expected: PASS.
When the fetch agent reports the crawl finished (confirm quest pages, quick guides and linked pages are in the raw cache): run `python process.py build && python process.py validate`, hand `link_targets.txt` to the fetch agent for a short second crawl, then `build` again.
Expected: files appear under `~/.runelite/chunkblazer/gps-data/`, `validate` prints counts and exits 0. Review the `quests_without_steps` and `unresolved_quest_steps` numbers; fix the parsers (with a new failing test per fix) or add `overrides/quest_step_overrides.json` entries until they are acceptable. Confirm with `git status` that nothing from `tools/` or the data dir shows up.

No commit (local-only).

### Task 13: Java DTOs and `GpsDataStore` (graceful degradation)

**Files:**
- Create: `src/main/java/com/chunkblazer/gps/data/Tile.java`, `Requirements.java`, `MonsterDef.java`, `ItemDef.java`, `QuestDef.java`, `QuestStep.java`, `TransportEdge.java`, `LocationEntry.java`, `TrainingMethod.java`, `GpsData.java`, `GpsDataStatus.java`, `GpsDataStore.java`
- Test: `src/test/java/com/chunkblazer/gps/data/GpsDataStoreTest.java`
- Fixtures: `src/test/resources/com/chunkblazer/gps/data/full/{monsters,items,quests,transport,locations,training_methods,data_version}.json` (synthetic, hand-written, tiny), `.../invalid/items.json` (not JSON), `.../empty/` (no files)

**Interfaces:**
- Produces (package `com.chunkblazer.gps.data`, all Lombok `@Value` immutables built by Gson through `@Value`-compatible fields; use plain classes with public final-style getters via `@Getter` if Gson reflective construction needs a no-arg constructor — Gson uses `Unsafe` and works with `@Value`):
  - `Tile { Integer x; Integer y; int plane; boolean approximate; String source; boolean isKnown() }`
  - `Requirements { Map<String,Integer> skills; List<String> quests; int questPoints; int combatLevel; List<ItemReq> items }`, nested `ItemReq { String name; List<Integer> ids; int qty }`; all collections default to empty when absent (getter returns `Collections.emptyMap()/emptyList()` for null).
  - `MonsterDef { List<Integer> ids; String name; int hp; int combatLevel; int maxHit; List<String> attackStyles; int defenceLevel; Map<String,Integer> defence }`
  - `ItemDef { int id; String name; boolean members; String slot; Requirements requirements; List<Source> sources; Teleport teleport }`, nested `Source { String type; String shop; Integer price; String npc; String skill; Integer level; String quest }`, `Teleport { String destination; int charges }`
  - `QuestStep { int index; String text; String kind; Tile location; List<ItemReq> items }`
  - `QuestDef { String id; String name; Requirements requirements; Rewards rewards; List<QuestStep> steps }`, nested `Rewards { Map<String,Integer> xp; List<ChoiceXp> choiceXp; int questPoints; List<Unlock> unlocks }`, `ChoiceXp { int amount; int count; List<String> skills }`, `Unlock { String type; String name }`
  - `TransportEdge { String id; String kind; String name; Tile from; Tile to; int ticks; Requirements requires; boolean consumesItem }`
  - `LocationEntry { String name; String kind; List<Integer> ids; int x; int y; int plane }`
  - `TrainingMethod { String skill; String name; int minLevel; int maxLevel; Integer xpPerHour; Tile location; List<ItemReq> items; boolean approximate }`
  - `GpsData { List<MonsterDef> monsters; List<ItemDef> items; List<QuestDef> quests; List<TransportEdge> transport; List<LocationEntry> locations; List<TrainingMethod> training; String dataVersion; GpsDataStatus status; static GpsData empty(GpsDataStatus) }` — all lists never null.
  - `GpsDataStatus { Path dir; boolean directoryFound; List<String> missingFiles; List<String> invalidFiles; boolean isComplete(); String describe() }` (`describe()` is a human string such as `"GPS data not found at <dir>. Run the scraper (see docs)."` or `"GPS data loaded (version 2026-10-14). Missing: transport.json"`).
  - `GpsDataStore { static Path defaultDir(); static GpsData load(Path dir) }` — never throws; `defaultDir()` is `new File(RuneLite.RUNELITE_DIR, "chunkblazer/gps-data").toPath()`. Each file parsed independently with its own try/catch (`JsonParseException`, `IOException`, `ClassCastException`).

- [ ] **Step 1: Write the fixtures and the failing test**

`full/monsters.json`: `{"schema":1,"monsters":[{"ids":[1],"name":"Test cow","hp":8,"combatLevel":2,"maxHit":1,"attackStyles":["crush"],"defenceLevel":2,"defence":{"stab":0,"slash":0,"crush":0,"magic":-21,"ranged":0}}]}`
`full/items.json`: `{"schema":1,"items":[{"id":1291,"name":"Test sword","members":false,"slot":"weapon","requirements":{"skills":{"ATTACK":1},"quests":[]},"sources":[{"type":"SHOP","shop":"Test shop","price":26}],"teleport":null}]}`
`full/quests.json`: one quest `TEST_QUEST` with requirements `{"skills":{"COOKING":10},"quests":[],"questPoints":0,"combatLevel":0,"items":[{"name":"Egg","ids":[1944],"qty":1}]}`, rewards `{"xp":{"COOKING":300},"choiceXp":[],"questPoints":1,"unlocks":[]}`, steps: index 0 location `{"x":3208,"y":3214,"plane":0,"approximate":false,"source":"MAP_TEMPLATE"}`, index 1 location `{"x":null,"y":null,"plane":0,"approximate":true,"source":"NONE"}`.
`full/transport.json`: one edge `test_tele` kind `SPELL`, `from:null`, `to:{"x":3213,"y":3424,"plane":0}`, `ticks:8`, `requires:{"skills":{"MAGIC":25},"quests":[],"items":[{"ids":[554],"qty":1}]}`, `consumesItem:false`.
`full/locations.json`: `{"schema":1,"entries":[{"name":"Cook","kind":"NPC","ids":[4626],"x":3208,"y":3214,"plane":0}]}`
`full/training_methods.json`: `{"schema":1,"methods":[{"skill":"COOKING","name":"Cook shrimps","minLevel":1,"maxLevel":30,"xpPerHour":30000,"location":null,"items":[],"approximate":true}]}`
`full/data_version.json`: `{"schema":1,"data_version":"test-1","generated_at":"x","counts":{}}`
`invalid/items.json`: `{not json`

```java
package com.chunkblazer.gps.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpsDataStoreTest
{
	private static Path fixture(String name) throws Exception
	{
		return Paths.get(GpsDataStoreTest.class.getResource("/com/chunkblazer/gps/data/" + name).toURI());
	}

	@Test
	void loadsAllFilesFromFullFixture() throws Exception
	{
		GpsData d = GpsDataStore.load(fixture("full"));
		assertTrue(d.getStatus().isComplete());
		assertEquals("test-1", d.getDataVersion());
		assertEquals(8, d.getMonsters().get(0).getHp());
		assertEquals("TEST_QUEST", d.getQuests().get(0).getId());
		assertEquals(Integer.valueOf(10), d.getQuests().get(0).getRequirements().getSkills().get("COOKING"));
		assertEquals(300, d.getQuests().get(0).getRewards().getXp().get("COOKING"));
		assertEquals("SPELL", d.getTransport().get(0).getKind());
		assertNull(d.getTransport().get(0).getFrom());
		assertEquals(3208, d.getLocations().get(0).getX());
		assertEquals(1, d.getTraining().size());
	}

	@Test
	void stepWithoutLocationIsNotKnown() throws Exception
	{
		GpsData d = GpsDataStore.load(fixture("full"));
		assertTrue(d.getQuests().get(0).getSteps().get(0).getLocation().isKnown());
		assertFalse(d.getQuests().get(0).getSteps().get(1).getLocation().isKnown());
	}

	@Test
	void missingDirectoryReturnsEmptyDataAndStatusNeverThrows(@TempDir Path tmp)
	{
		GpsData d = GpsDataStore.load(tmp.resolve("does-not-exist"));
		assertFalse(d.getStatus().isDirectoryFound());
		assertTrue(d.getMonsters().isEmpty());
		assertTrue(d.getQuests().isEmpty());
		assertTrue(d.getStatus().describe().contains("not found"));
	}

	@Test
	void emptyDirectoryListsEveryFileMissing(@TempDir Path tmp)
	{
		GpsData d = GpsDataStore.load(tmp);
		assertTrue(d.getStatus().isDirectoryFound());
		assertEquals(6, d.getStatus().getMissingFiles().size());
		assertFalse(d.getStatus().isComplete());
	}

	@Test
	void invalidJsonFileIsReportedOthersStillLoad(@TempDir Path tmp) throws Exception
	{
		Files.copy(fixture("full/monsters.json"), tmp.resolve("monsters.json"));
		Files.copy(fixture("invalid/items.json"), tmp.resolve("items.json"));
		GpsData d = GpsDataStore.load(tmp);
		assertEquals(1, d.getMonsters().size());
		assertTrue(d.getItems().isEmpty());
		assertTrue(d.getStatus().getInvalidFiles().contains("items.json"));
	}

	@Test
	void emptyFileIsInvalidNotCrash(@TempDir Path tmp) throws Exception
	{
		Files.write(tmp.resolve("quests.json"), new byte[0]);
		GpsData d = GpsDataStore.load(tmp);
		assertTrue(d.getQuests().isEmpty());
		assertTrue(d.getStatus().getInvalidFiles().contains("quests.json"));
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests com.chunkblazer.gps.data.GpsDataStoreTest`
Expected: compile errors (classes missing).

- [ ] **Step 3: Implement DTOs and store**

DTO pattern (repeat for each class in the interface list; use the file license header found at the top of `src/main/java/com/chunkblazer/QuestRequirements.java`, tabs for indentation, braces on their own line like the existing code):

```java
package com.chunkblazer.gps.data;

import lombok.Value;

@Value
public class Tile
{
	Integer x;
	Integer y;
	int plane;
	boolean approximate;
	String source;

	public boolean isKnown()
	{
		return x != null && y != null;
	}
}
```

Collection-bearing DTOs null-guard in getters, e.g.:

```java
@Value
public class Requirements
{
	Map<String, Integer> skills;
	List<String> quests;
	int questPoints;
	int combatLevel;
	List<ItemReq> items;

	public Map<String, Integer> getSkills() { return skills == null ? Collections.emptyMap() : skills; }
	public List<String> getQuests() { return quests == null ? Collections.emptyList() : quests; }
	public List<ItemReq> getItems() { return items == null ? Collections.emptyList() : items; }

	@Value
	public static class ItemReq
	{
		String name;
		List<Integer> ids;
		int qty;
	}
}
```

(Explicit getters override Lombok's generated ones, which Lombok allows.) `GpsDataStatus`:

```java
@Value
public class GpsDataStatus
{
	Path dir;
	boolean directoryFound;
	List<String> missingFiles;
	List<String> invalidFiles;

	public boolean isComplete()
	{
		return directoryFound && missingFiles.isEmpty() && invalidFiles.isEmpty();
	}

	public String describe()
	{
		if (!directoryFound)
		{
			return "GPS data not found at " + dir + ". Run the scraper (see docs/superpowers/plans).";
		}
		if (isComplete())
		{
			return "GPS data loaded.";
		}
		return "GPS data partial. Missing: " + missingFiles + " Invalid: " + invalidFiles;
	}
}
```

`GpsDataStore`:

```java
public final class GpsDataStore
{
	private static final Gson GSON = new Gson();
	private static final String[] FILES = {"monsters.json", "items.json", "quests.json", "transport.json", "locations.json", "training_methods.json"};

	private GpsDataStore() {}

	public static Path defaultDir()
	{
		return new File(RuneLite.RUNELITE_DIR, "chunkblazer/gps-data").toPath();
	}

	public static GpsData load(Path dir)
	{
		List<String> missing = new ArrayList<>();
		List<String> invalid = new ArrayList<>();
		if (dir == null || !Files.isDirectory(dir))
		{
			return GpsData.empty(new GpsDataStatus(dir, false, new ArrayList<>(Arrays.asList(FILES)), invalid));
		}
		List<MonsterDef> monsters = read(dir, "monsters.json", "monsters", new TypeToken<List<MonsterDef>>() {}, missing, invalid);
		List<ItemDef> items = read(dir, "items.json", "items", new TypeToken<List<ItemDef>>() {}, missing, invalid);
		List<QuestDef> quests = read(dir, "quests.json", "quests", new TypeToken<List<QuestDef>>() {}, missing, invalid);
		List<TransportEdge> transport = read(dir, "transport.json", "edges", new TypeToken<List<TransportEdge>>() {}, missing, invalid);
		List<LocationEntry> locations = read(dir, "locations.json", "entries", new TypeToken<List<LocationEntry>>() {}, missing, invalid);
		List<TrainingMethod> training = read(dir, "training_methods.json", "methods", new TypeToken<List<TrainingMethod>>() {}, missing, invalid);
		String version = readVersion(dir);
		return new GpsData(monsters, items, quests, transport, locations, training, version,
			new GpsDataStatus(dir, true, missing, invalid));
	}

	private static <T> List<T> read(Path dir, String file, String key, TypeToken<List<T>> type,
		List<String> missing, List<String> invalid)
	{
		Path p = dir.resolve(file);
		if (!Files.isRegularFile(p)) { missing.add(file); return new ArrayList<>(); }
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8))
		{
			JsonElement root = JsonParser.parseReader(r);
			if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has(key))
			{
				invalid.add(file); return new ArrayList<>();
			}
			List<T> out = GSON.fromJson(root.getAsJsonObject().get(key), type.getType());
			return out == null ? new ArrayList<>() : out;
		}
		catch (IOException | JsonParseException | ClassCastException | IllegalStateException e)
		{
			invalid.add(file); return new ArrayList<>();
		}
	}
}
```

Implement `readVersion(Path dir)` (reads `data_version.json` -> `data_version` string, returns `null` on any failure, does NOT add to missing/invalid since it is informational). `GpsData` is `@Value` with the lists defaulting to empty through `empty(status)`. Note `JsonParser.parseReader` on an empty file throws `JsonSyntaxException` (a `JsonParseException`) in modern Gson, which is the "empty file" test; if the Gson version bundled returns `JsonNull` instead, the `root == null || !root.isJsonObject()` guard covers it.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.chunkblazer.gps.data.GpsDataStoreTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/gps src/test/java/com/chunkblazer/gps src/test/resources/com/chunkblazer/gps
git commit -m "feat(gps): data DTOs and GpsDataStore with graceful degradation

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

### Task 14: `gpsDataDir` config and data-status resolution

**Files:**
- Modify: `src/main/java/com/chunkblazer/ChunkBlazerConfig.java` (add one string item)
- Create: `src/main/java/com/chunkblazer/gps/data/GpsDataLocator.java`
- Test: `src/test/java/com/chunkblazer/gps/data/GpsDataLocatorTest.java`

**Interfaces:**
- Consumes: `GpsDataStore.defaultDir()`, `GpsDataStore.load(Path)`.
- Produces: `GpsDataLocator.resolveDir(String configured) -> Path` (blank/whitespace -> `defaultDir()`, else `Paths.get(configured.trim())` with a leading `~` expanded to `user.home`); `GpsDataLocator.load(String configured) -> GpsData`. `ChunkBlazerConfig#gpsDataDir() -> String` (default `""`, under a hidden or advanced section consistent with neighbouring items). No plugin wiring yet beyond the config item (M2/M5 consume the locator).

- [ ] **Step 1: Failing test**

```java
package com.chunkblazer.gps.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class GpsDataLocatorTest
{
	@Test
	void blankUsesDefault()
	{
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir(""));
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir("   "));
		assertEquals(GpsDataStore.defaultDir(), GpsDataLocator.resolveDir(null));
	}

	@Test
	void explicitPathIsUsedAndTildeExpanded()
	{
		assertEquals(Paths.get("D:/x/gps"), GpsDataLocator.resolveDir(" D:/x/gps "));
		assertEquals(Paths.get(System.getProperty("user.home"), "gps"), GpsDataLocator.resolveDir("~/gps"));
	}

	@Test
	void loadOfMissingDirDoesNotThrow()
	{
		assertFalse(GpsDataLocator.load("Z:/definitely/not/here").getStatus().isDirectoryFound());
	}
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests com.chunkblazer.gps.data.GpsDataLocatorTest` — Expected: compile error.

- [ ] **Step 3: Implement**

```java
package com.chunkblazer.gps.data;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class GpsDataLocator
{
	private GpsDataLocator() {}

	public static Path resolveDir(String configured)
	{
		if (configured == null || configured.trim().isEmpty())
		{
			return GpsDataStore.defaultDir();
		}
		String c = configured.trim();
		if (c.equals("~") || c.startsWith("~/") || c.startsWith("~\\"))
		{
			return Paths.get(System.getProperty("user.home"), c.length() > 2 ? c.substring(2) : "");
		}
		return Paths.get(c);
	}

	public static GpsData load(String configured)
	{
		return GpsDataStore.load(resolveDir(configured));
	}
}
```

In `ChunkBlazerConfig.java`, add next to the existing items (open the file first and copy the annotation style/section/position conventions used by a neighbouring `String` item):

```java
@ConfigItem(keyName = "gpsDataDir", name = "GPS data folder", description = "Local folder with Progression GPS data. Blank uses ~/.runelite/chunkblazer/gps-data.", position = 900)
default String gpsDataDir()
{
	return "";
}
```

- [ ] **Step 4: Run tests and the full suite**

Run: `./gradlew test`
Expected: PASS (existing tests unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chunkblazer/ChunkBlazerConfig.java src/main/java/com/chunkblazer/gps/data/GpsDataLocator.java src/test/java/com/chunkblazer/gps/data/GpsDataLocatorTest.java
git commit -m "feat(gps): gpsDataDir config and GpsDataLocator

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_012VxiTXtLm6RdwSLxuoXrKC"
```

## Self-Review

- Spec coverage: section 4 (local-only, outputs, overrides; the cache, rate limit, UA and bulk rules are enforced by the existing fetch layer) -> Tasks 1-12; 5.1 degradation -> Tasks 13-14; 5.2 quest steps + location ladder -> Tasks 7-8 (+9 locations, 12 link-target list); items/monsters/transport/training -> Tasks 6, 10, 11. Quest-step data for interleaving is produced here; the planner consumes it in M4.
- Early crawl: already satisfied by the separately owned fetch layer; this plan only reads its cache (Task 1) and asks it for any missing pages.
- Known soft spots, deliberately handled by in-plan verification instead of guessing: real Bucket field names, coordinate template names, quest reward/requirement markup, and fairy-ring table format are confirmed with the fetch agent and by inspecting the real cache in Task 1, and recorded in gps_process/NOTES.md; Tasks 6-11 each end by checking one real cached page and adding a fixture-shaped test if the format differs.
