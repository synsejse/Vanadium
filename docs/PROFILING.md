# Profiling Vanadium

Use the JDK tools supplied by `nix develop`, or open recordings in JDK Mission Control.
Performance measurements must hold worker count, isolation, C2ME budget, heap, flags,
mods and workload fixed. `enabled=false` keeps structural mixins and loaded-chunk lookups;
it is not a no-Vanadium control.

## In-game commands

- `/vanadium benchmark 30` measures uncapped whole-server throughput using tick sprint.
- `/vanadium profile 30` captures tick/stage distributions at the current tick rate.
- `/vanadium profile report` shows the last completed capture.

What the reports contain, how their measurements overlap and where the `VANADIUM_RESULT` JSON goes
are in [configuration and commands](CONFIGURATION.md#reports).

## Flight Recorder

Use the Minecraft JVM PID from `jcmd -l`, not the Gradle daemon PID. Always use an absolute
recording filename:

```sh
jcmd <pid> JFR.start name=vanadium settings=profile duration=60s filename=/absolute/path/vanadium.jfr 'jdk.JavaMonitorEnter#threshold=100us' 'jdk.ThreadPark#threshold=100us' 'jdk.ExecutionSample#period=5ms'
jcmd <pid> Thread.print
jfr summary /absolute/path/vanadium.jfr
jfr print --events 'vanadium.*,jdk.JavaMonitorEnter,jdk.ThreadPark' /absolute/path/vanadium.jfr
```

Vanadium emits `vanadium.ServerTick` (the server's tick work, from Fabric's `START_SERVER_TICK` to
`END_SERVER_TICK`), `vanadium.Wave` (one stage wave: stage, world, regions, tasks, participants and
isolation) and `vanadium.Preparation` (independent jobs), all on the server thread. They are
durations of work. A completion gap, the time between two ticks' completions that the live fixtures
report, also holds the pacing wait, tasks the server thread runs between ticks and JVM stops before
the tick, so a long gap can hold a short `ServerTick`; see [tick timing](#tick-timing) for the
fixtures' clocks. Correlate these intervals with worker samples, monitor blocking, parks and
allocation samples in Mission Control. A wave's duration includes its lock waits and does not
establish CPU utilization by itself.

`./gradlew runIntegration -Plive.jfr=true` captures the isolated live suite with 100µs
monitor thresholds. `./gradlew runFeatureBench` writes separate on/off JFR recordings and
paired timing/allocation CSV files. See [development](DEVELOPMENT.md) for filters and scale.
Feature timing samples exclude active JFR and are subsystem measurements rather than
multiplayer TPS or p99 latency. Published chart inputs and workload limits are documented
in [performance evidence](PERFORMANCE.md).

## Mixed whole-server benchmark

`./gradlew runMixedBench` starts a fresh dedicated server (with the pinned C2ME and Lithium) in
`.vanadium/runs/mixedBench-UUID/` and measures a workload shaped like a small survival server.
It is manual only: it needs the accepted `.vanadium/eula.txt`, takes several minutes, and is never
part of `build` or `runIntegration`. Run one live server at a time.

Workload, identical in both modes for the same properties:

- Generated terrain (seed 12345, normal world type, difficulty normal) with natural spawning,
  the day–night cycle and default game rules, except fixed clear weather and `mob_griefing` off.
  Every run starts at dusk (time 12000) once chunk loading has settled.
- `mixed.bases` bases 384 blocks apart. Each has a 32×32 platform above the terrain with twelve
  furnaces fed by hoppers from input and fuel chests and emptied by hoppers into output chests,
  two circulating hopper rings (24 hoppers), a repeater clock lighting ten lamps and firing ten
  pistons every 32 ticks, a hydrated 9×9 wheat farm, a fenced pen with sixteen animals, and four
  villagers with beds, job sites and a bell.
- `mixed.players` real `ServerPlayer`s over the mocked transport (`live/TestPlayer.kt`), assigned to
  bases in turn. The first round circles its base at walking speed; the next round sprints 192 blocks
  out and back, unloading chunks behind it and reloading them from disk. Routes are stone roads above
  the terrain so straight moves pass vanilla validation. Players are survival players with
  resistance, regeneration and saturation: mobs target and hit them, but none dies or starves.
- Setup generates bases and routes, players join, and readiness waits at 20 TPS until the players
  entity-tick and the loaded-chunk count is steady for five seconds. Warmup (default 1,600 ticks)
  covers each explorer's first round trip, which generates its view corridor; measured windows
  then mostly load visited chunks from disk.

Windows are fixed numbers of ticks, so both modes advance the world equally. Without `mixed.sprint`
the server runs at its normal 20 TPS pacing (tick time and spare capacity matter, TPS drops only when
overloaded); with `mixed.sprint=true` it runs uncapped (throughput).

| Property | Default | Meaning |
|---|---|---|
| `mixed.mode` | `enabled` | `enabled`, or `serial` (`enabled = false`; structural mixins remain, not a no-mod control) |
| `mixed.players` / `mixed.bases` | 8 / 4 | simulated players and bases |
| `mixed.warmup` / `mixed.window` / `mixed.windows` | 1600 / 1200 / 5 | ticks before measuring, ticks per window, window count |
| `mixed.sprint` | `false` | uncapped ticking during warmup and windows |
| `mixed.workers` / `mixed.isolation` | 0 / 2 | `vanadium.toml` values (0 = automatic worker count) |
| `mixed.parallelSpawning` | `true` | `false` writes `parallelSpawning = false` (serial spawning arm; needs `mixed.mode=enabled`) |
| `mixed.c2me` | C2ME default | `globalExecutorParallelism` |
| `mixed.viewDistance` / `mixed.simulationDistance` | 8 / 6 | server view and simulation distance |
| `mixed.heap` / `mixed.jvmArgs` | `4g` / none | `-Xms`/`-Xmx` and extra JVM flags (space-separated; collector flags are rejected, use `mixed.gc`) |
| `mixed.gc` | `g1` | `g1` (`-XX:+UseG1GC`) or `zgc` (`-XX:+UseZGC`, generational) |
| `mixed.gcLog` | `false` | GC and safepoint logging into the run's `gc.log` (see below); `-Xloggc` and `-Xlog` flags in `mixed.jvmArgs` that name `gc.log` or `disable` are then rejected |
| `live.jfr` | `false` | record into the run's `live.jfr` (100µs monitor/park thresholds) from `mixed.jfrLead` ticks before the first window to the last |
| `mixed.jfrLead` | 200 | ticks of recording before the first window, at least 1; with a warmup shorter than the lead (including `mixed.warmup=0`) the recording starts at the end of setup, before readiness |

Outputs in the run directory:

- `mixed-bench/windows.csv`: per window, ticks, wall time, TPS, vanilla tick time (mean, p50, p95,
  p99, max: the `tickServer` duration MSPT tools show), process CPU and Java-thread CPU by role
  (server thread, Vanadium workers, C2ME, other), CPU per tick, busy cores, allocation, GC count and
  time (below), entity counts by category and loaded chunks at the window's end, and workload health:
  redstone lamp toggles, player position corrections and walker stalls in the window (all expected
  near 0 except toggles, about 6 per base per 100 ticks), and items smelted so far.
- `mixed-bench/threads.csv`: CPU and allocation per thread over all windows.
- `mixed-bench/entities.csv`: entity types, and item entities by item, at the end.
- `mixed-bench/ticks.csv`: every tick from the end of setup to the last measured tick (readiness,
  warmup and windows; none excluded): `phase` (`ready`, `warmup`, `measure`), `window` (-1 outside
  windows), `tick_id` (`MinecraftServer.tickCount`), `tick_ns` (vanilla `tickServer` duration) and
  `end_uptime_ns` (completion, below). Buffered as primitives and written at each boundary.
- `mixed-bench/boundaries.csv`: `setup_end`, `jfr_start`, `ready_end`, `measure_start` and each
  `window_end`, with the last completed tick, its completion, when the boundary's server-thread work
  began (`mark_uptime_ns`), the time from then until its row (`work_before_row_ns`: tick and window
  files, the counter snapshot ending a window, starting JFR) and wall-clock `epoch_us`. That is not
  all of the boundary's work: writing the row and its JFR event, the next window's counter snapshot
  and, after the last window, `threads.csv`, `entities.csv` and the result come after it.
- `gc.log` (with `mixed.gcLog=true`): `-Xlog:gc*,safepoint:file=gc.log:time,uptime,uptimenanos,level,tags:filecount=0`.
- `vanadium/results/mixed-bench-*.json`: the same windows with settings, JVM arguments, collectors,
  versions and the Vanadium configuration (also logged as a `VANADIUM_RESULT` line). `MIXED_BENCH_WINDOW`
  lines in the log repeat each row.

A run passes only when every one of these files exists with at least its expected rows (one per
window, header plus every warmup and measured tick, every boundary), plus `gc.log` when requested and
a non-empty `live.jfr` with `live.jfr=true`. Output write errors fail the run.

`gc_count`/`gc_ms` sum every `GarbageCollectorMXBean`. Under G1 these are young, concurrent-cycle and
full collections; under ZGC the cycle beans and the pause beans of those same cycles are added
together, so the columns overlap and are neither pause counts nor pause time. Compare GC pauses
between collectors (or runs) with `gc.log` or JFR's GC pause events, not these columns.

A stalled tick or setup stops the run after five minutes (`max-tick-time`) with vanilla's crash
report, which includes every thread's stack and held monitors.

When the server stops before the last window (a crash, including the fixture's own, or a shutdown
request such as SIGTERM, which Gradle's task timeout sends), the fixture logs `MIXED_BENCH_ABORTED`,
appends the ticks buffered since the last boundary to `ticks.csv`, stops JFR into `live.jfr` and
closes its files, best effort; the crash report keeps the original error and the run does not pass.
There is no periodic flushing: a server thread that never returns (the watchdog's stall exit) or a
halted or killed JVM loses the ticks since the last boundary. The recording is then written only by
the JDK's exit hook (`System.exit`, SIGTERM; the watchdog allows it 10 seconds), never on `halt` or
SIGKILL.

To compare modes, alternate fresh runs with identical properties, for example
`enabled, serial, serial, enabled` repeated, and compare per-window distributions (tick p50/p95,
CPU per tick, busy cores) rather than single means; whole-server timings vary noticeably between
runs, so use several runs per mode. Mob AI, spawning and villager behaviour are random, so
entity counts differ slightly between runs; check them and the health columns before comparing.
For tail work, run with `-Pmixed.gcLog=true` and match slow ticks or long gaps (`ticks.csv`) to
`gc.log` lines by uptime. Short smoke settings (for example
`-Pmixed.warmup=200 -Pmixed.window=200 -Pmixed.windows=2`) only check the harness.

### Tick timing

Columns appended to `windows.csv` after the health columns, and the clocks behind them:

- Clock: `*_uptime_ns` values are `System.nanoTime()` converted to JVM uptime, the clock of unified
  logging's `uptimenanos` decoration, so `gc.log` pauses and safepoints line up with ticks directly.
  The offset is calibrated once at setup (`MIXED_BENCH_CLOCK` line; uncertainty in the result JSON,
  normally well under a microsecond).
- Completion: when the fixture's `END_SERVER_TICK` hook starts, after vanilla's `tickServer` returns
  its duration and after earlier-registered end-of-tick listeners. A tick's start is approximately
  `end_uptime_ns - tick_ns`.
- Window boundary `t0`: the completion of the tick before the window (the last warmup tick, the
  readiness tick when `mixed.warmup=0`, or the previous window's last tick). Windows tile the timeline:
  each tick belongs to exactly one phase or window. Boundary work runs on the server thread after
  `t0`, so it is inside the next window's first gap (partly in its `work_before_row_ns`), not excluded.
- `completion_span_ms`/`completion_tps`: last completion minus `t0`, and ticks over that span.
  `wall_ms`/`tps` keep their earlier definition (counter snapshots at the boundaries).
- `max_gap_ms`/`max_gap_tick`: the longest interval between consecutive completions (the first starts at
  `t0`) and the tick that ends it. A gap holds the tick, the pacing wait and anything else the server
  thread did in between, so stalls outside `tickServer` show here but not in `tick_max_ms`.
- `ticks_over_50ms`/`ticks_over_100ms`: ticks whose `tickServer` duration is strictly greater;
  `tick_max_id` is the longest one. `first_tick`/`last_tick` give the window's tick IDs.
- `rolling_tps_min_20t`/`rolling_tps_min_100t`: the lowest `k / (t(j) - t(j-k))` over every complete
  run of `k` consecutive completions inside the window (the first starts at `t0`; none crosses a window
  boundary). Empty when the window has fewer than `k` ticks.

For attribution, `live.jfr` also contains `vanadium.MixedBenchTick` (tick ID, phase, window, duration and
completion uptime) and `vanadium.MixedBenchBoundary` instant events, next to `vanadium.ServerTick`.
Starting the recording happens `mixed.jfrLead` ticks ahead (at least one, so it is never in the first
window's first gap), at the end of a readiness or warmup tick, or at the end of setup when the warmup is
shorter than the lead; `jfrRecordedLeadTicks` in the JSON is the lead it actually got. Startup and first-use effects (class loading, mixin transforms
such as the first explosion) are not preloaded or excluded: the first window may contain them, and
the per-tick data shows where. [Cold first-use explosion](#cold-first-use-explosion) isolates the first explosion.

### Spawn contention arms

The 4338adb profile found spawn attempts bound by vanilla's shared spawn-state monitor
(`NaturalSpawner$SpawnState.canSpawnForCategoryLocal`, synchronized by `SyncAllMixin`) at 25
participants. These arms measure how spawning time and whole-tick time move with fewer threads.
Every arm is a fresh `runMixedBench` with otherwise identical properties; the result JSON
records `spawnArm`, `parallelSpawning`, `spawnParticipants` (also `environment.spawnParticipants`)
and the JVM arguments, and `MIXED_BENCH_SETUP` logs `spawnArm`.

| `spawnArm` | Flags | Spawn count | Spawn attempts |
| --- | --- | --- | --- |
| `participants-auto` | none (current default) | parallel `SPAWN_COUNTS` | scheduler estimate (25 in the 4338adb profile) |
| `participants-8` / `-4` / `-2` | `-Pmixed.jvmArgs=-Dvanadium.spawnParticipants=8` (4, 2) | parallel | waves offered at most 8 (4, 2) threads, server thread included |
| `participants-1` | `-Pmixed.jvmArgs=-Dvanadium.spawnParticipants=1` | parallel | spawning wave inline on the server thread, region plan order, thunder in its region |
| `serial-spawning` | `-Pmixed.parallelSpawning=false` | vanilla serial `createState` | vanilla's `tickSpawningChunk` inline in its shuffled order: each chunk's thunder, then its checks and attempt |

The cap is an upper bound on the threads a spawning wave is offered, not a guaranteed occupancy;
[Architecture](ARCHITECTURE.md#spawning-participants) says what it bounds and what it leaves alone.

`participants-1` keeps the parallel count and bounds only the spawning wave. `serial-spawning` is the
genuine serial path, and it differs from `participants-1` in more than the count. The count runs
vanilla's `createState` on the server thread, so its time moves from the `SPAWN_COUNTS` preparation
into the `SPAWNING` preparation phase of tick profiles (the parallel count merges in source order to
match it). Execution differs too: attempts run inside vanilla's chunk loop in its shuffled order
rather than as wave regions in plan order, each chunk's thunder runs inline just before that chunk's
checks and attempt rather than in its region, and there is no deferral path at all, while
`participants-1` still goes through the wave's deferral wrapper (on the inline caller it never
defers). A difference between the two arms therefore cannot be attributed solely to counting; the
`SPAWN_COUNTS` and `SPAWNING` preparation phases show the count's own time. Neither arm defers
spawning (no `ServerThreadRequired`): the server thread may block on structure-start chunks.
Combine extra JVM flags in one `mixed.jvmArgs` value.

Commands (one live server at a time; alternate arms, for example auto, 4, 1, serial-spawning,
then the reverse order, at least four runs per arm):

```sh
./gradlew runMixedBench --no-configuration-cache                                   # participants-auto
./gradlew runMixedBench --no-configuration-cache -Pmixed.jvmArgs=-Dvanadium.spawnParticipants=8
./gradlew runMixedBench --no-configuration-cache -Pmixed.jvmArgs=-Dvanadium.spawnParticipants=4
./gradlew runMixedBench --no-configuration-cache -Pmixed.jvmArgs=-Dvanadium.spawnParticipants=2
./gradlew runMixedBench --no-configuration-cache -Pmixed.jvmArgs=-Dvanadium.spawnParticipants=1
./gradlew runMixedBench --no-configuration-cache -Pmixed.parallelSpawning=false    # serial-spawning
```

Evidence per arm: `windows.csv` tick p50/p95/p99 and CPU per tick; entity counts by category
(fewer threads must not change mob totals beyond run-to-run spread, since the spawn rules are
unchanged); with `-Plive.jfr=true` on one run per arm, `WaveEvent` spawning durations and
recorded participants (never above the arm's cap; lower when the estimate is), `PreparationEvent` `SPAWN_COUNTS`, and
monitor-enter time on the spawn state. Keep `participants-auto` as the default until the
arms show a winner across runs.

## Cold first-use explosion

The 4338adb profile's worst enabled tick (275.8 ms) was the JVM's first `ServerExplosion`: one worker
ran the class's Mixin/ASM transform and the explosion as the last task of the flat entity wave while
the server thread waited. `./gradlew runColdExplosion` reproduces only that: the first ordinary
creeper explosion in a fresh JVM against warmed ones on the same path. It is manual only (accepted
`.vanadium/eula.txt`, about a minute of ticks, never part of `build` or `runIntegration`); run one
live server at a time. It changes nothing in Vanadium and preloads nothing.

The path is a creeper's own fuse, ticked in the flat entity wave (TNT ticks serially before the wave
and cannot reach that route). The fixture world is the isolated flat world, force-loaded around one
creative observer player (mocked transport) on a pillar. Twelve identical arenas sit on a ring 32
blocks from the observer, each with a no-AI creeper and a no-AI pig five blocks outward that the blast
damages. A fenced pen of `cold.filler` AI cows inside the ring makes the entity wave big enough to
offer workers. Natural spawning, patrols, traders, weather and time are off, the time is noon,
difficulty normal and `mob_griefing` off (explosions keep blocks, as in the mixed bench). Setup hurts a
temporary pig with explosion and generic damage, so entity damage is warm in every arm.

Ticks after readiness (observer loaded, every arena entity-ticking, loaded chunks unchanged for
five seconds) follow a fixed schedule, identical in every arm:

| Tick | `sham` | `warmup` | `load` |
| ---: | --- | --- | --- |
| 100 | nothing | ignite arena 0's creeper | `Class.forName(ServerExplosion)` (load and initialise, no explosion) on the server thread |
| 130 | discard arena 0's creeper | arena 0 explodes (warmup blast) | discard arena 0's creeper |
| 300 + (k − 1) × spacing | ignite arena k (k = 1..`cold.blasts`) | same | same |
| ignition + 30 | arena k explodes | same | same |

Blast 1 is the JVM's first explosion in `sham` and `load`, and its second in `warmup`. Blasts 2..N are
warm in every arm and give each run its own warm baseline.

A test-only mixin on `ServerLevel.explode` (`ExplosionObserver`, in the fixture jar) times every server
explosion on its own thread. `ServerLevel` is transformed at startup, so the observer adds nothing to
`ServerExplosion`'s first load. It is primed at setup. The timed span covers constructing
`ServerExplosion` (its first load and transform), `explode()` and the explosion packets. The run fails
on any explosion that is not an arena creeper's, on a creeper exploding other than once (or other
than 30–32 ticks after ignition), or when the JVM's first explosion is not the one the arm promises.

| Property | Default | Meaning |
|---|---|---|
| `cold.arm` | `sham` | `sham`, `warmup` or `load` (above) |
| `cold.blasts` | 8 | measured blasts, 2–11 |
| `cold.spacing` | 100 | ticks between measured ignitions, at least 60 |
| `cold.filler` | 200 | AI cows in the pen, 0–400 (0 leaves a tiny entity wave, likely on the server thread alone) |
| `cold.mode` | `enabled` | `serial` sets `enabled = false` (structural mixins stay, not a no-mod control) |
| `cold.workers` | 0 | `vanadium.toml` workers (0 = automatic); isolation 2, C2ME default, 2 GiB G1 heap |
| `cold.classLog` | `false` | `-Xlog:class+load=info:file=class-load.log:uptimenanos` in the run directory |
| `live.jfr` | `false` | `live.jfr` from readiness to the end, with `jdk.ClassLoad`/`jdk.ClassDefine` and the fixture's `vanadium.ColdExplosion` events |

Outputs in `.vanadium/runs/coldExplosion-UUID/`:

- `cold-explosion/blasts.csv`: one row per blast (`blast` 0 is the warmup blast, then arenas 1..N):
  `first_in_jvm`, ignition and blast tick IDs, `fuse_ticks`, the exploding `thread` and `route`
  (`entity_wave_worker`, `entity_wave_caller` or `outside_entity_wave`), `explode_ms` and its
  start/end on the JVM-uptime clock, `baseline_p50_ms` (median `tickServer` time of the 20 ticks up to
  ignition), `prime_tick_ms` (the tick after ignition: the fuse's first sound and game event),
  `blast_tick_ms` (vanilla `tickServer` duration of the blast tick), `blast_excess_ms` (blast tick minus
  baseline), `blast_gap_ms` (completion gap), `next1_ms`..`next5_ms`, and with `cold.classLog`
  `classes_in_explode`/`classes_in_gap` (classes loaded during the explosion and during the tick's gap).
- `cold-explosion/ticks.csv`: every tick from setup to the end (`tick_id`, `tick_ns`, `end_uptime_ns`,
  as in the mixed bench's `ticks.csv`). Written at the end only: a failed run keeps none.
- `cold-explosion/blast-classes.csv` (with `cold.classLog`): every class loaded in each blast tick's gap,
  its uptime and whether it fell inside the explosion.
- `vanadium/results/cold-explosion-*.json` (and its `VANADIUM_RESULT` line): settings, `armTick`,
  `armActionMs` (the load-only control's `forName` time), `serverExplosionLoadUptimeNs`/`Tick` (from the
  class log: where `ServerExplosion` was actually first loaded), worker count, JVM arguments, every blast,
  and `summary` (first measured against the median warm blast, explosion and tick time, and the first
  blast's route). `COLD_EXPLOSION_BLAST` and `COLD_EXPLOSION_SUMMARY` log lines repeat them.

Commands (alternate arms in fresh JVMs, for example `sham, warmup, warmup, sham` repeated; whole-server
timings vary a lot between launches, so at least four runs per arm):

```sh
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=sham
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=warmup
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=load                         # control, at least two runs
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=sham -Pcold.classLog=true    # attribution, separate runs
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=sham -Plive.jfr=true         # attribution, separate runs
./gradlew runColdExplosion --no-configuration-cache -Pcold.arm=sham -Pcold.mode=serial      # same blasts off the entity wave
```

Reading the arms (report each run's values, medians and ranges, not single runs):

- Reproduced: in `sham` runs, blast 1's `explode_ms` and `blast_excess_ms` exceed that run's warm blasts
  (2..N) by more than the warm blasts vary across runs, and `warmup` runs' blast 1 falls within the warm
  range. The paired difference of blast 1 (`sham` minus `warmup`, by alternation pair) is the first-use
  cost under these settings. Compare `route` too: the 4338adb straggler was `entity_wave_worker`; a
  blast on the caller blocks the server thread directly instead of through the wave's completion.
- Attribution: if `load` runs' blast 1 is within the warm range, loading and transforming `ServerExplosion`
  (`armActionMs`) accounts for the cost. What remains above warm in `load` is other first use: the
  classes in `blast-classes.csv` (packets, damage calculators, Lithium helpers) and first execution.
  `serverExplosionLoadTick` identifies the tick that ends the completion gap containing the load.
  It should be the blast tick in `sham` and `armTick + 1` in `load`, since the load-only action
  runs after the arm tick's completion timestamp. An earlier load means the class was already warm.
- The stall's extent: `next*_ms` against the baseline say whether it is one tick or more.

Limitations:

- Development launch: Loom's run tasks start the server with `fabric.development` and a log4j
  configuration that writes DEBUG to `logs/debug.log`, including Mixin's per-mixin `Mixing ...` lines
  written during the transform. `runColdExplosion`, like `runMixedBench`, measures that launch only.
  The packaged fixture jars were run by hand under a production Fabric launch on 2026-11-18; see the
  [release follow-up](PERFORMANCE.md#release-follow-up).
- `cold.classLog` and JFR add their own cost to every class load (JFR noticeably more): use them for
  attribution in separate runs and take timings from runs without them.
- Host effects (frequency, scheduling, the dual-socket VM) are outside the instrumentation, and the
  mocked transport records packets without encoding them, so packet serialization is not included.
- One explosion type (an uncharged creeper with `mob_griefing` off, so no block destruction or drops)
  and one fixture world; cow movement is random, the explosion schedule is not.
