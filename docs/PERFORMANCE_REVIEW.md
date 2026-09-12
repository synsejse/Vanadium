# Performance and refactoring review — 2026-09-12

The first targets should be chunk-cache lookup and entity-section range queries.
Both appear in the live profile, and both offer improvements without redesigning the
threading model. Worker dispatch is a smaller, measurable opportunity. Item merging,
tracking maintenance, and collection ownership deserve separate targeted workloads.

The initial review below did not change production Java or the checked-in benchmarks.
The subsequent implementation of items 1–3 is recorded at the end of this document.
Initial experiments and raw evidence are in `.vanadium/review/`; the fresh server world is in
the directory recorded by `.vanadium/review/server-run.txt`.

## Measurements

Hardware: AMD Ryzen 5 9600X, 6 physical cores / 12 logical CPUs, x86_64 NixOS.
Java: locked OpenJDK 21.0.12.1. Minecraft 1.21.1, Fabric Loader 0.19.3,
Fabric API 0.116.12+1.21.1, C2ME 0.4.0-alpha.0.16, Vanadium 2.0.0.
Performance workloads ran sequentially, with no other benchmark running alongside them.

### Existing scheduler benchmark

Three fresh JVM runs of `./gradlew benchScheduler`, each with 2,000 warmup iterations,
5,000 measured iterations, 12 workers, cell size 4, 1,024 chunks, `SPIN=200`, and Parallel GC.
Each benchmark iteration exercises one CHUNK stage, not an entire Minecraft tick.

| Metric | Run 1 | Run 2 | Run 3 |
| --- | ---: | ---: | ---: |
| No-op stage, microseconds | 14.6 | 14.8 | 14.1 |
| Loaded stage, microseconds | 52.4 | 52.4 | 52.6 |
| No-op allocated bytes/stage | 4,972 | 4,969 | 4,954 |

### Distributed live-server workload

Created 256 persistent, invulnerable, AI-enabled zombies in 64 glass enclosures,
four zombies per chunk across an 8×8 chunk square. All 64 chunks were force-loaded.
Seed 12345, normal difficulty, fixed midnight, natural spawning disabled, no players.
Warmed for 15 seconds after setup. Default 12 workers and automatic cell size 3.
All Vanadium boolean options stayed enabled except the entity flag being compared.

Ran six 2,000-tick vanilla sprints in on/off/off/on/on/off order, saving and pausing
between runs. Flight Recorder's `profile` settings were active throughout the comparison.

| `parallelEntities` | Milliseconds per tick, chronological within mode | Median |
| --- | --- | ---: |
| Enabled | 1.04, 0.95, 0.95 | 0.95 |
| Disabled | 1.39, 1.38, 1.35 | 1.38 |

Parallel entity ticking reduced median tick time by about **31% in this workload**.
These are rounded vanilla sprint reports, not a multiplayer capacity estimate. The world
continues evolving between runs, and this is a single server process with mixed-order
measurements, not independent cloned-world trials. Disabling `parallelEntities` leaves
Vanadium's other behavior and synchronization installed; it is not a vanilla comparison.

The existing `scripts/bench-server.sh` workload puts every zombie within one chunk.
It is useful as a smoke/profile test, but initially schedules those entities in one cell
and does not provide a useful distributed entity-scaling baseline.

### Profile and focused cache experiment

The recording contains 1,458 execution samples and 5,705 allocation samples:

- `ParallelChunkManager.getChunk` appeared in 387 sampled stacks; `lookup` in 264.
- `McThreadTracker.isPooled` appeared in 159 sampled stacks.
- `CacheKey` was the largest weighted sampled allocation class. AVL tree entries used
  by copied sorted-set ranges were also among the largest.
- ConcurrentHashMap tree-bin lookup appeared directly beneath the chunk-cache lookup.
- Worker dispatch appeared much less often. This does not establish its exact share
  of elapsed time: stack depth and sampling limit attribution.
