> Historical evidence from the 26.2 development cycle. Commands and source paths below
> describe that revision; current workflows are in [DEVELOPMENT.md](../DEVELOPMENT.md).

# Minecraft 26.2 feature benchmarks

Measured 2026-09-19 using the checked-in feature benchmark harness. The baseline
uses production code at `bc13da9`; follow-up results cover the fixes for
[navigation](#slowdown-fixes-navigation), [tracking](#slowdown-fixes-tracking) and
[spawn preparation](#slowdown-fixes-spawn-preparation). These are warmed subsystem
operations in a transformed Fabric server with C2ME, not whole-server TPS gains.
All four correctness fixes remain on.

## Method

- AMD Ryzen 5 9600X, 6 cores / 12 logical CPUs, one NUMA node, NixOS/Linux 7.2.6, performance governor.
  No affinity or frequency pinning; the desktop/editor remained running. This is
  not a result for the target dual Xeon E5-2699 v3.
- OpenJDK 25.0.4.1+1, fixed 2GiB heap, G1; Minecraft 26.2, Fabric Loader 0.19.3,
  Fabric API 0.160.0+26.2, Cloth Config 26.2.155; pinned C2ME
  0.4.2-alpha.0.52 with global executor parallelism explicitly set to four.
- Vanadium: four background helpers plus the participating server thread; cell
  size two. Other flags stay at defaults. Separate fresh flat worlds, seed 12345,
  simulation/view distance four; no remote players or additional performance mods.
- Three sequential fresh JVMs. Each case warms off/on for two seconds each, then
  runs 12 alternating AB/BA pairs of 250ms operation batches. The first order is
  reversed in the second JVM. No timing assertion or fastest-run selection.
- Times and allocations are medians across 36 batches per arm, normalized by
  operations. Speedup is the median paired off/on ratio; greater than one is faster.
  Fork ranges compare each JVM's medians; they are not confidence intervals.
- `ThreadMXBean` measures caller CPU, helper CPU and allocated bytes. Allocation
  includes caller plus Vanadium helpers; it excludes unrelated background threads.
  GC pauses remain part of elapsed time. The timing loop and instrumentation have
  finite overhead, particularly for sub-microsecond cases.
- Separate two-second JFR recordings for both arms of every case in the first JVM:
  Java execution samples at 5ms, allocation sampling, monitor/park events at 1ms.
  JFR is stopped during timing batches. Shorter waits are not counted; absence of
  recorded monitor events does not establish absence of contention.

## What the switches mean

| Feature | Off control | On operation and workload |
|---|---|---|
| Navigation | Full scan through the current navigation set; invalidation hooks stay installed | Conservative candidates plus one refresh per operation; 1,024 32-node paths, 1 or 64 edits. Dispersed mobs are 128 blocks apart; dense mobs overlap. Dirty cases invalidate every mob before refresh, without moving the bounds. No pathfinding/recomputation is executed. |
| Tracking preparation | Test-only mixin selects the existing inline diff branch | Same area index, eight simulated players, 256/2,048 mobs, completed staging, moving players and sync-requested entities. Includes preparation and callback dispatch/drain; the baseline also merges update records. Player update/send callbacks are stand-ins; no real clients. |
| Spawn counting | `parallelSpawning=false` calls vanilla `createState` | Snapshot when needed, worker counts and reduction included. 256/2,048/8,192 alternating pigs/zombies; one third persistent, one loaded flat chunk. No live player caps or nonzero biome density charges in the timing fixture. Spawn attempts themselves are excluded. |
| Item locks | All candidate pairs share one reentrant lock | Same current safe merge method with individual locks; 1/32 independent pairs, 128 checks per pair per wave, nonmatching stone/dirt stacks. This isolates lock granularity, not the historical whole neighbor-search implementation or pickup throughput. |
| Neighbor queue | Former `CopyOnWriteArrayList` restored in the real updater | Current `ArrayList`; same synchronized queue drain. Synthetic callbacks enqueue 6/256 leaves. Block behavior, redstone computation and cross-region chains are excluded. |
| Chunk packets | Direct `ClientboundLevelChunkPacketData` construction | Section preparation, barrier, buffers, copying and packet-data construction included. Batches of 8/32 flat chunks, then eight dense 16-state palette sections per chunk. Fixture section edits are outside measurement. Lighting packets, selection/quotas, compression and sending are excluded. |
| Profiler | Scheduler profiling detached | Same 128-cell wave with profile attached; zero or 2,000 integer iterations per task. Includes collection, four color barriers and per-world/cell recording. Steady-state bounded sample storage can saturate. |

Since the Kotlin port, the item-lock and neighbor-queue fixtures call the private vanilla methods
through reflection instead of exact method handles. Both arms pay the same call overhead, but
absolute times are not directly comparable with the tables recorded before the port.

Benchmark switches and reflective field substitutions exist only in the separate
test jar. No unsafe unlocked mode is used. The fixture runs at server startup,
outside normal ticking; its later catch-up warning is expected. This avoids the
20 TPS ceiling but cannot supply tick p95/p99 or a no-Vanadium comparison.

## Baseline results

**The original changes were not uniformly faster.** Navigation has the largest conditional gain
and the worst regression; tracking preparation regresses in both measured sizes.
Large spawn counts, independent item candidates, neighbor queue storage and packet
section preparation benefit in these fixtures. One operation means the complete
workload named in the row, not one Minecraft tick. 1,000µs = 1ms.

| Workload | Off µs | On µs | Paired speedup | Fork range | Caller CPU off/on µs | Allocation off/on KiB |
|---|---:|---:|---:|---:|---:|---:|
| navigation-spread-edits-1-static | 14.83 | 0.11 | 137.73× | 134.18–143.21× | 14.80 / 0.11 | 8.1 / 0.4 |
| navigation-spread-edits-1-dirty | 25.47 | 673.86 | 0.04× | 0.03–0.04× | 25.44 / 672.98 | 8.2 / 8.4 |
| navigation-spread-edits-64-static | 996.23 | 4.58 | 219.15× | 211.82–228.34× | 994.13 / 4.56 | 521.6 / 19.1 |
| navigation-spread-edits-64-dirty | 1003.36 | 740.98 | 1.38× | 1.34–1.52× | 1002.09 / 739.96 | 521.6 / 27.1 |
| navigation-dense-edits-64-static | 976.69 | 1301.09 | 0.75× | 0.74–0.76× | 974.74 / 1296.05 | 521.6 / 1041.7 |
| tracking-8-players-256-mobs | 44.91 | 56.13 | 0.80× | 0.79–0.81× | 44.79 / 53.43 | 61.3 / 146.7 |
| tracking-8-players-2048-mobs | 410.62 | 464.70 | 0.88× | 0.86–0.90× | 409.06 / 450.29 | 468.5 / 1116.6 |
| spawn-count-256 | 2.63 | 3.36 | 0.78× | 0.76–0.78× | 2.62 / 3.34 | 7.7 / 12.1 |
| spawn-count-2048 | 23.07 | 18.53 | 1.26× | 1.25–1.27× | 22.98 / 16.09 | 54.4 / 93.3 |
| spawn-count-8192 | 111.61 | 63.47 | 1.75× | 1.70–1.80× | 111.22 / 55.80 | 214.4 / 330.6 |
| item-locks-1-pairs | 3.73 | 3.26 | 1.14× | 1.13–1.15× | 3.72 / 3.25 | 5.0 / 5.0 |
| item-locks-32-pairs | 223.25 | 54.73 | 4.09× | 3.86–4.91× | 82.63 / 52.00 | 164.0 / 160.4 |
| neighbor-queue-width-6 | 0.25 | 0.09 | 3.02× | 2.63–3.05× | 0.25 / 0.09 | 0.3 / 0.1 |
| neighbor-queue-width-256 | 12.28 | 2.34 | 5.20× | 4.29–5.37× | 12.19 / 2.33 | 137.0 / 4.0 |
| chunk-packets-8-flat | 17.24 | 11.40 | 1.52× | 1.51–1.55× | 17.19 / 9.95 | 30.6 / 49.9 |
| chunk-packets-32-flat | 70.48 | 27.60 | 2.55× | 2.52–2.57× | 70.35 / 26.73 | 122.3 / 197.8 |
| chunk-packets-8-palette | 36.71 | 25.49 | 1.43× | 1.40–1.44× | 36.55 / 22.38 | 203.2 / 395.2 |
| chunk-packets-32-palette | 146.25 | 81.87 | 1.78× | 1.74–1.79× | 145.48 / 78.47 | 812.8 / 1578.8 |
| profiler-128-cells-work-0 | 5.67 | 11.67 | 0.48× | 0.47–0.49× | 5.37 / 10.58 | 1.1 / 6.4 |
| profiler-128-cells-work-2000 | 40.82 | 46.29 | 0.90× | 0.88–0.95× | 36.64 / 39.92 | 1.7 / 6.8 |

## What the baseline profiles show

- **Navigation cleanup has a concrete quadratic path.** In the all-dirty/one-edit
  on-profile, 292 of 394 caller CPU samples end in `Entity.getId`; the stack is
  `Entity.equals → ArrayList.contains → AbstractCollection.removeAll → NavigationIndex.refresh`.
  `dirty.removeAll(changed)` repeatedly searches the snapshot list. The measured
  25.47→673.86µs is **26.5× slower** by the ratio of median times, even though bounds
  stay unchanged. Fix this cleanup before concluding that dirty-index cost is inherent.
  Stable dispersed queries benefit greatly; dense 64-edit queries are **33% slower**
  and allocate roughly twice as much.
- **Tracking still feeds a serial enqueue/merge path.** For 2,048 mobs, the on-profile
  has 170/375 caller samples in `CellGrid.enqueue` or `NearbyTrackers.enqueue`;
  workers spend samples building diffs and growing lists. Caller CPU rises
  **409→450µs**, total caller+helper CPU **409→711µs**, and allocation **469→1,117KiB**.
  Wall time regresses **13%**; the 256-mob case regresses **25%**. More worker activity
  alone does not relieve the main thread here.
- **Spawn copying remains on the caller.** At 8,192 entities, 132/303 caller samples
  in the on-profile are in `ArrayList.grow`. Workers do run `NaturalSpawner.createState`.
  Caller CPU falls **111→56µs**, while combined CPU rises **111→147µs**. The small
  256-entity fallback still makes a snapshot and is **28% slower**. Avoiding that
  copy is a clearer first step than increasing worker counts.
- **Item lock scope matters under independent work.** The shared-lock profile samples
  `AbstractQueuedSynchronizer.acquire` and records parks in the merge wrapper;
  individual locks let workers execute candidate checks concurrently. The 32-pair
  fixture is **4.09× faster** by paired timing, with combined CPU **398→248µs**.
  This is evidence for narrower lock scope, not a pickup or whole-item-tick benchmark.
- **Neighbor storage reduces copying, not serialization.** Both profiles remain in
  the synchronized updater's add/drain path. With 256 queued leaves, measured
  allocation falls **137→4KiB/op** and elapsed time improves **5.20×**. Actual block
  callbacks and the per-world lock remain outside the benefit demonstrated here.
- **Packet work moves onto helpers at a memory cost.** Palette map lookups, fixed
  long-array writes and varint encoding appear on workers in the on-profile.
  For 32 palette chunks, caller CPU falls **145→78µs** and elapsed time improves
  **1.78×**, but combined CPU rises **145→209µs**, and allocation nearly doubles
  (**813→1,579KiB**). This does not move block-entity or lighting callbacks off-thread.
- **Opt-in profiling has measurable overhead.** The 128-cell empty fixture goes
  **5.67→11.67µs**; the CPU-work fixture goes **40.82→46.29µs**. Normal dispatch and
  the fixture's CPU loop dominate the sampled stacks. Profiling adds roughly
  **5–6KiB/op** in these cases; keep it off for uninstrumented timing comparisons.

JFR CPU counts describe separate profiled runs, not the timing batches. Allocation
sample classes are useful for attribution; their weights can span preceding disabled
recording intervals. Quantitative allocation above comes from `ThreadMXBean`, not
summing JFR sample weights. Likewise, recorded parks only cover waits of at least 1ms.

## Reproduction and evidence

See [development commands](DEVELOPMENT.md#benchmarks-and-profiles). Raw per-batch
CSV, separate on/off JFR files, environment metadata and server logs stay under the
printed `.vanadium/runs/features-*/` directories. They are deliberately untracked.
Completed runs: `features-un8mbsn2` (includes all 40 JFR files), `features-ase7quly`,
`features-3jsxr6h7`: **1,440 timing batches** total. Full JSON summary is retained at
`.vanadium/feature-results-26.2-2026-09-19.json`. The initial reduced-duration harness
checks are excluded. All three measured servers saved and stopped without error logs.
The final build passed 165 tests; the live regression suite passed in
`.vanadium/runs/smoke-il6299h1`.

The report script emits both a Markdown table and JSON containing worker CPU costs,
per-JVM ratios and JFR summaries; use Mission Control for complete call trees.

Do not extrapolate subsystem ratios to TPS or add their savings together. A real
world changes entity/path lifetimes, population, palette sizes, packet callbacks,
allocation pressure and the fraction of time spent in each path. Shared world
monitors, caller-side merges and cell imbalance can dominate the remainder.
See [current limitations](DEVELOPMENT.md#current-limitations) before deployment.

## Slowdown fixes: navigation

Three new JVMs with the same durations, workers and workload, restricted to
`--filter 'navigation-.*'`, confirm the cleanup/candidate-copy fixes and deferred
refresh policy. These are post-fix on/off pairs; the older baseline above is retained.

| Workload | Off µs | On µs | Paired speedup | Fork range | Caller CPU off/on µs | Allocation off/on KiB |
|---|---:|---:|---:|---:|---:|---:|
| navigation-spread-edits-1-static | 15.59 | 0.07 | 233.04× | 228.15–252.12× | 15.55 / 0.07 | 8.1 / 0.1 |
| navigation-spread-edits-1-dirty | 25.48 | 24.73 | 1.03× | 1.02–1.04× | 25.41 / 24.69 | 8.2 / 8.1 |
| navigation-spread-edits-64-static | 1034.65 | 2.47 | 418.59× | 414.95–421.84× | 1033.03 / 2.46 | 521.6 / 6.5 |
| navigation-spread-edits-64-dirty | 1022.82 | 46.01 | 22.28× | 22.21–22.55× | 1020.90 / 45.94 | 521.6 / 14.6 |
| navigation-dense-edits-64-static | 970.61 | 910.59 | 1.06× | 1.03–1.14× | 969.29 / 906.61 | 521.6 / 517.6 |

The all-dirty/one-edit case is now effectively at full-scan cost; dense queries no
longer allocate a deduplication set. Frequent edits still benefit from refreshing
and using the index. Dirty fallbacks remain conservative if refresh is deferred or
invalidation occurs during a snapshot. Live checks now cover those cases explicitly.
Build: 165 tests passed; live C2ME checks: `.vanadium/runs/smoke-dvdaho0o`.
Measured runs: `features-a07gdhlk` (JFR), `features-7yub2n7l`, `features-f0tzfxo0`;
summary: `.vanadium/navigation-fixed.json`. The short exploratory run is excluded.

## Slowdown fixes: tracking

The off control now runs the same reusable-row/batched-task implementation inline;
on runs independent player preparation in parallel. Both arms include dispatch and
callbacks, so this table isolates parallelism within the improved implementation.

| Workload | Off µs | On µs | Paired speedup | Fork range | Caller CPU off/on µs | Allocation off/on KiB |
|---|---:|---:|---:|---:|---:|---:|
| tracking-8-players-256-mobs | 30.20 | 25.90 | 1.17× | 1.17–1.18× | 30.17 / 23.99 | 8.1 / 8.6 |
| tracking-8-players-2048-mobs | 307.25 | 236.91 | 1.29× | 1.22–1.32× | 306.83 / 226.79 | 51.3 / 51.7 |

Compared with the earlier implementation's enabled measurements (separate JVM runs),
256 mobs improved from 56.13 to 25.90µs; 2,048 mobs from 464.70 to 236.91µs.
Large-case allocation fell from 1,116.6 to 51.7KiB/op and caller CPU from 450.29 to
226.79µs. Those historical comparisons are not paired samples. The within-build
on/off comparisons above retain the original alternating-pair method.

JFR no longer shows enqueue/record merging as the dominant caller work. The large
on-profile spends 141/377 caller samples in `ServerEntity.sendChanges` and 64 in
`TrackingTask.run`; workers execute player diffs. This clustered fixture still has
little cell-level callback parallelism. Player-owned rows avoid shared output writes;
tracker callbacks retain input player order and send changes once per tracker.

Build: 165 tests passed. Live checks cover player arrival/departure, tracker removal,
buffer growth and slot reuse as well as existing visibility/concurrent-writer checks:
`.vanadium/runs/smoke-apw7b28_`. Measured runs: `features-gatvpb69` (JFR),
`features-d6pzpvtz`, `features-2usbxpys`; summary `.vanadium/tracking-fixed.json`.

## Slowdown fixes: spawn preparation

The entity lookup exposes a live, read-only collection. Inputs below 1,024 entities
take the serial path without copying; larger collections provide a sized snapshot.
Unsized, potentially single-pass mod iterables retain the snapshot fallback.

| Workload | Off µs | On µs | Paired speedup | Fork range | Caller CPU off/on µs | Allocation off/on KiB |
|---|---:|---:|---:|---:|---:|---:|
| spawn-count-256 | 2.47 | 2.47 | 1.00× | 1.00–1.00× | 2.46 / 2.46 | 11.7 / 11.7 |
| spawn-count-2048 | 21.22 | 11.39 | 1.86× | 1.76–1.93× | 21.16 / 10.08 | 86.4 / 108.4 |
| spawn-count-8192 | 107.15 | 33.30 | 3.21× | 3.13–3.27× | 106.82 / 23.36 | 342.4 / 412.4 |

The small-case regression and extra allocation disappear. Large-case caller CPU
falls by 78%; combined caller/helper CPU is approximately 108µs versus 107µs off.
JFR no longer lists `ArrayList.grow` among the leading caller frames. Both caller
and helpers spend their samples counting entities and looking up biome data.

The enabled 8,192-entity operation previously measured 63.47µs and now measures
33.30µs, but that historical comparison uses separate JVM runs. Absolute allocation
also differs in the vanilla off arm between the original full suite and this
filtered run; do not attribute that difference to the fix. Within this run, parallel
preparation still adds 70KiB/op for the large case, versus 116KiB in the baseline.

Build: 165 tests passed. Live C2ME checks cover the 1,023/1,024/1,025 boundaries,
single-pass iterables, live collection membership and rejected view mutations:
`.vanadium/runs/smoke-8z1xd693`. Measured runs: `features-m8ict8ix` (JFR),
`features-g49ks_8b`, `features-xez91_l4`; summary `.vanadium/spawn-fixed.json`.
All three JVMs used `--filter 'spawn-count-.*'`; the short exploratory run is excluded.

## Dual Xeon E5-2699 v3 development VM (2026-11-08)

First measurements on the target hardware, using `scripts/bench-sweep.py`. These are whole-server
uncapped TPS (`/vanadium benchmark`, 30s) and 20 TPS stage profiles, not subsystem ratios.

### Method

- KVM guest on the dual E5-2699 v3 host: 72 vCPUs reported as 2 sockets × 36 cores, one thread
  per core, two NUMA nodes (7.9GB each), no guest `cpuidle` driver. NixOS, Linux 6.18.60.
- OpenJDK 25.0.4.1, fixed 4GiB G1 heap; Minecraft 26.2, Fabric Loader 0.19.3, Fabric API
  0.160.0+26.2, Cloth Config 26.2.155, C2ME 0.4.2-alpha.0.52 with `globalExecutorParallelism = 4`.
- Fresh flat world per JVM, seed 12345, view/simulation distance 4, no players. 1,024 force-loaded
  chunks (32×32); mob spawning, daylight and weather cycles off, midnight. `idle` measures those
  chunks alone; `mobs` adds 4,096 persistent, invulnerable zombies with AI (four per chunk).
- Cell size 2 unless stated. Each point is one server JVM; profiles are separate 10s captures.
  Sequential measurements in one JVM drift upward (about +10%) as zombies spread out, so
  design comparisons alternate variants in ABBA order inside one JVM.

### Results

Baseline is commit `7092c32` on the same VM topology. "Off" is `enabled = false`, which keeps
structural mixins (not a no-Vanadium baseline).

| Workers | Idle TPS before → after | Zombies TPS before → after |
|---:|---:|---:|
| 16 | – → 1,042 | – → 63.8 |
| 24 | 514 → 1,027 | 32.0 → 71.7 |
| 32 | – → 995 | – → 73.8 |
| 36 | 276 → 977 | 27.5 → 76.3 |
| 48 | – → 831 | – → 68.5 |
| 72 (auto) | 314 → – | 27.6 → – |

In one 32-worker JVM (same order effects): off 7.6, the former four-color schedule 65.4, neighborhood waves 81.3 TPS.
The four-color schedule was removed afterwards.
At 32 workers, tick work fell from roughly 31ms to 14–16ms; ENTITY execution is about 87% of
ideal parallel time (cell CPU ÷ participants). Remaining serial preparation per tick: spawning
0.5ms, chunk collection 0.6ms, tracking 0.5ms, entity collection 0.95ms.

Interleaved scheduling comparison at 36 workers (two ABBA rounds, mean TPS): four-color barriers
70.4 (cell 2) / 73.1 (cell 1); neighborhood waves 82.7 (cell 2) / 73.4 (cell 1).

### What limited scaling, and what changed

JFR at 24 workers on the baseline recorded about 122 thread-seconds of monitor blocking
(events ≥100µs) in a 41s window. The largest monitors were the navigation index (61s, every
moving mob), saved data via `getWorldBorder` (37s plus 3.8s on `WorldBorder`), C2ME's ticket-level
map via `hasChunk` (13s), `PathTypeCache` (8.2s) and POI distance updates (3.8s). After the changes
the same recording shows 1.4ms. Separately, `WorkerPool` woke helpers one `execute` at a time
through a queue and bounced two shared counters per task, so small waves got slower with more
workers; packed position keys degraded concurrent maps into tree bins.

| Area | Change |
|---|---|
| Worker pool | Persistent spin-then-park workers, Dekker wake-ups, chunked claims, one completion update per participant |
| Scheduling | One neighborhood-exclusion wave per stage, largest cells first (four-color barriers removed) |
| Navigation index | Lock-free per-mob dirty flags with striped queues |
| Saved data / border | Lock-free cached reads; border initialization double-checked |
| Chunk lookups | Exact lock-free map of loaded full chunks for `getChunk(FULL)`, `getChunkNow`, `hasChunk` |
| Path types, POI | Immutable cache entries; concurrent village distances without idle update passes |
| Randomness | Per-thread level random streams and random tick positions |
| Maps | murmur3-mixed packed keys in concurrent long maps |
| Serial work | Batched entity and random-tick collection, parallel tracker move scans, C2ME spawn filter unwrapped |

### Tick split and wave utilization

Measured with the profile's tick split and utilization (commit `f8ad3b5`) at 16 workers,
cell size 2, Fabric Loader 0.20.1, C2ME 0.4.2-alpha.0.52, otherwise as above but with 256
force-loaded chunks (16×16) and 1,024 zombies. One JVM: on, then `/vanadium set enabled off`.

| Arm | Uncapped TPS | Stage waves | Preparation | Serial | ENTITY utilization | CHUNK utilization |
|---|---:|---:|---:|---:|---:|---:|
| On | 165.6 | 84.3% | 4.9% | 10.7% | 40% | 17% |
| Off | 38.7 | 0.1% | 0% | 99.9% | – | – |

A separate 30s JFR capture of the same workload (`scripts/jfr-summary.py`) gave stage waves
86.2%, preparation 4.9% and serial 8.9%, with serial time per tick p50 0.44ms and p95 0.68ms.
The serial share is not the main limit here: ENTITY waves keep participants only about
35–40% busy. Likely causes, not yet measured separately, are neighbor exclusion (an 8×8
cell grid lets at most 16 of its 64 cells run at once, so 17 participants share at most 16
runnable cells and wait on neighbors) and uneven cells (the largest holds 29 tasks against an
average of 9–11). Wave shape looks like a better next target than the remaining serial code.

### Machine findings

- **Usable compute is about 36 cores.** One CPU-bound process per vCPU runs at 83–84% of solo
  speed with 18 or 36 busy vCPUs (turbo), but 42% (median) with 54 or 72, so total throughput
  falls beyond 36 busy vCPUs. Barrier-synchronized waves suffer when a vCPU holding a cell is
  descheduled. Pairwise probes found no shared cores between specific vCPUs, so the guest cannot
  identify siblings; the host topology must be exposed to the guest.
- **Binding the server to one NUMA node made no measurable difference** at 24 workers (72.1/70.7
  unbound versus 74.6/70.8 bound), so cross-socket memory placement is not the current limit.
- Configure `workers` explicitly on this VM (about 32); `0` resolves to 72 and oversubscribes.

Evidence (JFR files, console logs, `results.json`) was kept outside the repository with the run.
Untested here: real players and packet load, redstone/hoppers/fluids, exploration and chunk
generation, modpack content, and a server without Vanadium.

### Worker wake-up follow-up (2026-11-08)

On `9781c93` plus the experimental worker wake-up changes, six fresh JVMs ran in order
100µs, 2ms, tick-warm, tick-warm, 2ms, 100µs. Temporary per-cell counters were removed
before every reported run. The tick-warm arm woke all workers at tick start and kept them
spinning until tick end, with a 100µs idle window otherwise; this experiment was removed.
The retained change sets the bounded worker idle spin default to 2ms. The claim protocol,
barriers, caller participation and 200µs caller tail spin are unchanged.

Environment: dual E5-2699 v3 KVM guest, 72 vCPUs (currently exposed as 2 sockets × 18 cores ×
2 threads), 2 NUMA nodes, unbound placement; OpenJDK 25.0.4.1 from `nix develop`, 32 helpers,
cell size 2, all default stage flags enabled, C2ME parallelism 4, 4GiB fixed G1 heap.
Minecraft 26.2, Loader 0.20.1, Fabric API 0.160.0+26.2, Kotlin runtime
1.14.2+kotlin.2.4.21, Cloth Config 26.2.155, C2ME 0.4.2-alpha.0.52. Same seed-12345 flat
world recipe as above: 1,024 forced chunks, no players, then 4,096 persistent AI zombies.
Each JVM settled for 15s before the chunk-only measurement and 5s after summoning zombies.
Each uncapped measurement lasted 30s, followed by a separate 10s profile at 20 TPS.

| Worker idle policy | Zombie TPS, two JVMs | Mean TPS | Median tick work, two profiles | p95 tick work, two profiles |
|---|---|---:|---|---|
| 100µs | 86.3 / 84.6 | 85.5 | 11.719 / 11.970ms | 14.378 / 13.754ms |
| 2ms | 102.3 / 98.7 | 100.5 | 11.135 / 10.469ms | 16.552 / 12.902ms |
| Whole tick warm | 97.9 / 94.5 | 96.2 | 10.971 / 10.776ms | 13.432 / 13.318ms |

The 2ms window increased mean zombie throughput **17.6%** over 100µs and was faster than
tick-warm in both runs. This is a throughput result, not evidence of improved p95: tail
results vary. Two JVMs per arm are insufficient to quantify confidence or modpack behavior.

The harness now measures process CPU during normal-rate profiles. This instrumentation was
added during the sweep, so only the second JVM per arm has these fields:

| Policy | Chunk-only CPU cores at 20 TPS | Zombie CPU cores at 20 TPS |
|---|---:|---:|
| 100µs | 0.518 | 5.948 |
| 2ms | 1.570 | 7.462 |
| Whole tick warm | 1.099 | 7.278 |

These are summed process CPU seconds divided by elapsed capture wall time (about 10.5s),
including compiler and GC threads and profile overhead. The longer window costs about
1.5 additional CPU cores at 20 TPS with zombies; during uncapped runs process CPU rose
from about 25 to 32 cores. Use `-Dvanadium.workerSpinMicros=100` when CPU economy matters
more than peak throughput. This setting does not change the number of workers.

Second-JVM chunk-only throughput was 1,134 / 1,208 / 1,080 TPS for 100µs / 2ms / tick-warm.
The first baseline chunk-only run overlapped test compilation and is excluded from this
comparison; zombie runs and all other arms had no concurrent build/test job.
Evidence is retained under `.vanadium/wake-study/{0-base,1-spin,2-warm,3-warm,4-spin,5-base}/`
(`results.json`, console logs); isolated worlds and full server logs are under `.vanadium/runs/`.
Reproduce the retained policies with separate invocations of:

```sh
nix develop -c python3 scripts/bench-sweep.py --workers 32 --radius 16 --settle 15 \
  --workloads idle,mobs --jvm=-Dvanadium.workerSpinMicros=2000 --out .vanadium/spin-2000
# Repeat with 100, reversing order in a second pair of JVMs.
```

The tick-warm API, JVM experiment switch, and always-on wave statistics daemon were removed.
No entity/chunk/world locking or Minecraft injection targets changed. Real players, chunk
generation, modpack load and performance on the Ryzen remain untested for this default.

Validation of the retained default: `nix develop -c ./gradlew build --rerun-tasks` passed
(140 tests, no failures; access widener validated), Python syntax compilation and
`git diff --check` passed. `nix develop -c python3 scripts/dev-server.py smoke` passed
startup, ticking, save and shutdown with the final sources; evidence remains in
`.vanadium/runs/smoke-t24im3jr/`. The smoke used auto workers (72), not the 32-helper
performance configuration. No JFR capture was taken in this sweep; the historical
wake-latency counters motivated the experiment but are not retained as production instrumentation.


### Chunk infrastructure clarification (2026-11-09)

Loaded-FULL lookups are now unconditional, including with `enabled=false`; the optional
`chunkCache` setting was removed. Earlier on/off measurements describe the older code and
must not be interpreted as measurements of today's disabled mode. The renamed
`ParallelChunkCache`, `ChunkTickBatch`/`ChunkTickSink`, and internal `WorkerPool.WorkBatch`
are documented in [the chunk infrastructure ownership notes](ARCHITECTURE.md#chunk-infrastructure).

Validation: focused tick/config/chunk tests with `--rerun-tasks` and the full build passed
(140 tests, no failures). `scripts/check-block-updates.py` passed the live suite and its
startup/tick/save/shutdown smoke, including new loaded-chunk checks for both lock policies,
enabled/disabled modes, negative positions, status-specific misses, replacement publication,
stale-instance unload, removal, and real synchronous loads. Evidence remains in
`.vanadium/runs/smoke-0qd09gns/`. Bytecode inspection confirmed primitive-key loaded lookups;
the release jar contains the renamed classes and no copies under their retired names.

A JFR validation capture used the same dual Xeon E5-2699 v3 KVM guest (72 vCPUs), OpenJDK
25.0.4.1, Minecraft 26.2, Loader 0.20.1, Fabric API 0.160.0+26.2, Kotlin runtime
1.14.2+kotlin.2.4.21, Cloth Config 26.2.155, and pinned C2ME 0.4.2-alpha.0.52. It used
32 helpers, cell size 2, 2ms worker idle spin, C2ME parallelism 4, unbound placement, a fixed
4GiB G1 heap, and default enabled features. A fresh seed-12345 flat world had 256 forced
chunks and 1,024 persistent zombies, no players, with spawning/daylight/weather cycles off.
The recording covered a 30s tick sprint and a separate 10s profile at 20 TPS, with monitor
and park thresholds of 100µs and execution sampling every 5ms. There were no monitor-enter
events with chunk lookup/load frames; the only monitor-enter event was JFR's own
`PlatformRecorder` (14.6ms). This is a contention check, not a before/after speedup result
or evidence about cold concurrent generation, multiplayer, or modpack safety.

The initial recording attempt exposed a harness bug: relative JFR filenames were resolved
against the isolated server directory and recording failure was unchecked. The harness now
resolves output directories absolutely, checks the start response, and verifies the recording
file after stopping. The successful capture and monitor summary are in
`.vanadium/chunk-infrastructure-jfr-verified/`; the server run is
`.vanadium/runs/sweep-w32-c2-qx48qr3a/`. Pinned C2ME chunk-cache mixins were also inspected;
the remaining vanilla four-entry cache path is still an explicit follow-up audit.
