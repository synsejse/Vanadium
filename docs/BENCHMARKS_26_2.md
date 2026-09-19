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