- No monitor-enter events were recorded at the profile's threshold. This does not
  rule out shorter contention, and the workload does not stress dropped-item merging.

A separate single-threaded experiment filled a `ConcurrentHashMap` with 1,024 chunk keys
covering X/Z -16 through 15 at one status. The existing `(long, int)` record hash yields
only 32 distinct hashes because the packed position's halves fold together. A temporary
record overriding `hashCode()` with `31 * Long.hashCode(HashCommon.mix(chunkPos)) + status`
yielded 1,024 distinct hashes for that dataset.

Three JVM forks per variant, each with five million warmup and five million measured
lookups, 256 MiB heap and Parallel GC:

| Key hashing | Nanoseconds/get |
| --- | --- |
| Existing record | 24.99, 24.67, 24.81 |
| Mixed position | 2.38, 2.39, 2.38 |

This is a narrow diagnostic microbenchmark with a small hot map, not JMH or a measured
server patch. It makes hashing the first low-complexity candidate to validate in game.

## Ranked changes

### 1. Improve the chunk-cache key and lookup path — strongest evidence

Location: `chunk/ParallelChunkManager.java`, especially `lookup()` and `CacheKey`.

Start with a documented hash override using the existing fastutil mixer. Keep the record
and its equality semantics. Then investigate the demonstrated lookup-key allocation,
with before/after allocation measurements; avoid introducing mutable shared lookup keys.
Extract a small `ChunkCache` only if it makes invalidation and lookup contracts clearer.

Also publish newly loaded entries while still holding the load lock: currently both
branches unlock before `chunkCache.put`, allowing another caller to miss before publication.
Do not retain entries containing `WeakReference(null)`. Replace the ten-second full cache
clear only after defining an unload/invalidation policy; removing expiry alone is unsafe.

Validation: hash distribution across signs/statuses, concurrent same-chunk lookup, unload
and reload, required-status handling, cache-disabled behavior, live profile comparison.

### 2. Stop rebuilding trees for entity-section range queries — profile-backed

Location: `concurrent/ConcurrentLongSortedSet.java:subSet/headSet/tailSet`.

Every range request constructs a new `LongAVLTreeSet` from a concurrent skip-list range.
That adds traversal, tree insertion, and an object per copied entry to entity queries.
Give range access one clear implementation: a lightweight view if callers permit weakly
consistent traversal, or a compact primitive snapshot if they require a fixed result.
Preserve the intended iteration semantics explicitly rather than silently changing them.

Validation: range endpoints, ordering, negative section positions, concurrent section
creation/removal, entity collision queries, and allocation profiles.

### 3. Make Minecraft-worker detection a cheap, specific operation — profile-backed

Location: `chunk/McThreadTracker.java`, `mixin/util/UtilMixin.java`.

Every chunk lookup checks a concurrent map of pool names and then a set of threads.
The sole production query asks about the Minecraft `Main` pool, and the registration
hook registers `ForkJoinWorkerThread` instances. Expose `isMinecraftMainWorker(Thread)`
and reject ordinary server/Vanadium threads before consulting the registry. If useful,
track pool identity instead of retaining every thread, after checking lifecycle assumptions.
This makes the API more descriptive and targets work actually present in the profile.

### 4. Simplify per-wave coordination — measurable, lower priority

Location: `tick/WorkerPool.java` and `tick/CellGrid.java`.

Each multi-task wave allocates a linked queue, queue nodes, counters, a latch, and helper
tasks. The scheduler already supplies stable array-backed color lists. Prototype a small
`Wave` object holding a task list, an atomic claim index, completion state, and failure.
That can remove queue-node allocation while keeping caller participation easy to read.
The current benchmark puts this in the tens of microseconds, so prioritize it after the
cache/range issues. Do not assume replacing one atomic algorithm with another is faster.

Fix interruption semantics alongside this work: `awaitLatch` currently restores the
interrupt and returns without ensuring all work has finished. Preserve a full barrier
and define cancellation behavior. Consider amortizing `CellGrid`'s full retained-cell
eviction scan only if sparse-world measurements justify changing its exact expiry policy.

