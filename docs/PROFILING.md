# Profiling Vanadium

Use the JDK tools supplied by `nix develop`, or open recordings in JDK Mission Control.
Performance measurements must hold worker count, isolation, C2ME budget, heap, flags,
mods and workload fixed. `enabled=false` keeps structural mixins and loaded-chunk lookups;
it is not a no-Vanadium control.

## In-game commands

- `/vanadium benchmark 30` measures uncapped whole-server throughput using tick sprint.
- `/vanadium profile 30` captures tick/stage distributions at the current tick rate.
- `/vanadium profile report` shows the last completed capture.

Results are logged as `VANADIUM_RESULT` JSON lines and written under `vanadium/results/`
in the server directory, including configuration, versions, heap and processor count.
World totals include stage work; do not sum overlapping measurements. Caller tail wait
is not a measurement of lock contention.

## Flight Recorder

Use the Minecraft JVM PID from `jcmd -l`, not the Gradle daemon PID. Always use an absolute
recording filename:

```sh
jcmd <pid> JFR.start name=vanadium settings=profile duration=60s filename=/absolute/path/vanadium.jfr 'jdk.JavaMonitorEnter#threshold=100us' 'jdk.ThreadPark#threshold=100us' 'jdk.ExecutionSample#period=5ms'
jcmd <pid> Thread.print
jfr summary /absolute/path/vanadium.jfr
jfr print --events 'vanadium.*,jdk.JavaMonitorEnter,jdk.ThreadPark' /absolute/path/vanadium.jfr
```

Vanadium emits `vanadium.ServerTick` (work excluding idle), `vanadium.Wave` (stage, world,
regions, tasks, participants and isolation), and `vanadium.Preparation` (independent jobs).
Correlate these intervals with worker samples, monitor blocking, parks and allocation
samples in Mission Control. The serial remainder of tick work bounds potential scaling.
Region duration includes lock waits and does not establish CPU utilization by itself.

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
| `mixed.gcLog` | `false` | GC and safepoint logging into the run's `gc.log` (see below) |
| `live.jfr` | `false` | record into the run's `live.jfr` (100µs monitor/park thresholds) from `mixed.jfrLead` ticks before the first window to the last |
| `mixed.jfrLead` | 200 | ticks of recording before the first window; with a shorter warmup the recording starts at the end of setup |

Outputs in the run directory:

- `mixed-bench/windows.csv`: per window, ticks, wall time, TPS, vanilla tick time (mean, p50, p95,
  p99, max: the `tickServer` duration MSPT tools show), process CPU and Java-thread CPU by role
  (server thread, Vanadium workers, C2ME, other), CPU per tick, busy cores, allocation, GC count and
  time, entity counts by category and loaded chunks at the window's end, and workload health:
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
  began (`mark_uptime_ns`), how long it took (`work_ns`: snapshots, file writes, starting JFR) and
  wall-clock `epoch_us`.
- `gc.log` (with `mixed.gcLog=true`): `-Xlog:gc*,safepoint:file=gc.log:time,uptime,uptimenanos,level,tags:filecount=0`.
- `vanadium/results/mixed-bench-*.json`: the same windows with settings, JVM arguments, collectors,
  versions and the Vanadium configuration (also logged as a `VANADIUM_RESULT` line). `MIXED_BENCH_WINDOW`
  lines in the log repeat each row.

Tick timing (columns appended to `windows.csv` after the health columns):

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
  `t0`, so it is inside the next window's first gap (and in its `work_ns`), not excluded.
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
Starting the recording happens `mixed.jfrLead` ticks ahead, not inside a window; `jfrRecordedLeadTicks`
in the JSON is the lead it actually got. Startup and first-use effects (class loading, mixin transforms
such as the first explosion) are not preloaded or excluded: the first window may contain them, and
the per-tick data shows where.

A stalled tick or setup stops the run after five minutes (`max-tick-time`) with vanilla's crash
report, which includes every thread's stack and held monitors.

To compare modes, alternate fresh runs with identical properties, for example
`enabled, serial, serial, enabled` repeated, and compare per-window distributions (tick p50/p95,
CPU per tick, busy cores) rather than single means; whole-server timings vary noticeably between
runs, so use several runs per mode. Mob AI, spawning and villager behaviour are random, so
entity counts differ slightly between runs; check them and the health columns before comparing.
Record the hardware, flags and properties with any published result. For tail work, run with
`-Pmixed.gcLog=true` and match slow ticks or long gaps (`ticks.csv`) to `gc.log` lines by uptime.
Short smoke settings (for example
`-Pmixed.warmup=200 -Pmixed.window=200 -Pmixed.windows=2`) only check the harness.

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

The cap is an upper bound on the threads a spawning wave is offered, not a guaranteed occupancy:
the wave gets the smaller of the scheduler's estimate and the cap (that is what `WaveEvent` and tick
profiles record), a one-region wave runs inline whatever the cap, and offered workers that find no
claimable region stay idle. With parallel spawning the spawning wave also carries the thunder of
ticking chunks, so the cap bounds that thunder too.

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
