# Progression GPS M1: Local Data Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local-only wiki scraper (fetch, persistent cache, strict rate limit, descriptive User-Agent) that can start crawling early, then transforms producing the processed JSON the plugin loads at runtime, plus the Java loader that degrades gracefully when data is absent.

**Architecture:** A stdlib-only Python package in `tools/wiki-scraper/` (never committed) crawls bulk wiki queries into a persistent per-page raw cache, then pure transform functions turn the cache into JSON files written to `~/.runelite/chunkblazer/gps-data/`. The plugin side adds `com.chunkblazer.gps.data` (DTOs + `GpsDataStore`) that reads that directory with Gson and never throws.

**Tech Stack:** Python 3.12 (stdlib: urllib, json, argparse, unittest), Java 11 release, Gson, Lombok, JUnit 5 (all already present; no new dependencies).

**Spec:** `docs/superpowers/specs/2026-10-14-progression-gps-design.md` (sections 4, 5.1, 5.2).

## Global Constraints

- Nothing wiki-derived is ever committed or published: the scraper script, `tools/wiki-scraper/cache/`, and the processed output stay off GitHub. Exclusion is via `.git/info/exclude`, NOT the committed `.gitignore`.
- Each wiki page is fetched exactly once (persistent raw cache); re-fetch only with `--refresh <page>`.
- Strict self rate limit: one request every 5-10 s (6-12/min); exponential backoff on 429/5xx honouring `Retry-After`; descriptive User-Agent (tool name + contact), never a default agent. Prefer bulk queries (up to 20 titles per `action=query`, `action=bucket`, prices `/mapping`).
- Processed output default: `~/.runelite/chunkblazer/gps-data/`; plugin config `gpsDataDir` overrides (blank = default). Nothing in `src/main/resources`.
- Java: release 11, Gson + Lombok only, no new dependencies, logic classes have no `Client` dependency. Java tests use synthetic hand-written fixtures in `src/test/resources/com/chunkblazer/gps/`, never real wiki data.
- Do not edit the committed `.gitignore`. Never `git add` anything under `tools/`.
- The plans and spec are committed; the scraper is not. Commit steps below that touch only Java/docs use normal `git add` of explicit paths; scraper tasks have NO commit step (the tool is local-only) and instead end with the test run.
- Contact for the User-Agent comes from env var `GPS_SCRAPER_CONTACT` (an email or URL); the scraper refuses to run without it and has no default.

## Review Focus

- Crawl killed mid-write (power loss, Ctrl-C): cache files must never be half-written, and a re-run must resume. Tested in Task 2 (atomic write + corrupt-file recovery).
- HTTP 429 with a `Retry-After` header: the wait must honour it, not the default backoff. Tested in Task 2.
- `GPS_SCRAPER_CONTACT` unset: the CLI must refuse to fetch rather than send a generic User-Agent. Tested in Task 3.
- A wiki page that does not exist (title guessed wrong) or has no recognisable sections: transforms must skip it, count it in the validation report, and never crash. Tested in Tasks 8 and 12.
- `gps-data` directory missing, a file empty, or a file containing invalid JSON: the Java loader must return partial data plus a status and never throw. Tested in Task 13.

## File Structure

Local only (excluded from git):
- `tools/wiki-scraper/gps_scraper/__init__.py`
- `tools/wiki-scraper/gps_scraper/ratelimit.py` — `RateLimiter`
- `tools/wiki-scraper/gps_scraper/cache.py` — `Cache` (atomic, per-key files)
- `tools/wiki-scraper/gps_scraper/fetcher.py` — `Fetcher` (UA, backoff, cache-through)
- `tools/wiki-scraper/gps_scraper/wiki.py` — bulk API helpers (`pages_wikitext`, `bucket`, `mapping`, `category_members`)
- `tools/wiki-scraper/gps_scraper/manifest.py` — discovery + `manifest.json`
- `tools/wiki-scraper/gps_scraper/wikitext.py` — shared wikitext helpers (strip markup, links, templates, tables)
- `tools/wiki-scraper/gps_scraper/build_*.py` — one transform per output (`items`, `monsters`, `quests`, `locations`, `transport`, `training`)
- `tools/wiki-scraper/gps_scraper/validate.py` — validation report + `data_version.json`
- `tools/wiki-scraper/gps_scraper/cli.py`, `tools/wiki-scraper/scraper.py` (entry point)
- `tools/wiki-scraper/tests/` — unittest tests and `fixtures/` (tiny hand-written samples)
- `tools/wiki-scraper/NOTES.md` — probe findings (real field names, bucket names)

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

Order matters: Tasks 1-4 make the scraper usable and START THE CRAWL; Tasks 5-12 are transforms written while the crawl runs; Tasks 13-14 are the Java side and are independent of the crawl, so they can run in parallel with it.

### Task 1: Local-only scaffolding

**Files:**
- Modify: `.git/info/exclude` (local, not committed)
- Create: `tools/wiki-scraper/gps_scraper/__init__.py` (empty), `tools/wiki-scraper/tests/__init__.py` (empty)

**Interfaces:**
- Produces: package `gps_scraper` importable when cwd is `tools/wiki-scraper`; tests run with `python -m unittest discover -s tests -t .` from that directory.

- [ ] **Step 1: Add the local exclusion (idempotent)**

```bash
cd /d/Github/chunkblazer
grep -qx 'tools/wiki-scraper/' .git/info/exclude || printf 'tools/wiki-scraper/\n' >> .git/info/exclude
grep -qx 'gps-data/' .git/info/exclude || printf 'gps-data/\n' >> .git/info/exclude
mkdir -p tools/wiki-scraper/gps_scraper tools/wiki-scraper/tests/fixtures
: > tools/wiki-scraper/gps_scraper/__init__.py
: > tools/wiki-scraper/tests/__init__.py
```

- [ ] **Step 2: Verify git cannot see the tool and the committed .gitignore is untouched**

Run: `git status --short; git check-ignore -v tools/wiki-scraper/gps_scraper/__init__.py; git diff --stat -- .gitignore`
Expected: `git status` shows no `tools/` entries; `check-ignore` prints a rule (either `.git/info/exclude` or the old `tools/` rule); `.gitignore` diff is empty.