### 5. Make item-merge locking exception-safe, then narrow it — source finding

Location: `mixin/entity/ItemEntityMixin.java`.

One static lock serializes all item merging. Acquisition at HEAD and release at RETURN
also leaves it locked if the original method throws. First replace the split injections
with `@WrapMethod` and `try/finally`. Then profile an item-farm workload and consider
ordered two-item locking at the actual pair mutation, using the existing `StripedLocks`
pattern. Per-cell locking alone does not protect merges across cell boundaries.

### 6. Simplify tracking maintenance and avoid interior rescans — source finding

Locations: `tracking/AreaMap.java:update`, `tracking/NearbyTrackers.java`.

`AreaMap.update` changes only differing cells but still scans both complete squares.
For a one-chunk move at unchanged radius, named helpers for entering/leaving strips
could reduce scanning from area to perimeter. Keep a straightforward fallback for
teleports and radius changes, with model-based tests against the current algorithm.

Staged trackers occupy both a deque of records and a membership set; removing one does
a linear `removeIf`. An insertion-ordered tracker-to-added-tick map could express the
same policy with fewer structures and constant-time lookup/removal. Batch tracking
actions per tracker only if player-heavy profiling shows the individual lambdas matter.
No connected players were used here, so multiplayer tracking benefits are unmeasured.

### 7. Review write-heavy copy-on-write lists — source finding

Locations: `ChainRestrictedNeighborUpdaterMixin.pending`, `TypeFilterableListMixin`.

Copy-on-write arrays copy on mutation. Neighbor-update queues and entity-section churn
deserve explicit ownership rules rather than a blanket concurrent-collection substitution.
The neighbor enqueue method already holds a monitor, but establish all read/drain paths
before considering a regular list under that monitor or a snapshot at a phase boundary.
Read-mostly lists elsewhere may remain appropriate; do not replace them indiscriminately.

### 8. Reduce adapter duplication and give background work a lifecycle — maintainability

`FastUtilViews` is 1,129 lines; the concurrent adapter package totals 2,359. Several bulk
operations construct temporary sets through streams, and `fastIterator` just delegates
to the allocating ordinary iterator. Split primitive adapters into focused classes,
reuse appropriate abstract implementations, and cache views where semantics allow.
Keep contract tests for equality, default-return values, live views, and iterator removal.
The linked-set wrapper's numeric ordering versus insertion ordering also needs an audit.

Add an explicit closeable server runtime owning the pool and housekeeping tasks. The
worker pool and per-world lock evictors currently lack a registered server-stop cleanup;
the chunk cache separately starts a cleaner thread. Clear ownership improves integrated
server restarts and prevents retained background work without adding a generic framework.
Replace broad swallowed exceptions in entity mixins with an understood fix or diagnostic
path; they currently make correctness problems difficult to distinguish from slow behavior.

## Suggested sequence

1. Keep the distributed benchmark and make dense/sparse/entity/item/redstone/player
   workloads explicit. Record MSPT, allocations, and relevant stage timing.
2. Implement and measure the cache hash, then the cheap worker-type guard.
3. Address range-copy allocation while locking down its concurrency contract.
4. Fix item-lock exception safety and worker-barrier cancellation before expanding parallelism.
5. Take worker-wave, tracking, and adapter refactors as separate measured changes.

Avoid a wholesale executor rewrite, lock-free custom collections, or removal of broad
Minecraft synchronization without evidence. Small named abstractions and tested ownership
rules offer a better match for performance plus readable code.

Raw evidence: `scheduler-*.log`, `server-review.log`, `server-results.json`, `server.jfr`,
`profile-summary.txt`, `hash-benchmark.log`, `CacheHashBench.java`, and `server-review.py`
under `.vanadium/review/`. The live workload completed save/shutdown without error-level
server log entries. Both benchmark helpers are temporary review experiments, not CI tests.

