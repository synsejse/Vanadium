# Configuration and commands

## Configuration

Edit `config/vanadium.toml`, then run `/vanadium reload` to apply changes live.
`workers` requires a server restart. A failed `/vanadium reload` retains the active settings.
`/vanadium set` changes in-memory values; commands do not write the configuration file.

| Option | Default | Effect |
|---|---|---|
| `enabled` | `true` | Master switch; `false` uses serial ticking, with structural mixins, locks and loaded-chunk lookups still active |
| `workers` | `0` | `<= 0` = one per physical core, leaving one logical processor for the server thread: min(cores, logical processors − 1), at least one; two thirds of the logical processors where the core count is unknown. Positive values clamp to 2..max(2, available logical processors) (restart). See [workers and isolation](#workers-and-isolation) |
| `isolation` | `2` | Exclusion radius in chunks (1–16, Chebyshev distance) for block entities, chunk ticks, scheduled ticks and spawning. Work at or within this distance never runs concurrently. Region sizes adapt automatically. Entities are not affected |
| `parallelEntities` | `true` | Tick entities in parallel: all of them in one wave, wherever they are. Each owns itself while it ticks; what entities do to each other is locked per interaction |
| `skipFailedEntityTicks` | `true` | An entity tick that throws during parallel entity ticking is logged and the rest of that tick skipped, instead of stopping the server; three failures in a row by one entity still stop it |
| `parallelBlockEntities` | `true` | Tick block entities in parallel |
| `serialEntityTypes` | `[]` | Entity type IDs that tick serially; see [serial type rules](#serial-type-rules) |
| `serialBlockEntityTypes` | `[]` | Block-entity type IDs that tick serially; see [serial type rules](#serial-type-rules) |
| `parallelChunkTicks` | `true` | Tick chunks (weather, random ticks) in parallel |
| `parallelScheduledTicks` | `true` | Run scheduled block/fluid ticks (redstone, fluid spread, leaf decay) in parallel |
| `parallelSpawning` | `true` | Run per-chunk natural mob spawning and the mob count in parallel; chunks whose structure lookups need ungenerated chunks rerun on the server thread after the wave. On dedicated servers, the mob count is also skipped while `spawn_mobs` is false, until something reads it (`/perf` report, mods); see [Architecture](ARCHITECTURE.md#preparation-and-networking) |
| `parallelTracking` | `true` | Run entity tracking (movement/data packets to watching players) in parallel |
| `parallelChunkPackets` | `true` | Prepare chunk section packet data in parallel between world ticks |
| `consolidateFlushes` | `true` | Batch each connection's packets into one flush per tick instead of one per packet |
| `crowdedSectionIndex` | `true` | Entity sections holding 24 or more entities (`vanadium.crowdedSectionEntities`, [below](#jvm-properties)) index their entities by 2×4×2-block cells, so pushing and other small box queries test only nearby entities; same results as the full scan |
| `idleWorkers` | `lean` | What threads do between ticks: `lean`, `balanced` or `hot`; see [idle workers](#idle-workers) |
| `slowWaveMillis` | `0` | Warn when a wave exceeds this duration; `0` disables diagnostics. See [stall diagnostics](#stall-diagnostics) |
| `detailedTickDiagnostics` | `false` | Add current task/type labels while wave diagnostics are enabled |

### Workers and isolation

`workers = 0` starts one worker per physical core and leaves one logical processor for the server
thread, which works alongside the workers in every wave: with SMT it takes a spare sibling, without
SMT it takes a core. On the 36-core, 72-thread test machine that is 36 workers. Workers beyond the
physical cores share cores with SMT siblings and mostly spin; on four cores without SMT, the default of
3 workers beat 4. The measurements, with their builds and dates, are in
[worker count](PERFORMANCE.md#worker-count) and [default worker count](PERFORMANCE.md#default-worker-count).

Cores are detected through OSHI, the cross-platform hardware library Minecraft already includes,
using the same code on Windows, macOS and Linux. The reported system core count is capped by Java's
available logical processor count minus one, which respects JVM-reported affinity and CPU quotas
(including `-XX:ActiveProcessorCount`). OSHI reports system topology, so this does not identify exactly
which physical cores a restricted affinity mask includes; set `workers` explicitly when tuning a
pinned process. If hardware detection is unavailable, the default stays two thirds of Java's available
processors. Detection happens once per JVM and is skipped when an explicit worker count is used.
The startup log names both counts: `Will use 36 threads, isolation 2 chunks (36 system cores; 72 JVM processors)`.
The best count still depends on the machine and the world, so compare a few values with
`/vanadium benchmark`.

JVM flags matter as much as `workers` on a multi-socket server; see
[recommended JVM flags](PERFORMANCE.md#recommended-jvm-flags) for the flags, their measured effect,
requirements and when not to use them.

`isolation` sets the spatial exclusion distance independently of worker count, not the size of a
region; see [planning and execution](ARCHITECTURE.md#planning-and-execution).

### Idle workers

At the normal 20 TPS the threads that tick the world sit idle for most of each 50 ms. Whether that
makes ticks slower depends on the machine: on the test VM it doubled tick times at some times and cost
nothing at others ([idle workers at 20 TPS](PERFORMANCE.md#idle-workers-at-20-tps)). Check your own
machine with `/vanadium profile` at 20 TPS against a `/tick sprint`:

- `lean` (default) spins nothing between ticks: the least CPU.
- `balanced` parks workers and keeps the server thread spinning until the next tick:
  about one core (`top` shows the server process at 100% or more even when ticks are short).
- `hot` keeps every worker that ran spinning as well, for one core per worker (`top` shows about
  `workers` × 100%).

In no mode does anything spin after a tick without parallel work (an empty world), while the server
is paused when empty, or for a `/tick freeze` world's entities; how each mode spins is in
[Architecture](ARCHITECTURE.md#idle-spinning). `/vanadium set idleWorkers hot` switches live for a
comparison; `/vanadium reload` restores the file's value.

### Serial type rules

Serial rules match exact registry IDs. Set them as TOML string arrays:

```toml
serialEntityTypes = ["minecraft:villager", "example:custom_mob"]
serialBlockEntityTypes = ["minecraft:hopper", "example:machine"]
```

Use `[]` to clear a rule, then run `/vanadium reload`.
Unknown but well-formed IDs are allowed and have no effect until that type is present.
Matching entity and block-entity tickers run on the server thread before the stage's queued parallel
work; a serial passenger takes its whole vehicle tree with it. Projectiles, entities in portals and
entities whose ticks mostly change other things (TNT, container minecarts, the dragon, the wither,
lightning, effect clouds, evoker fangs, copper golems, wardens) always tick serially.

A rule moves only those tickers to the server thread. It does not isolate a mod's shared state:
chunk ticks, scheduled ticks, spawning and tracking still run in parallel, and other entities and
block entities, and the callbacks they trigger, can still reach that state concurrently. Validate the
actual modpack before deployment.

### JVM properties

Measurement and diagnostic inputs, read once per JVM from `-Dvanadium.<name>=<value>` on the server's
command line. None is needed for normal play.

| Property | Default | Accepts | Effect |
|---|---|---|---|
| `vanadium.spawnParticipants` | `auto` | `auto` or an integer ≥ 1 | Upper bound on threads, the server thread included, offered to each natural-spawning wave (`1` = inline on the server thread). Anything else stops the server at start. Results record the resolved value as `environment.spawnParticipants`. See [Architecture](ARCHITECTURE.md#spawning-participants) and [spawn contention arms](PROFILING.md#spawn-contention-arms) |
| `vanadium.participantCostNanos` | `2000` | 0–1,000,000 | Cost c of involving one more thread in a wave, which sizes waves; see [Architecture](ARCHITECTURE.md#wave-sizing) |
| `vanadium.criticalPathShare` | `0.8` | `0` (no bound) or a number above 0 and at most 1 | Bound on a flat wave's threads relative to its longest task; see [Architecture](ARCHITECTURE.md#wave-sizing) |
| `vanadium.entityHomes` | `true` | `true` or `false`, any case | `false` claims every entity wave in list order instead of by home; see [Architecture](ARCHITECTURE.md#flat-entity-wave) |
| `vanadium.workerSlackSpinMicros` | `500` | 0–1,000,000 | Worker spin between waves of a tick with slack; see [Architecture](ARCHITECTURE.md#idle-spinning) |
| `vanadium.callerPressureSpinMicros` | `10000` | 0–1,000,000 | Longest tail the server thread spins through under pressure before parking; see [Architecture](ARCHITECTURE.md#idle-spinning) |
| `vanadium.crowdedSectionEntities` | `24` | 2–4,096 | Entities in a section before it gets a cell grid (`crowdedSectionIndex`) |
| `vanadium.interactionLogWaves` | `0` | 0–1,000,000 | Every N entity waves, log foreign-write counts by kind plus ownership `cycles`/`timeouts`; `0` disables. See [Architecture](ARCHITECTURE.md#flat-entity-wave) |
| `vanadium.interactionTimeoutMillis` | `100` | 1–60,000 | Longest wait for another entity's ownership lock before the write proceeds unprotected and is counted as a timeout |
| `vanadium.callerRequestStallSeconds` | `10` | 1–3,600 | How long a worker's chunk lookup handed to the wave's caller may wait before a warning names the stalled caller; see [world access](ARCHITECTURE.md#world-access) |

All are validated: a malformed, NaN, infinite or out-of-range value logs
`Ignoring -D…; using the default …` once and the default applies (`spawnParticipants` excepted, as above).

### Removed options and properties

These were removed. A file or command line that still sets one works as before apart from that
setting, and logs a warning naming it as removed and ignored.

- `parallelChunkLoads` (TOML): chunk loads always run under per-chunk locks on the server thread. A
  `vanadium.toml` that still sets it loads with its other values applied; each load that finds the key
  (startup or `/vanadium reload`) warns, and the save after a successful startup drops it.
- `-Dvanadium.idleWorkers`: use the `idleWorkers` option or `/vanadium set idleWorkers`.
- `-Dvanadium.serverSpinMicros`, `-Dvanadium.prewakeLeadMicros`, `-Dvanadium.prewakeGraceMicros`,
  `-Dvanadium.workerSpinMicros`, `-Dvanadium.callerSpinMicros`, `-Dvanadium.callerWakes` and
  `-Dvanadium.prewake`: no replacement; the values they set are now fixed
  ([Architecture](ARCHITECTURE.md#idle-spinning)).

## Commands

| Command | Permission | Effect |
|---|---|---|
| `/vanadium` or `/vanadium status` | everyone | Version, enabled state, workers (with restart-pending detection), isolation, all stage flags |
| `/vanadium set <option> <value>` | op (2) | Change a boolean (`on`/`off` or `true`/`false`), integer or `idleWorkers` option live (not `workers`); not saved, `/vanadium reload` restores the file |
| `/vanadium reload` | op (2) | Re-read `vanadium.toml` and apply live |
| `/vanadium benchmark [seconds]` | op (2) | Unlock TPS (via vanilla tick-sprint; default 30s, range 5–300s), then summarize ticks run, average TPS and best window and save the result |
| `/vanadium profile [seconds]` | op (2) | Capture tick/stage timings at the current tick rate (default 30s, range 1–300s), then summarize them and save the result |
| `/vanadium profile stop` | op (2) | Finish the current capture early |
| `/vanadium profile report` | op (2) | Repeat the last profile's summary and result path for this server |
| `/vanadium benchmark report` | op (2) | Repeat the last benchmark's summary and result path for this server |

`/vanadium set` is meant for A/B runs in one JVM: for example `/vanadium set parallelTracking off`,
`/vanadium benchmark`, then `/vanadium reload`. Keep configuration and workload fixed when comparing
captures.

### Reports

A finished capture sends a short summary to whoever started it, and logs it:

- a profile: ticks captured, average and p95 tick work, and the three slowest queued stages in
  **ms per server tick** across all dimensions;
- a benchmark: average TPS, ticks run and the best window, a count of ticks in about one second
  (not normalized TPS). Benchmark TPS is uncapped processing capacity, not the gameplay tick rate.

Its last line is the result file's server-relative path; in game, click it to copy. Everything
else is in that file. `/vanadium profile report` and `/vanadium benchmark report` repeat the
latest summary of their kind; each capture replaces the previous one, and both reset when the
server stops.

Every result is published as JSON: one `VANADIUM_RESULT {...}` log line, for scripts that read the
console, and a file under `vanadium/results/` in the server directory. Each result records the
active configuration, worker count, isolation, CPU count, heap and mod versions it was measured with.

A profile result holds average/p50/p95/p99 tick work and queued stage execution, caller tail-wait
time, region/task counts, and average/maximum tasks per region. It splits tick work into stage
waves, preparation waves and the serial remainder, which bounds what more workers can gain,
and gives each stage's participant utilization (region time over wave time × participants). Stage
measurements exclude collection and serial fallbacks; whole-tick measurements include them but
exclude idle time between ticks. Per-world entries also report preparation (collection plus inline
fallbacks), stage execution, and whole-world time. Region execution percentiles expose uneven work
within waves. Spawning and thunder use `SPAWNING`; random/precipitation ticks use `CHUNK`.
World totals include stage work, so these overlapping measurements must not be added together.
Region durations include any lock waits, and caller tail wait is not a measure of lock contention;
use JFR ([profiling](PROFILING.md#flight-recorder)) to distinguish waiting from CPU work. Profiling
adds measurement overhead and does not unlock TPS.

### Stall diagnostics

For a stall investigation, set `slowWaveMillis = 1000` and optionally
`detailedTickDiagnostics = true` in TOML, then run `/vanadium reload`. A separate watchdog reports the stage,
dimension, isolation, elapsed time, and active caller/worker stacks while the wave
is still running, with each running region given as a chunk rectangle. Reports are
limited to one per 30 seconds, with up to eight participant stacks and twelve frames each.
Polling occurs every 100ms, so this is a diagnostic threshold, not a deadline.

Detailed mode labels queued entity and block-entity ticks with registry type IDs; other
tasks show their runnable class. It adds wrappers and per-task progress updates. Basic
mode tracks progress once per region; disabled mode starts no watchdog thread. Diagnostics
never cancel, retry, or interrupt tick work. Set `slowWaveMillis = 0` and reload after
investigating; inline serial fallbacks outside scheduler waves are not monitored.

### Watchdog crash reports

When vanilla's watchdog (`max-tick-time` in `server.properties`) gives up on a hung tick, the crash
report it writes under `crash-reports/` has a `-- Vanadium --` section after vanilla's thread dump,
which stops at eight frames per thread:

- **Scheduler**: workers and isolation.
- **Wave**: with `slowWaveMillis` above 0, the watched wave's stage, dimension, isolation, elapsed time
  and active participants. Otherwise the dimension being ticked and the stage it last began (its
  collection, preparation or wave, or work after its wave if that already finished).
- **Latest parallel wave**: tasks finished, unclaimed and participants of the last wave handed to the
  workers. Waves that run inline on the server thread are not counted, so a finished wave here means
  the hang is elsewhere. A participant counts its tasks as finished only when it runs out of work, and
  a region wave's tasks are its participants' loops over regions.
- **Busy workers**: each worker running anything outside the pool, with its thread state, its region and
  (with `detailedTickDiagnostics`) task when the wave is watched, and 32 stack frames.
- **Idle workers**: those spinning, parked or claiming in the pool.

The section is built only when the watchdog fires, from state the tick keeps anyway; it costs
nothing before then. C2ME adds its own thread-state section to the same report.
