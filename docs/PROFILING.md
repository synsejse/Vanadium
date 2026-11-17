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
| `mixed.c2me` | C2ME default | `globalExecutorParallelism` |
| `mixed.viewDistance` / `mixed.simulationDistance` | 8 / 6 | server view and simulation distance |
| `mixed.heap` / `mixed.jvmArgs` | `4g` / none | `-Xms`/`-Xmx` and extra JVM flags (space-separated) |
| `live.jfr` | `false` | record the measured windows only into the run's `live.jfr` (100µs monitor/park thresholds) |

Outputs in the run directory:

- `mixed-bench/windows.csv`: per window, ticks, wall time, TPS, vanilla tick time (mean, p50, p95,
  p99, max: the `tickServer` duration MSPT tools show), process CPU and Java-thread CPU by role
  (server thread, Vanadium workers, C2ME, other), CPU per tick, busy cores, allocation, GC count and
  time, entity counts by category and loaded chunks at the window's end, and workload health:
  redstone lamp toggles, player position corrections and walker stalls in the window (all expected
  near 0 except toggles, about 6 per base per 100 ticks), and items smelted so far.
- `mixed-bench/threads.csv`: CPU and allocation per thread over all windows.
- `mixed-bench/entities.csv`: entity types, and item entities by item, at the end.
- `vanadium/results/mixed-bench-*.json`: the same windows with settings, JVM arguments, versions and
  the Vanadium configuration (also logged as a `VANADIUM_RESULT` line). `MIXED_BENCH_WINDOW` lines
  in the log repeat each row.

A stalled tick or setup stops the run after five minutes (`max-tick-time`) with vanilla's crash
report, which includes every thread's stack and held monitors.

To compare modes, alternate fresh runs with identical properties, for example
`enabled, serial, serial, enabled` repeated, and compare per-window distributions (tick p50/p95,
CPU per tick, busy cores) rather than single means; whole-server timings vary noticeably between
runs, so use several runs per mode. Mob AI, spawning and villager behaviour are random, so
entity counts differ slightly between runs; check them and the health columns before comparing.
Record the hardware, flags and properties with any published result. Short smoke settings (for example
`-Pmixed.warmup=200 -Pmixed.window=200 -Pmixed.windows=2`) only check the harness.