## Implemented: cache hashing, range queries, worker identification

Only these three production areas were changed:

- `ParallelChunkManager.CacheKey` retains its record equality and now mixes the packed
  chunk position before folding it into the hash. Cache lifetime, lookup-key allocation,
  and load/publication behavior are unchanged.
- `ConcurrentLongSortedSet` returns bounded, live skip-list views instead of building
  AVL-tree snapshots. Forward iteration uses the backing iterator directly. A small
  cursor adapter also supports backward traversal and removal without copying a tree;
  the fastutil abstract sorted-set base supplies set equality/hash semantics.
- `McThreadTracker` records only Minecraft's Main workers. The specific
  `isMinecraftMainWorker` query rejects ordinary server and Vanadium threads using an
  `instanceof ForkJoinWorkerThread` check before accessing the concurrent registry.

Range views are deliberately **live and weakly consistent**, rather than detached snapshots.
Minecraft 1.21.1's `SectionedEntityCache.forEachInBox` and `getSections` consume them with
forward iterators; section lookups already skip missing sections. View mutations now
affect the backing set, matching the normal sorted-set view contract. Iterators are not
point-in-time snapshots and may observe concurrent additions/removals.

Validation: `./gradlew build` passed **90 tests**, with no failures, errors, or skips.
New coverage checks nearby-key hash distribution, position/status identity, actual
Minecraft/other/Vanadium worker classification, range bounds, live views, set equality,
cursor direction changes/removal, randomized comparisons against fastutil, and concurrent
range mutation. The final distributed server run also completed save and shutdown with
no error-level server log entries. `git diff --check` passed.

The same 256-zombie/64-chunk experiment was repeated in a fresh world with the same
settings, warmup, sprint ordering, and JFR recording settings:

| Entity ticking | Before: ms/tick | After: ms/tick | Median before → after |
| --- | --- | --- | --- |
| Parallel | 1.04, 0.95, 0.95 | 1.04, 0.87, 0.87 | 0.95 → 0.87 |
| Serial fallback | 1.39, 1.38, 1.35 | 1.22, 1.23, 1.20 | 1.38 → 1.22 |

Observed median tick time fell about **8% with parallel entities** and **12% with serial
entities** in this synthetic workload. These are indicative comparisons between separate
server runs, not controlled multiplayer or long-duration results.

Reanalysis of both JFR files using their full stored stacks (the initial review's text
export limited stack depth) found `ParallelChunkManager.lookup` in 285 of 1,458 execution
samples before versus 64 of 1,296 after. AVL-tree entries no longer appear among the
largest weighted allocation classes. `CacheKey` allocation remains a major hotspot;
this patch fixes its hashing, not allocation. Sample counts are not direct CPU-time
percentages or exact allocation totals.

Final evidence is under `.vanadium/review-after/`: `server-results.json`, `server-review.log`,
`server.jfr`, `profile-before.txt`, `profile-after.txt`, and the fresh run directory recorded
in `server-run.txt`. Player movement, extended chunk churn, and multiplayer behavior still
need their own live workloads.

## Simplification follow-up (2026-09-12)

The Main-worker registry above is now removed entirely. `ParallelChunkManager` compares
a `ForkJoinWorkerThread`'s owning pool with `Util.getMainWorkerExecutor()`. This removes
`McThreadTracker`, its concurrent set, and the registration-only `UtilMixin` (including
its mixin configuration entry). Inspection of the pinned Minecraft source and C2ME's
`Util` mixin confirmed that the Main executor is exposed directly.

`Cell` computes and stores its color at construction instead of retaining a `CellPos`.
The unused `pos()` and `taskCount()` methods and `CellPos` class are removed. Floor
division remains in `CellGrid`, with negative-coordinate and neighboring-color coverage
in the existing grid/cell tests. Status now reads the worker count through the scheduler
from `WorkerPool`, replacing the duplicate mutable `Vanadium.bootWorkers` field.