- [ ] **Step 3: Write and run a trivial test proving the harness works**

Create `tools/wiki-scraper/tests/test_smoke.py`:

```python
import unittest

class Smoke(unittest.TestCase):
    def test_import(self):
        import gps_scraper
        self.assertIsNotNone(gps_scraper)
```

Run: `cd tools/wiki-scraper && python -m unittest discover -s tests -t . -v`
Expected: `OK` (1 test).

No commit (local-only).

### Task 2: Rate limiter, atomic cache, fetcher (usable scraper core)

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/ratelimit.py`, `cache.py`, `fetcher.py`
- Test: `tools/wiki-scraper/tests/test_fetcher.py`

**Interfaces:**
- Produces:
  - `RateLimiter(min_s=5.0, max_s=10.0, clock=time.monotonic, sleep=time.sleep, rng=random.Random())` with `wait() -> float` (sleeps so consecutive requests are at least a random 5-10 s apart, returns seconds slept).
  - `Cache(root: str)` with `get(ns: str, key: str) -> dict | None`, `put(ns: str, key: str, record: dict) -> None` (atomic), `has(ns, key) -> bool`. A record is a plain dict, stored as `<root>/<ns>/<sha1(key)>.json` containing `{"key": key, "record": record}`.
  - `Fetcher(cache, limiter, contact, transport=default_transport, sleep=time.sleep)` with `get(url: str, params: dict, ns: str, cache_key: str, refresh=False) -> str` (cache-through; returns body text) and `user_agent` property. `Transport = Callable[[str, dict], tuple[int, dict, bytes]]` taking `(url, headers)`; returns `(status, headers_lowercased, body)`.
  - `FetchError(Exception)` on non-retryable failure or retries exhausted.

- [ ] **Step 1: Write the failing tests**

```python
# tests/test_fetcher.py
import json, os, tempfile, unittest
from gps_scraper.cache import Cache
from gps_scraper.fetcher import Fetcher, FetchError
from gps_scraper.ratelimit import RateLimiter

class FakeClock:
    def __init__(self): self.t = 0.0; self.slept = []
    def now(self): return self.t
    def sleep(self, s): self.slept.append(s); self.t += s

def make(transport, tmp):
    clk = FakeClock()
    lim = RateLimiter(5, 10, clock=clk.now, sleep=clk.sleep, rng=__import__("random").Random(1))
    f = Fetcher(Cache(tmp), lim, "me@example.com", transport=transport, sleep=clk.sleep)
    return f, clk

class FetcherTests(unittest.TestCase):
    def test_user_agent_is_descriptive_and_has_contact(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, _ = make(lambda u, h: (200, {}, b"x"), tmp)
            self.assertIn("ChunkBlazerGPS", f.user_agent)
            self.assertIn("me@example.com", f.user_agent)

    def test_each_key_fetched_once(self):
        calls = []
        def tr(url, headers): calls.append((url, headers)); return 200, {}, b"hello"
        with tempfile.TemporaryDirectory() as tmp:
            f, _ = make(tr, tmp)
            self.assertEqual(f.get("http://x/api", {"a": "1"}, "page", "A"), "hello")
            self.assertEqual(f.get("http://x/api", {"a": "1"}, "page", "A"), "hello")
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][1]["User-Agent"], f.user_agent)
            f.get("http://x/api", {"a": "1"}, "page", "A", refresh=True)
            self.assertEqual(len(calls), 2)

    def test_rate_limit_spacing_between_requests(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, clk = make(lambda u, h: (200, {}, b"x"), tmp)
            f.get("http://x", {}, "p", "1"); f.get("http://x", {}, "p", "2"); f.get("http://x", {}, "p", "3")
            gaps = [s for s in clk.slept if s > 0]
            self.assertEqual(len(gaps), 2)  # first request does not wait
            for g in gaps: self.assertTrue(5 <= g <= 10, g)

    def test_backoff_on_5xx_then_success(self):
        seq = iter([(503, {}, b""), (502, {}, b""), (200, {}, b"ok")])
        with tempfile.TemporaryDirectory() as tmp:
            f, clk = make(lambda u, h: next(seq), tmp)
            self.assertEqual(f.get("http://x", {}, "p", "1"), "ok")
            backoffs = [s for s in clk.slept if s >= 30]
            self.assertEqual(len(backoffs), 2)
            self.assertGreater(backoffs[1], backoffs[0])  # exponential

    def test_429_retry_after_is_honoured(self):
        seq = iter([(429, {"retry-after": "120"}, b""), (200, {}, b"ok")])
        with tempfile.TemporaryDirectory() as tmp:
            f, clk = make(lambda u, h: next(seq), tmp)
            f.get("http://x", {}, "p", "1")
            self.assertIn(120, clk.slept)

    def test_gives_up_after_max_attempts_and_does_not_cache_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, _ = make(lambda u, h: (500, {}, b""), tmp)
            with self.assertRaises(FetchError):
                f.get("http://x", {}, "p", "1")
            self.assertFalse(Cache(tmp).has("p", "1"))

    def test_404_is_not_retried_and_not_cached(self):
        n = []
        def tr(u, h): n.append(1); return 404, {}, b"nope"
        with tempfile.TemporaryDirectory() as tmp:
            f, _ = make(tr, tmp)
            with self.assertRaises(FetchError):
                f.get("http://x", {}, "p", "1")
            self.assertEqual(len(n), 1)

class CacheTests(unittest.TestCase):
    def test_roundtrip_and_atomic_no_tmp_left(self):
        with tempfile.TemporaryDirectory() as tmp:
            c = Cache(tmp)
            c.put("page", "Cook's Assistant", {"body": "x"})
            self.assertEqual(c.get("page", "Cook's Assistant"), {"body": "x"})
            leftovers = [n for d, _, fs in os.walk(tmp) for n in fs if n.endswith(".tmp")]
            self.assertEqual(leftovers, [])

    def test_corrupt_file_is_treated_as_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            c = Cache(tmp)
            c.put("page", "A", {"body": "x"})
            path = c._path("page", "A")
            with open(path, "w") as fh: fh.write('{"key": "A", "rec')  # truncated write
            self.assertIsNone(c.get("page", "A"))
            self.assertFalse(c.has("page", "A"))
```

- [ ] **Step 2: Run to verify failure**

Run: `cd tools/wiki-scraper && python -m unittest tests.test_fetcher -v`
Expected: ImportError (modules missing).

- [ ] **Step 3: Implement**

```python
# gps_scraper/ratelimit.py
import random, time

class RateLimiter:
    """Guarantees a random 5-10 s gap between request starts. First call does not wait."""
    def __init__(self, min_s=5.0, max_s=10.0, clock=time.monotonic, sleep=time.sleep, rng=None):
        self.min_s, self.max_s = min_s, max_s
        self.clock, self.sleep = clock, sleep
        self.rng = rng or random.Random()
        self._last = None

    def wait(self):
        slept = 0.0
        if self._last is not None:
            gap = self.rng.uniform(self.min_s, self.max_s)
            remaining = gap - (self.clock() - self._last)
            if remaining > 0:
                self.sleep(remaining)
                slept = remaining
        self._last = self.clock()
        return slept
```

```python
# gps_scraper/cache.py
import hashlib, json, os

class Cache:
    def __init__(self, root):
        self.root = root

    def _path(self, ns, key):
        h = hashlib.sha1(key.encode("utf-8")).hexdigest()
        return os.path.join(self.root, ns, h + ".json")

    def get(self, ns, key):
        try:
            with open(self._path(ns, key), "r", encoding="utf-8") as fh:
                obj = json.load(fh)
            if obj.get("key") != key:
                return None
            return obj["record"]
        except (OSError, ValueError, KeyError):
            return None

    def has(self, ns, key):
        return self.get(ns, key) is not None

    def put(self, ns, key, record):
        path = self._path(ns, key)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump({"key": key, "record": record}, fh, ensure_ascii=False)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)

    def keys(self, ns):
        d = os.path.join(self.root, ns)
        if not os.path.isdir(d):
            return
        for name in sorted(os.listdir(d)):
            if name.endswith(".json"):
                try:
                    with open(os.path.join(d, name), "r", encoding="utf-8") as fh:
                        obj = json.load(fh)
                    yield obj["key"], obj["record"]
                except (OSError, ValueError, KeyError):
                    continue