Validation:

- Focused tick/chunk tests with `--rerun-tasks` and the full build passed: **87 tests**,
  no failures, errors, or skips. The old coordinate-grouping test was merged into grid
  coverage, and the two registry tests were replaced by the live checks below.
- Direct chunk-manager tests could not run in plain JUnit: vanilla registry bootstrap
  and mapped-class access require the Fabric environment. A temporary test mod in
  `.vanadium/simplify-check/` instead checked the actual Minecraft Main and IO pools,
  an unrelated ForkJoin pool, an ordinary/name-spoofed thread, and Vanadium worker/caller
  threads with bounded waits. All classifications passed with pinned C2ME loaded.
- A chunk request from Minecraft's Main worker completed through the server executor.
  Changing configured workers preserved the active count and displayed `restart pending`;
  restoring the setting cleared that hint.
- The isolated smoke test passed startup, chunk loading, block editing, ticking, saving,
  and shutdown with no error-level server entries. Evidence is retained in
  `.vanadium/simplify-check/server-check.log` and `.vanadium/runs/smoke-p_i7r4h5/`.
- `git diff --check` passed; the built jars contain none of the removed classes or the
  temporary test mod.

No timing benchmark was repeated for this cleanup; the earlier measurements describe
the previous implementation. Multiplayer, extended world use, and integrated-server
restart remain untested.

## Implemented: indexed worker waves and interruption barriers (2026-09-12)

`WorkerPool` now uses a private `Wave` with an indexed task list, atomic claim/remaining
counts, one completion latch, and the first task failure. Scheduler color buckets are
read directly; sequential lists and other collections get an array-backed copy. Queue
nodes and per-helper capturing lambdas are eliminated. Empty and one-task waves keep
their existing direct paths. The executor, helper count, cell sizing, and eviction policy
are unchanged.

The task count is captured before dispatch. After all tasks finish, queued late helpers
can only claim an out-of-range index; they never inspect a list that the scheduler has
already cleared/reused. Task completion is the barrier, not helper invocation completion.
If helper submission fails, the caller still drains the wave and waits for in-flight
tasks before propagating an error.

An interrupted caller now waits until every task finishes, then restores its interrupt
flag before returning or throwing the captured task failure. Interruption does not cancel
tasks. Three bounded, gated regression tests fail against the previous implementation and
pass with the fix, covering interruption during the wait, interruption before the wait,
and interruption combined with task failure. Additional coverage checks exact-once task
execution, repeated list reuse, non-random-access collections, and rejected submission.

### Measurements

Same Ryzen 5 9600X, NixOS, Java 21.0.12.1 and project/mod versions recorded above; Parallel
GC, no other benchmarks or servers running concurrently. Both benchmarks are standalone
Java tests with no Minecraft world or runtime flags. The new `./gradlew benchWorkerPool`
uses 1 and 12 workers and prebuilt lists of 1, 2, 16, 256, or 1,024 tasks. It compares no-op
tasks, balanced CPU work (200 iterations/task), and the same total work concentrated into
every sixteenth task. Each task owns its result, avoiding a shared result accumulator in
the timed path. Each case uses 2,000 warmup and 5,000 measured waves, reporting elapsed
time and allocation across the warmed JVM thread set.

The final comparison used three fresh JVMs per implementation and benchmark, in mixed
order: new/old, old/new, new/old. Saved class directories select the pre-change and final
worker implementations without switching the working tree. These are diagnostic
microbenchmarks, not JMH confidence intervals or end-to-end Minecraft performance claims.

Selected medians with **12 workers**:

| Tasks / workload | Before → after, µs/wave | Before → after, bytes/wave |
| --- | ---: | ---: |
| 2 / balanced | 0.535 → 0.622 | 214 → 174 |
| 16 / no-op | 1.260 → 1.089 | 1,171 → 433 |
| 256 / balanced | 20.755 → 15.296 | 7,155 → 609 |
| 1,024 / no-op | 37.003 → 19.058 | 25,502 → 519 |
| 1,024 / uneven | 83.312 → 49.890 | 25,710 → 768 |

Large waves improve substantially; tiny waves are mixed. For example, two balanced tasks
were 87 ns slower in the median above, and 16 uneven tasks went from 2.896 to 3.053 µs.
Single-task dispatch is unchanged; differences there reflect measurement/JIT variation.
All raw cases, including the one-worker runs, are retained in `comparison.json`.

The existing full scheduler benchmark uses 12 workers, cell size 4, 1,024 chunks, 2,000
warmup and 5,000 measured CHUNK stages, with `SPIN=200`:

| Metric | Before: three JVMs | After: three JVMs |
| --- | --- | --- |
| No-op bytes/stage | 4,916 / 4,909 / 4,906 | 1,927 / 1,938 / 1,923 |
| No-op µs/stage | 15.4 / 15.5 / 15.6 | 13.6 / 14.0 / 13.6 |
| Loaded µs/stage | 67.8 / 65.4 / 68.1 | 65.7 / 63.0 / 71.8 |

Median scheduler allocation falls **61%** and no-op time **12%**. Loaded-stage ranges
overlap, so these trials do not establish a loaded-stage speedup. A first prototype
counted every task directly through the latch; its loaded results prompted the mixed-order
comparison and retaining a separate remaining counter, with only the final task signaling
the latch.

### Validation and evidence

- Focused scheduler tests passed; `./gradlew build --rerun-tasks` passed **93 tests**, with
  no failures, errors, or skips. The access widener and remapped jars also built.
- The fresh distributed smoke world ran 256 persistent AI zombies in 64 enclosures/chunks,
  seed 12345, fixed midnight, normal difficulty, no players or natural spawning, all
  Vanadium flags enabled, 12 workers, automatic cell size 3, and pinned C2ME. Initialization,
  ticking, save, and shutdown passed without error-level server entries. This was a
  compatibility/correctness smoke run; no new server speedup is claimed.
- `git diff --check` passed. Raw comparisons, the old failing-test log, benchmark driver,
  and live evidence are retained in `.vanadium/wave-review/`; the fresh world/logs are in
  `.vanadium/runs/smoke-iwv6mhby/`.

Long-duration world safety, multiplayer behavior, and integrated-server restart remain
untested. Interruption deliberately completes the current wave; a task that never returns
still prevents its barrier from completing.

## Cache publication and item-lock follow-up (2026-09-12)

The final change keeps the existing concurrent map and mixed record key. Both chunk-load
lock modes now call one `loadAndCache` helper: recheck the cache, perform the vanilla load,
and publish a non-null result before releasing the load lock. Null results no longer
create useless weak-reference entries. The weak ownership and ten-second clear policy
are unchanged.

Separately, `ItemEntityMixin` wraps `tryMerge()V` with `try/finally` instead of acquiring at
HEAD and releasing only at RETURN. The same global reentrant lock and scope remain;
exceptions propagate after unlocking. No narrower item locking was introduced.

### Allocation experiments: not retained

Two prototypes reused immutable lookup keys through a thread-local reference, then a
16-slot thread-local array. Neither cached chunks or bypassed invalidation. They were
compared against the current scheduler/cache baseline using the same 256-zombie,
64-chunk workload described above: Ryzen 5 9600X, locked Java 21.0.12.1, pinned Minecraft,
Fabric and C2ME versions, 12 workers, automatic cell size 3, fixed seed/midnight, no
players or natural spawning. All flags were enabled except the entity flag being compared.
Each fresh server warmed for 15 seconds, then ran six 2,000-tick sprints in
on/off/off/on/on/off order under JFR, with saves and two-second pauses between sprints.

| Implementation | Parallel ms/tick (median) | Serial fallback ms/tick (median) | Weighted sampled key allocation |
| --- | ---: | ---: | ---: |
| Existing keys | 1.18 | 1.35 | 5.99 GB |
| Last key per thread | 1.16 | 1.42 | 2.79 GB |
| 16 keys per thread | 1.06 | 1.46 | 2.75 GB |