```

```python
# gps_scraper/fetcher.py
import time, urllib.error, urllib.parse, urllib.request

class FetchError(Exception):
    pass

def default_transport(url, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read() or b""
    except (urllib.error.URLError, TimeoutError) as e:
        return 599, {}, str(e).encode("utf-8")

class Fetcher:
    MAX_ATTEMPTS = 6
    BASE_BACKOFF = 30
    MAX_BACKOFF = 900

    def __init__(self, cache, limiter, contact, transport=default_transport, sleep=time.sleep):
        self.cache, self.limiter, self.contact = cache, limiter, contact
        self.transport, self.sleep = transport, sleep

    @property
    def user_agent(self):
        return f"ChunkBlazerGPS-personal-scraper/0.1 (personal offline use, 6-12 req/min, cached; contact: {self.contact})"

    def get(self, url, params, ns, cache_key, refresh=False):
        if not refresh:
            hit = self.cache.get(ns, cache_key)
            if hit is not None:
                return hit["body"]
        full = url + ("?" + urllib.parse.urlencode(params) if params else "")
        headers = {"User-Agent": self.user_agent, "Accept-Encoding": "identity"}
        for attempt in range(self.MAX_ATTEMPTS):
            self.limiter.wait()
            status, rh, body = self.transport(full, headers)
            if status == 200:
                text = body.decode("utf-8")
                self.cache.put(ns, cache_key, {"body": text, "url": full, "fetched_at": time.time()})
                return text
            if status == 429 or status >= 500:
                if "retry-after" in rh:
                    try:
                        delay = int(float(rh["retry-after"]))
                    except ValueError:
                        delay = self.BASE_BACKOFF
                else:
                    delay = min(self.BASE_BACKOFF * (2 ** attempt), self.MAX_BACKOFF)
                self.sleep(delay)
                continue
            raise FetchError(f"HTTP {status} for {full}")
        raise FetchError(f"gave up after {self.MAX_ATTEMPTS} attempts: {full}")
```

Note: in `test_backoff_on_5xx_then_success` the delays are 30 then 60 (both >= 30, increasing); in the 429 test the sleep list contains exactly `120`.

- [ ] **Step 4: Run tests**

Run: `cd tools/wiki-scraper && python -m unittest tests.test_fetcher -v`
Expected: all PASS.

No commit (local-only).

### Task 3: Bulk wiki API helpers and CLI skeleton (scraper usable)

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/wiki.py`, `cli.py`, `tools/wiki-scraper/scraper.py`
- Test: `tools/wiki-scraper/tests/test_wiki.py`, `tests/test_cli.py`

**Interfaces:**
- Consumes: `Fetcher.get`, `Cache`.
- Produces (`wiki.py`):
  - `API = "https://oldschool.runescape.wiki/api.php"`, `MAPPING = "https://prices.runescape.wiki/api/v1/osrs/mapping"`.
  - `pages_wikitext(fetcher, cache, titles: list[str], refresh=False, batch=20) -> dict[str, str | None]` — fetches only titles not already in cache namespace `page` (each title cached separately; missing pages cached as `{"missing": true}`), 20 titles per request, returns title -> wikitext or `None` if the page does not exist. Follows redirects (`redirects=1`) and maps results back to the requested titles.
  - `category_members(fetcher, category: str) -> list[str]` (follows `cmcontinue`, ns `cat`).
  - `bucket(fetcher, bucket_name: str, fields: list[str], where: str | None = None, page_size=500) -> list[dict]` (ns `bucket`, pages by offset until a short page; each offset page cached by key `f"{bucket_name}|{where}|{offset}"`).
  - `mapping(fetcher) -> list[dict]` (ns `mapping`, key `"mapping"`).
- Produces (`cli.py`): `main(argv) -> int` with subcommands `probe`, `plan`, `crawl`, `status`, `build`, `validate` (the last three added in later tasks; this task creates `probe`, `status` and the common option parsing). Common options: `--cache DIR` (default `tools/wiki-scraper/cache`), `--out DIR` (default `~/.runelite/chunkblazer/gps-data`), `--refresh PAGE` (repeatable).
- `get_fetcher(args, env) -> Fetcher` raises `SystemExit("GPS_SCRAPER_CONTACT is not set ...")` if the env var is empty.

- [ ] **Step 1: Write failing tests**

```python
# tests/test_wiki.py
import json, tempfile, unittest
from gps_scraper.cache import Cache
from gps_scraper.fetcher import Fetcher
from gps_scraper.ratelimit import RateLimiter
from gps_scraper import wiki

def mk(handler, tmp):
    lim = RateLimiter(0, 0, clock=lambda: 0.0, sleep=lambda s: None)
    return Fetcher(Cache(tmp), lim, "me@example.com", transport=handler, sleep=lambda s: None)

def query_response(pages, redirects=None, normalized=None):
    q = {"pages": pages}
    if redirects: q["redirects"] = redirects
    if normalized: q["normalized"] = normalized
    return json.dumps({"query": q}).encode()

class WikiTests(unittest.TestCase):
    def test_pages_batched_cached_and_missing_recorded(self):
        urls = []
        def tr(url, h):
            urls.append(url)
            pages = [{"title": "A", "revisions": [{"slots": {"main": {"content": "wikitext A"}}}]},
                     {"title": "B", "missing": True}]
            return 200, {}, query_response(pages)
        with tempfile.TemporaryDirectory() as tmp:
            f = mk(tr, tmp)
            out = wiki.pages_wikitext(f, f.cache, ["A", "B"])
            self.assertEqual(out, {"A": "wikitext A", "B": None})
            out2 = wiki.pages_wikitext(f, f.cache, ["A", "B"])
            self.assertEqual(out2, out)
            self.assertEqual(len(urls), 1)  # second call fully served from cache
            self.assertIn("titles=A%7CB", urls[0])

    def test_batches_of_twenty(self):
        urls = []
        def tr(url, h):
            urls.append(url); return 200, {}, query_response([])
        with tempfile.TemporaryDirectory() as tmp:
            f = mk(tr, tmp)
            wiki.pages_wikitext(f, f.cache, [f"T{i}" for i in range(45)])
            self.assertEqual(len(urls), 3)

    def test_redirect_maps_back_to_requested_title(self):
        def tr(url, h):
            return 200, {}, query_response(
                [{"title": "Real", "revisions": [{"slots": {"main": {"content": "body"}}}]}],
                redirects=[{"from": "Alias", "to": "Real"}])
        with tempfile.TemporaryDirectory() as tmp:
            f = mk(tr, tmp)
            self.assertEqual(wiki.pages_wikitext(f, f.cache, ["Alias"]), {"Alias": "body"})

    def test_bucket_pages_until_short_page(self):
        calls = []
        def tr(url, h):
            calls.append(url)
            rows = [{"page_name": f"M{i}"} for i in range(2)] if len(calls) == 1 else [{"page_name": "M2"}]
            return 200, {}, json.dumps({"bucket": rows}).encode()
        with tempfile.TemporaryDirectory() as tmp:
            f = mk(tr, tmp)
            rows = wiki.bucket(f, "infobox_monster", ["page_name"], page_size=2)
            self.assertEqual([r["page_name"] for r in rows], ["M0", "M1", "M2"])
            self.assertEqual(len(calls), 2)
```

```python
# tests/test_cli.py
import unittest
from gps_scraper import cli

class CliTests(unittest.TestCase):
    def test_refuses_without_contact(self):
        with self.assertRaises(SystemExit) as cm:
            cli.get_fetcher(cli.parse(["probe"]), env={})
        self.assertIn("GPS_SCRAPER_CONTACT", str(cm.exception))

    def test_builds_fetcher_with_contact(self):
        f = cli.get_fetcher(cli.parse(["probe"]), env={"GPS_SCRAPER_CONTACT": "me@example.com"})
        self.assertIn("me@example.com", f.user_agent)
```

- [ ] **Step 2: Run to verify failure**

Run: `cd tools/wiki-scraper && python -m unittest tests.test_wiki tests.test_cli -v`
Expected: ImportError / failures.

- [ ] **Step 3: Implement `wiki.py`**

```python
# gps_scraper/wiki.py
import json

API = "https://oldschool.runescape.wiki/api.php"
MAPPING = "https://prices.runescape.wiki/api/v1/osrs/mapping"

def _chunks(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]

def pages_wikitext(fetcher, cache, titles, refresh=False, batch=20):
    out = {}
    todo = []
    for t in titles:
        hit = None if refresh else cache.get("page", t)
        if hit is None:
            todo.append(t)
        else:
            out[t] = None if hit.get("missing") else hit["wikitext"]
    for group in _chunks(todo, batch):
        params = {"action": "query", "format": "json", "formatversion": "2", "redirects": "1",
                  "prop": "revisions", "rvprop": "content", "rvslots": "main", "titles": "|".join(group)}
        body = fetcher.get(API, params, "req", "titles|" + "|".join(group), refresh=refresh)
        q = json.loads(body).get("query", {})
        alias = {}
        for n in q.get("normalized", []):
            alias[n["from"]] = n["to"]
        for r in q.get("redirects", []):
            alias[r["from"]] = r["to"]
        by_title = {}
        for p in q.get("pages", []):
            if p.get("missing") or "revisions" not in p:
                by_title[p["title"]] = None
            else:
                by_title[p["title"]] = p["revisions"][0]["slots"]["main"]["content"]
        for t in group:
            final = t
            for _ in range(3):  # follow normalized -> redirect chain
                final = alias.get(final, final)
            text = by_title.get(final)
            cache.put("page", t, {"missing": True} if text is None else {"wikitext": text, "resolved": final})
            out[t] = text
    return out

def category_members(fetcher, category):
    members, cont = [], None
    while True:
        params = {"action": "query", "format": "json", "list": "categorymembers", "cmtitle": category,
                  "cmlimit": "500"}
        if cont:
            params["cmcontinue"] = cont
        body = fetcher.get(API, params, "cat", f"{category}|{cont}")
        data = json.loads(body)
        members += [m["title"] for m in data["query"]["categorymembers"]]
        cont = data.get("continue", {}).get("cmcontinue")
        if not cont:
            return members

def bucket(fetcher, bucket_name, fields, where=None, page_size=500):
    rows, offset = [], 0
    sel = ",".join("'" + f + "'" for f in fields)
    while True:
        q = f"bucket('{bucket_name}').select({sel})"
        if where:
            q += f".where({where})"
        q += f".limit({page_size}).offset({offset}).run()"
        body = fetcher.get(API, {"action": "bucket", "format": "json", "query": q},
                           "bucket", f"{bucket_name}|{where}|{offset}")
        page = json.loads(body).get("bucket", [])
        rows += page
        if len(page) < page_size:
            return rows
        offset += page_size

def mapping(fetcher):
    return json.loads(fetcher.get(MAPPING, {}, "mapping", "mapping"))
```

- [ ] **Step 4: Implement `cli.py` and entry point**

```python
# gps_scraper/cli.py
import argparse, os, sys
from .cache import Cache
from .fetcher import Fetcher
from .ratelimit import RateLimiter

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CACHE = os.path.join(HERE, "cache")
DEFAULT_OUT = os.path.join(os.path.expanduser("~"), ".runelite", "chunkblazer", "gps-data")

def parse(argv):
    p = argparse.ArgumentParser(prog="scraper.py")
    p.add_argument("command", choices=["probe", "plan", "crawl", "status", "build", "validate"])
    p.add_argument("--cache", default=DEFAULT_CACHE)
    p.add_argument("--out", default=DEFAULT_OUT)
    p.add_argument("--refresh", action="append", default=[])
    p.add_argument("--only", default=None, help="crawl/build only this group")
    return p.parse_args(argv)

def get_fetcher(args, env=None):
    env = os.environ if env is None else env
    contact = (env.get("GPS_SCRAPER_CONTACT") or "").strip()
    if not contact:
        raise SystemExit("GPS_SCRAPER_CONTACT is not set. Set it to an email or URL identifying you; "
                         "the wiki requires a descriptive User-Agent with contact info.")
    return Fetcher(Cache(args.cache), RateLimiter(5, 10), contact)

def main(argv=None):
    args = parse(argv if argv is not None else sys.argv[1:])
    if args.command == "status":
        from .manifest import print_status
        return print_status(args)
    fetcher = get_fetcher(args)
    if args.command == "probe":
        from .manifest import probe
        return probe(fetcher, args)
    if args.command == "plan":
        from .manifest import plan
        return plan(fetcher, args)
    if args.command == "crawl":
        from .manifest import crawl
        return crawl(fetcher, args)
    if args.command == "build":
        from .build import build_all
        return build_all(args)
    from .validate import run as validate_run
    return validate_run(args)
```

```python
# scraper.py
import sys
from gps_scraper.cli import main
if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 5: Run tests**

Run: `cd tools/wiki-scraper && python -m unittest discover -s tests -t . -v`
Expected: all PASS.

No commit (local-only).

### Task 4: Probe, manifest, and START THE CRAWL

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/manifest.py`, `tools/wiki-scraper/NOTES.md`
- Test: `tools/wiki-scraper/tests/test_manifest.py`

**Interfaces:**
- Consumes: `wiki.pages_wikitext`, `wiki.category_members`, `wiki.bucket`, `wiki.mapping`.
- Produces (`manifest.py`):
  - `probe(fetcher, args) -> int`: makes the minimum number of real requests (about 6, all cached) to confirm the live API shapes and prints them; used once by hand.
  - `build_manifest(quest_titles: list[str], extra_pages: dict[str, list[str]]) -> dict` — pure: `{"groups": {"quests": [...], "quick_guides": [...], "transport": [...], "training": [...], "locations": [...]}, "buckets": [{"name": "infobox_monster", "fields": [...]}, ...], "mapping": true}`.
  - `plan(fetcher, args) -> int`: discovers quest titles via `category_members("Category:Quests")`, writes `<cache>/manifest.json`.
  - `crawl(fetcher, args) -> int`: reads `manifest.json`, fetches in priority order (mapping, buckets, quests, quick guides, transport pages, training pages, locations), resumable (cache-through), prints `done/total` and ETA, handles Ctrl-C cleanly (exit 130, cache intact).
  - `print_status(args) -> int`: counts cached entries per namespace and per manifest group, fetched vs outstanding, and the ETA at 8 s/request.
  - `QUICK_GUIDE_SUFFIX = "/Quick guide"`.

- [ ] **Step 1: Write failing tests for the pure part**

```python
# tests/test_manifest.py
import unittest
from gps_scraper import manifest

class ManifestTests(unittest.TestCase):
    def test_quick_guide_titles_derived_from_quests(self):
        m = manifest.build_manifest(["Cook's Assistant", "Dragon Slayer II"], {})
        self.assertEqual(m["groups"]["quests"], ["Cook's Assistant", "Dragon Slayer II"])
        self.assertEqual(m["groups"]["quick_guides"],
                         ["Cook's Assistant/Quick guide", "Dragon Slayer II/Quick guide"])

    def test_default_buckets_and_mapping_present(self):
        m = manifest.build_manifest([], {})
        names = [b["name"] for b in m["buckets"]]
        self.assertIn("infobox_monster", names)
        self.assertIn("storeline", names)
        self.assertTrue(m["mapping"])

    def test_extra_pages_deduplicated_and_ordered(self):
        m = manifest.build_manifest([], {"transport": ["Fairy ring", "Fairy ring", "Spirit tree"]})
        self.assertEqual(m["groups"]["transport"], ["Fairy ring", "Spirit tree"])

    def test_estimate_seconds(self):
        self.assertEqual(manifest.eta_seconds(requests_left=10, per_request=8), 80)
```

- [ ] **Step 2: Run to verify failure**

Run: `cd tools/wiki-scraper && python -m unittest tests.test_manifest -v` — Expected: ImportError.

- [ ] **Step 3: Implement `manifest.py`**

```python
# gps_scraper/manifest.py
import json, os, sys, time
from . import wiki

QUICK_GUIDE_SUFFIX = "/Quick guide"

DEFAULT_BUCKETS = [
    {"name": "infobox_monster", "fields": ["page_name", "name", "hitpoints", "combat_level", "max_hit",
                                            "attack_style", "defence_level", "stab_defence_bonus",
                                            "slash_defence_bonus", "crush_defence_bonus",
                                            "magic_defence_bonus", "ranged_defence_bonus", "id"]},
    {"name": "storeline", "fields": ["page_name", "sold_by", "sold_item", "store_stock",
                                      "store_sell_price"]},
    {"name": "infobox_item", "fields": ["page_name", "item_name", "item_id", "members", "weight"]},
    {"name": "infobox_bonuses", "fields": ["page_name", "slot", "equipment_slot"]},
    {"name": "dropsline", "fields": ["page_name", "item_name", "drop_json"]},
]
# NOTE: field names above are the best guess. Task 4 Step 4 (probe) confirms them against the live
# API and the corrected names are recorded in NOTES.md and fixed here before the crawl starts.

DEFAULT_PAGES = {
    "transport": ["Teleportation", "Teleportation/Spell", "Spirit tree", "Fairy ring", "Gnome glider",
                  "Charter ship", "Minigame Teleportation", "Magic carpet", "Canoe", "Ship", "Mine cart"],
    "training": [f"{s} training" for s in
                 ["Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic", "Runecraft", "Construction",
                  "Hitpoints", "Agility", "Herblore", "Thieving", "Crafting", "Fletching", "Slayer",
                  "Hunter", "Mining", "Smithing", "Fishing", "Cooking", "Firemaking", "Woodcutting",
                  "Farming"]],
    "locations": ["Bank", "Bank/Locations"],
}

def build_manifest(quest_titles, extra_pages):
    groups = {k: list(dict.fromkeys(v)) for k, v in DEFAULT_PAGES.items()}
    for k, v in extra_pages.items():
        groups[k] = list(dict.fromkeys(groups.get(k, []) + v))
    groups["quests"] = list(dict.fromkeys(quest_titles))
    groups["quick_guides"] = [t + QUICK_GUIDE_SUFFIX for t in groups["quests"]]
    return {"groups": groups, "buckets": DEFAULT_BUCKETS, "mapping": True}

def eta_seconds(requests_left, per_request=8):
    return requests_left * per_request

def _manifest_path(args):
    return os.path.join(args.cache, "manifest.json")

def probe(fetcher, args):
    print("1/6 mapping (first 2 rows)")
    print(json.dumps(wiki.mapping(fetcher)[:2], indent=1))
    print("2/6 bucket infobox_monster (limit 3, all fields)")
    rows = wiki.bucket(fetcher, "infobox_monster", ["page_name", "hitpoints"], page_size=3)
    print(json.dumps(rows[:3], indent=1))
    print("3/6 category Quests (first 5)")
    print(wiki.category_members(fetcher, "Category:Quests")[:5])
    print("4/6 quick guide sample")
    p = wiki.pages_wikitext(fetcher, fetcher.cache, ["Cook's Assistant/Quick guide"])
    print((p["Cook's Assistant/Quick guide"] or "MISSING")[:1500])
    print("5/6 quest page sample")
    p = wiki.pages_wikitext(fetcher, fetcher.cache, ["Cook's Assistant"])
    print((p["Cook's Assistant"] or "MISSING")[:2500])
    print("6/6 transport page sample")
    p = wiki.pages_wikitext(fetcher, fetcher.cache, ["Fairy ring"])
    print((p["Fairy ring"] or "MISSING")[:1500])
    print("\nRecord real field names / template names in tools/wiki-scraper/NOTES.md, then fix DEFAULT_BUCKETS.")
    return 0

def plan(fetcher, args):
    quests = wiki.category_members(fetcher, "Category:Quests")
    m = build_manifest(quests, {})
    with open(_manifest_path(args), "w", encoding="utf-8") as fh:
        json.dump(m, fh, indent=1)
    print(f"manifest: {len(quests)} quests, {sum(len(v) for v in m['groups'].values())} pages")
    return 0

def _outstanding(m, cache):
    todo = []
    if m["mapping"] and not cache.has("mapping", "mapping"):
        todo.append(("mapping", None))
    for b in m["buckets"]:
        if not cache.has("bucket", f"{b['name']}|None|0"):
            todo.append(("bucket", b))
    for group in ["quests", "quick_guides", "transport", "training", "locations"]:
        titles = [t for t in m["groups"].get(group, []) if not cache.has("page", t)]
        for i in range(0, len(titles), 20):
            todo.append(("pages", titles[i:i + 20]))
    return todo

def crawl(fetcher, args):
    with open(_manifest_path(args), encoding="utf-8") as fh:
        m = json.load(fh)
    todo = _outstanding(m, fetcher.cache)
    total = len(todo)
    print(f"{total} request units outstanding, ETA about {eta_seconds(total) // 60} min")
    try:
        for i, (kind, arg) in enumerate(todo, 1):
            if kind == "mapping":
                wiki.mapping(fetcher)
            elif kind == "bucket":
                wiki.bucket(fetcher, arg["name"], arg["fields"])
            else:
                wiki.pages_wikitext(fetcher, fetcher.cache, arg, refresh=any(t in args.refresh for t in arg))
            print(f"[{i}/{total}] {kind} done", flush=True)
    except KeyboardInterrupt:
        print("interrupted; cache is intact, re-run to resume")
        return 130
    return 0

def print_status(args):
    from .cache import Cache
    cache = Cache(args.cache)
    mp = _manifest_path(args)
    if not os.path.exists(mp):
        print("no manifest yet; run: python scraper.py plan")
        return 1
    with open(mp, encoding="utf-8") as fh:
        m = json.load(fh)
    todo = _outstanding(m, cache)
    print(f"outstanding request units: {len(todo)} (ETA about {eta_seconds(len(todo)) // 60} min)")
    for g, titles in m["groups"].items():
        have = sum(1 for t in titles if cache.has("page", t))
        print(f"  {g}: {have}/{len(titles)} pages cached")
    return 0
```

- [ ] **Step 4: Run unit tests, then the live probe (one-time, about 1 minute)**

Run: `cd tools/wiki-scraper && python -m unittest discover -s tests -t . -v` — Expected: PASS.
Run (contact is the maintainer's own address; do not hardcode it anywhere): `cd tools/wiki-scraper && GPS_SCRAPER_CONTACT=<your email> python scraper.py probe`
Expected: six sections print real data. Write what you see into `tools/wiki-scraper/NOTES.md`: (a) the actual Bucket names and field names for monsters/shops/items/drops, (b) how coordinates appear in quick guides and NPC pages (the template name and parameters, e.g. `{{Map|x=..|y=..}}` or `{{Locline|...}}`), (c) how the quest page lists rewards and requirements, (d) the fairy ring list format. If a bucket or field name differs, fix `DEFAULT_BUCKETS` before continuing. If a transport/training page title returned MISSING, replace it in `DEFAULT_PAGES` with the correct title (look it up through `action=query&list=search`, which is one extra cached request).

- [ ] **Step 5: Plan and START THE CRAWL (background, leave it running)**

Run: `cd tools/wiki-scraper && GPS_SCRAPER_CONTACT=<your email> python scraper.py plan && python scraper.py status`
Then start in the background: `cd tools/wiki-scraper && GPS_SCRAPER_CONTACT=<your email> python scraper.py crawl > crawl.log 2>&1` (use `run_in_background`).
Expected: status shows the outstanding units and an ETA of roughly 20-40 minutes (about 10 request units per 20 quick guides group times about 8 s). The crawl is resumable: if it stops, re-run the same command. Continue with Task 5 while it runs; check progress with `python scraper.py status`.

No commit (local-only).

### Task 5: Shared wikitext helpers

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/wikitext.py`
- Test: `tools/wiki-scraper/tests/test_wikitext.py`

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
from gps_scraper import wikitext as w

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

Run: `cd tools/wiki-scraper && python -m unittest tests.test_wikitext -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/wikitext.py
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

Run: `cd tools/wiki-scraper && python -m unittest tests.test_wikitext -v` — Expected: PASS. Fix any regex edge until green (the `templates` named-arg detection is the likeliest).

No commit (local-only).

### Task 6: Build `items.json` and `monsters.json`

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/build_items.py`, `build_monsters.py`, `tools/wiki-scraper/gps_scraper/build.py` (orchestrator, extended by later tasks)
- Test: `tools/wiki-scraper/tests/test_build_items_monsters.py`

**Interfaces:**
- Consumes: cache namespaces `mapping` (key `mapping`), `bucket` (keys `<name>|None|<offset>`); `Cache.get`.
- Produces:
  - `build_monsters(rows: list[dict]) -> dict` — pure, returns the `monsters.json` object (contract above). Bucket rows are the dicts from `wiki.bucket`; parsing uses the field names recorded in NOTES.md (default names from `DEFAULT_BUCKETS`). Rows with no numeric hitpoints are skipped. `id` may be a JSON-list string `"[2790,2791]"` or a single value.
  - `build_items(mapping_rows, shop_rows, bonus_rows, drop_rows) -> dict` — pure, returns the `items.json` object.
  - `load_bucket_rows(cache, name) -> list[dict]` — concatenates all cached offset pages `name|None|0`, `|500`, ... until one is missing.
  - `build.build_all(args) -> int` — loads the cache, calls every `build_*` that exists, writes outputs into `args.out` atomically, returns 0.

- [ ] **Step 1: Failing tests**

```python
# tests/test_build_items_monsters.py
import unittest
from gps_scraper.build_monsters import build_monsters
from gps_scraper.build_items import build_items

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_items_monsters -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_monsters.py
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
# gps_scraper/build_items.py
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
# gps_scraper/build.py
import json, os
from .cache import Cache

def load_bucket_rows(cache, name):
    rows, offset = [], 0
    while True:
        rec = cache.get("bucket", f"{name}|None|{offset}")
        if rec is None:
            return rows
        page = json.loads(rec["body"]).get("bucket", [])
        rows += page
        if len(page) < 500:
            return rows
        offset += 500

def write_json(out_dir, name, obj):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name)
    with open(path + ".tmp", "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
    os.replace(path + ".tmp", path)

def build_all(args):
    cache = Cache(args.cache)
    from .build_monsters import build_monsters
    from .build_items import build_items
    counts = {}
    rec = cache.get("mapping", "mapping")
    mapping_rows = json.loads(rec["body"]) if rec else []
    mon = build_monsters(load_bucket_rows(cache, "infobox_monster"))
    write_json(args.out, "monsters.json", mon); counts["monsters"] = len(mon["monsters"])
    items = build_items(mapping_rows, load_bucket_rows(cache, "storeline"),
                        load_bucket_rows(cache, "infobox_bonuses"), load_bucket_rows(cache, "dropsline"))
    write_json(args.out, "items.json", items); counts["items"] = len(items["items"])
    print("built", counts)
    return 0
```

Note: after the crawl, if the real bucket rows have a different shape than the tests (NOTES.md from Task 4), adjust the field lookups here and add a test row mirroring the real shape.

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest tests.test_build_items_monsters -v` — Expected: PASS.

No commit (local-only).

### Task 7: Build quest definitions (requirements, rewards)

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/build_quests.py`
- Test: `tools/wiki-scraper/tests/test_build_quests.py`, fixtures `tests/fixtures/cooks_assistant.wikitext`, `tests/fixtures/quest_requirements_sample.json`

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
from gps_scraper import build_quests as bq

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_quests -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_quests.py
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

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest tests.test_build_quests -v` — Expected: PASS. Then compare one real cached quest page (from `status`-confirmed cache) against NOTES.md; if the real Rewards/Requirements format differs, add a fixture line copied in shape (retyped, not pasted) and extend the regexes until the new test passes.

No commit (local-only).

### Task 8: Quest steps from quick guides, with location resolution

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/build_steps.py`
- Test: `tools/wiki-scraper/tests/test_build_steps.py`

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
from gps_scraper import build_steps as bs

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_steps -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_steps.py
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

- [ ] **Step 4: Run tests** — `cd tools/wiki-scraper && python -m unittest tests.test_build_steps -v` — Expected: PASS. Then check against one real cached quick guide (see NOTES.md for its real bullet/template style) and add a test fixture shaped like it if the format differs.

No commit (local-only).

### Task 9: Build `locations.json`

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/build_locations.py`
- Test: `tools/wiki-scraper/tests/test_build_locations.py`

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
from gps_scraper.build_locations import locations_from_pages, merge_overrides, build_locations

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_locations -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_locations.py
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

- [ ] **Step 4: Run tests** — PASS. The crawl must include NPC/object/place pages the quick guides link to for `NAMED_LINK` resolution to hit; Task 12 adds a second-phase `plan --from-guides` that appends every link target found in cached quick guides to the `locations` group (bulk, 20/request) so a second short crawl covers them. Note that need in NOTES.md.

No commit (local-only).

### Task 10: Build `transport.json` (seed + parsed tables)

**Files:**
- Create: `tools/wiki-scraper/gps_scraper/build_transport.py`
- Test: `tools/wiki-scraper/tests/test_build_transport.py`

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
from gps_scraper.build_transport import fairy_ring_edges, merge_seed, build_transport

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_transport -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_transport.py
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
- Create: `tools/wiki-scraper/gps_scraper/build_training.py`
- Test: `tools/wiki-scraper/tests/test_build_training.py`

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
from gps_scraper.build_training import parse_level_range, parse_xp_hr, methods_from_page, build_training

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_build_training -v` — Expected: ImportError.

- [ ] **Step 3: Implement**

```python
# gps_scraper/build_training.py
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
- Modify: `tools/wiki-scraper/gps_scraper/build.py` (call every builder), `cli.py` (already routes `validate`)
- Create: `tools/wiki-scraper/gps_scraper/validate.py`
- Test: `tools/wiki-scraper/tests/test_validate.py`

**Interfaces:**
- Consumes: all `build_*` functions and `load_bucket_rows`.
- Produces:
  - `build.build_all(args)` now also reads cached pages (`Cache.keys("page")` yields `(title, record)`), `quest_requirements.json` (path `<repo>/src/main/resources/com/chunkblazer/quest_requirements.json`, found relative to the script dir), overrides from `<out>/overrides/*.json` (missing = `{}`), builds locations first, then quests+steps, transport, training, writes `quests.json`, `transport.json`, `locations.json`, `training_methods.json`, and `data_version.json`.
  - `validate.report(out_dir: str) -> dict` — `{"files_missing": [...], "counts": {...}, "quests_without_steps": [...], "unresolved_quest_steps": int, "approximate_quest_steps": int, "monsters_without_ids": int}`.
  - `validate.run(args) -> int` prints the report; exit 1 if any core file (`monsters.json`, `items.json`, `quests.json`, `transport.json`, `locations.json`) is missing or `quests_without_steps` is larger than 25 percent of quests.
  - `plan --from-guides` option: appends every link target found in cached quick guides (not already cached) to the `locations` group in `manifest.json` (so one more short crawl fetches NPC/object pages); implement as `manifest.add_guide_links(cache, manifest) -> int`.

- [ ] **Step 1: Failing tests**

```python
# tests/test_validate.py
import json, os, tempfile, unittest
from gps_scraper import validate

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

- [ ] **Step 2: Run to verify failure** — `cd tools/wiki-scraper && python -m unittest tests.test_validate -v` — Expected: ImportError.

- [ ] **Step 3: Implement `validate.py`, extend `build_all`, add `add_guide_links`**

```python
# gps_scraper/validate.py
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

In `build.py`, extend `build_all` (after the existing monsters/items lines) with: read all cached pages into `pages = {title: rec.get("wikitext")}` via `cache.keys("page")`; load `locations_overrides`, `step_overrides`, `transport_seed` from `<out>/overrides/`; `locs = build_locations(pages_for_group, overrides)`; `loc_index = {e["name"]: e for e in locs["entries"]}`; for every title in the manifest `quests` group: `q = build_quest(title, pages.get(title) or "", fallback)` with `fallback = quest_requirements.get(quest_enum_id(title))`, `attach_steps(q, pages.get(title + "/Quick guide"), loc_index, step_overrides.get(q["id"], {}), (fallback or {}).get("chunks", []))`; write `quests.json`, `transport.json` (`build_transport(pages, loc_index, seed)`), `locations.json`, `training_methods.json`; finally write `data_version.json` with `data_version = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())`, the counts, and `unresolved_quest_steps` from `validate.report`. Keep each writer through `write_json`. Add to `manifest.py`:

```python
def add_guide_links(cache, m):
    from . import wikitext as w
    known = set(m["groups"].get("locations", []))
    new = []
    for title, rec in cache.keys("page"):
        if title.endswith(QUICK_GUIDE_SUFFIX) and rec.get("wikitext"):
            for l in w.links(rec["wikitext"]):
                if l not in known and not cache.has("page", l):
                    known.add(l); new.append(l)
    m["groups"]["locations"] = m["groups"].get("locations", []) + new
    return len(new)
```

and in `plan(...)`: if `--from-guides` (add `p.add_argument("--from-guides", action="store_true")` in `cli.parse`), load the existing manifest instead of discovering, call `add_guide_links`, rewrite it.

- [ ] **Step 4: Run all tests, then the real build**

Run: `cd tools/wiki-scraper && python -m unittest discover -s tests -t . -v` — Expected: PASS.
When `python scraper.py status` shows the first crawl finished: run `python scraper.py plan --from-guides`, then `crawl` again (short, covers linked NPC/object pages), then `python scraper.py build && python scraper.py validate`.
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

- Spec coverage: section 4 (local-only, cache, rate limit, UA, bulk, outputs, overrides) -> Tasks 1-12; 5.1 degradation -> Tasks 13-14; 5.2 quest steps + location ladder -> Tasks 7-8 (+9 locations, 12 `--from-guides`); items/monsters/transport/training -> Tasks 6, 10, 11. Quest-step data for interleaving is produced here; the planner consumes it in M4.
- Early crawl: Tasks 1-4 produce a runnable, rate-limited, cached scraper and start the crawl before any transform exists.
- Known soft spots, deliberately handled by in-plan verification instead of guessing: real Bucket field names, coordinate template names, quest reward/requirement markup, and fairy-ring table format are confirmed during the Task 4 probe and recorded in NOTES.md; Tasks 6-11 each end by checking one real cached page and adding a fixture-shaped test if the format differs.