The prototypes reduced weighted sampled key allocation by about 53–54%, but serial
fallback timing worsened in these runs and cache lookup appeared more often in CPU
samples. Timing varied within each process, and these evolving-world trials do not
establish a general speedup or regression. The evidence did not justify retaining the
extra lookup machinery. A separate object-allocation check also found both a boxed long
and the existing record occupy 24 bytes on this JVM, ruling out the presumed size benefit
of replacing the record with a boxed position key alone.

**Key allocation remains an open optimization target.** The final patch contains neither
thread-local prototype nor a map-layout change; no allocation or tick-time improvement is
claimed for the retained publication/item-lock fixes.

### Final validation

- Focused chunk/item tests passed. `./gradlew build --rerun-tasks` passed **94 tests** with
  no failures, errors, or skips, and built the remapped jars and access-widener validation.
- A regression test invokes the item wrapper with a throwing operation and proves a
  different thread can acquire the lock afterward, using a bounded wait.
- A temporary Fabric test mod checked actual cache behavior in both load-lock modes:
  required-status separation, cleared weak references, invalidation, non-loading null
  misses, and cache/master-disabled fallbacks. It also observed the real per-position
  unlock edge and verified the chunk was already published.
- With pinned C2ME loaded, a temporary mixin injected a failure inside vanilla item
  merging. The exception propagated and a different Minecraft IO worker subsequently
  entered the merge method. Normal ticking then merged two 16-item stacks across the
  x=48 block cell boundary into one 32-item stack.
- Final isolated startup/tick/save/shutdown passed with no error-level log entries;
  `git diff --check` passed. The final live run is `.vanadium/runs/smoke-ehihz93i/`.

Evidence and the disposable test mod/scripts are retained under `.vanadium/cache-review/`:
`before/`, `after/` (one-key prototype), `pooled/`, and `check-server-final.log`.
The test mod is not part of the distributable jar. Multiplayer, long-running unload/reload
churn, integrated-server restart, and item-farm lock contention remain untested.

## Tracker staging and area updates (2026-09-12)

`NearbyTrackers` now stores staged trackers and arrival ticks in one
`Reference2LongLinkedOpenHashMap`. This removes the parallel identity set and wrapper
record, and replaces linear deque removal with a hash lookup. Insertion order preserves
FIFO expiry: a tracker added at tick 0 remains staged at tick 200 and migrates at 201.
Identity keys matter because Minecraft's tracker equality compares entity IDs.

`AreaMap.update` now visits the entering/leaving strips around the overlap of its old
and new squares. Stationary updates return immediately; disjoint teleports paint/erase
the complete squares. Two private helpers express rectangle subtraction and rectangle
updates without allocating temporary geometry objects. Ordinary small moves visit work
proportional to the perimeter instead of scanning both square interiors.

### Synthetic comparisons

Hardware: AMD Ryzen 5 9600X, 6 cores / 12 logical CPUs; NixOS, locked OpenJDK 21.0.12.1,
Parallel GC. Each comparison used three fresh JVMs per implementation in mixed order,
run sequentially with no concurrent server workload. These are caller-thread collection
benchmarks; worker count, cell size, and Vanadium flags do not apply. The classpath uses
Minecraft 1.21.1 and the existing dependencies; Fabric/C2ME are not booted for these runs.
Values below are medians, not end-to-end tick or multiplayer performance claims.

The staging fixture removes and re-adds existing identities at varying positions, keeping
the population constant. It uses 2,000 warmup and 10,000 measured operations per size and
compares the previous deque/record/set layout with the new linked identity map.

| Staged trackers | Before ns/remove+add | After ns/remove+add | Before bytes/op | After bytes/op |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 518.3 | 91.2 | 64 | 0 |
| 1,024 | 1,531.5 | 51.2 | 174 | 0 |
| 8,192 | 9,282.0 | 54.5 | 795 | 0 |

This isolates container churn; it does not measure full tracker removal, player updates,
or traversal of the staging map each tick.

The committed `./gradlew benchAreaMap` fixture alternates one mover between two states,
with an overlapping stationary object. Each case warms for 250 ms and measures 50,000
updates. Teleports move by three radii; resizes alternate radius and radius + 1.

| Radius | Workload | Before ns/update | After ns/update |
| ---: | --- | ---: | ---: |
| 2 | Stationary | 35.9 | 19.0 |
| 2 | One chunk | 77.1 | 46.1 |
| 2 | Diagonal | 86.3 | 69.4 |
| 2 | Teleport | 365.6 | 620.4 |
| 2 | Resize | 142.8 | 118.5 |
| 8 | Stationary | 547.7 | 3.2 |
| 8 | One chunk | 639.7 | 153.3 |
| 8 | Diagonal | 844.0 | 367.8 |
| 8 | Teleport | 7,113.5 | 6,810.8 |
| 8 | Resize | 905.6 | 364.3 |
| 16 | Stationary | 2,000.1 | 3.1 |
| 16 | One chunk | 2,459.3 | 480.5 |
| 16 | Diagonal | 2,902.8 | 851.2 |
| 16 | Teleport | 19,420.8 | 18,279.9 |
| 16 | Resize | 3,085.4 | 907.0 |

One-chunk moves at radii 8–16 were 4.2–5.1 times faster in this fixture. Both versions
reported zero whole bytes/update for overlapping movement, resize, and stationary cases.
Teleport allocation was unchanged at 2,000 / 28,900 / 108,900 bytes per update for radii
2 / 8 / 16, from creating uncovered chunk sets. The extremely low stationary times reflect
an unchanged-state microbenchmark; `NearbyTrackers` already filters unchanged chunks.

The radius-2 teleport result prompted an isolated follow-up with one second of warmup and
one million measured updates, again three mixed-order forks each. Medians were 479.1 ns
before and 495.8 ns after, with overlapping ranges (437.0–539.2 versus 490.9–509.1 ns).
The larger apparent slowdown did not reproduce under that setup. No teleport speedup is
claimed; disjoint updates still have the same square-sized work and allocation.

### Validation and retained evidence

- The three new area tests passed against both the original implementation and final code.
  They compare against independently rebuilt full-square coverage across moves, resizes,
  teleports, negative/world-edge coordinates, and 1,000 seeded mixed operations with
  overlapping objects and distinct equal-valued identities.
- Focused tracking tests were rerun; `./gradlew build benchAreaMap` passed **97 tests** with
  no failures, errors, or skips, built remapped jars, validated the access widener, and
  exercised the new benchmark task. Reported comparisons use the separate JVM runs above.
- A temporary Fabric test mod exercised the actual tracker classes and applied mixins
  with two simulated server-side players and intercepted packet sends. It verified
  identity-keyed staging, arrival order, removal/re-addition, the 200/201 expiry boundary,
  player movement, entity chunk crossings and teleports, watcher changes, and player removal.
  Its own `NearbyTrackers` instance dispatched through the shared tracking scheduler.
- The same fresh server completed startup, ticking, save, and shutdown with pinned C2ME
  0.4.0-alpha.0.16, Fabric Loader 0.19.3, Fabric API 0.116.12+1.21.1, 12 workers, automatic
  cell size 3, and all Vanadium flags enabled. `git diff --check` passed.

Raw benchmarks, frozen before/after classes, fixture sources, and the temporary test mod
are in `.vanadium/tracking-review/`. The live world and logs are retained at
`.vanadium/runs/smoke-f4djc3ta/`. The test mod is not included in the distributable jar.
Real client connections, join/leave protocol behavior, dimension changes, and sustained
multiplayer load remain untested; the simulated-player check is not a multiplayer benchmark.
