# Configuration and commands

## Configuration

Edit `config/vanadium.toml`, then run `/vanadium reload` to apply changes live.
`workers` requires a server restart. Commands do not modify or save configuration values.

| Option | Default | Effect |
|---|---|---|
| `enabled` | `true` | Master switch; `false` uses serial ticking, with structural mixins, locks and loaded-chunk lookups still active |
| `workers` | `0` | `<= 0` = two thirds of the available logical processors (at least one); positive values clamp to 2..max(2, available logical processors) (restart) |
| `isolation` | `2` | Exclusion radius in chunks (1–16, Chebyshev distance) for block entities, chunk ticks, scheduled ticks and spawning. Work at or within this distance never runs concurrently. Region sizes adapt automatically. Entities are not affected |
| `parallelEntities` | `true` | Tick entities in parallel: all of them in one wave, wherever they are. Each owns itself while it ticks; what entities do to each other is locked per interaction |
| `skipFailedEntityTicks` | `true` | An entity tick that throws during parallel entity ticking is logged and the rest of that tick skipped, instead of stopping the server; three failures in a row by one entity still stop it |
| `parallelBlockEntities` | `true` | Tick block entities in parallel |
| `serialEntityTypes` | `[]` | Entity type IDs that tick serially |
| `serialBlockEntityTypes` | `[]` | Block-entity type IDs that tick serially |
| `parallelChunkTicks` | `true` | Tick chunks (weather, random ticks) in parallel |
| `parallelScheduledTicks` | `true` | Run scheduled block/fluid ticks (redstone, fluid spread, leaf decay) in parallel |
| `parallelSpawning` | `true` | Run per-chunk natural mob spawning in parallel; chunks whose structure lookups need ungenerated chunks rerun on the server thread after the wave |
| `parallelTracking` | `true` | Run entity tracking (movement/data packets to watching players) in parallel |
| `parallelChunkPackets` | `true` | Prepare chunk section packet data in parallel between world ticks |
| `consolidateFlushes` | `true` | Batch each connection's packets into one flush per tick instead of one per packet |
| `parallelChunkLoads` | `true` | Per-chunk load locks (server thread only); `false` = one global lock |
| `crowdedSectionIndex` | `true` | Entity sections holding 24 or more entities (`-Dvanadium.crowdedSectionEntities`) index their entities by 2×4×2-block cells, so pushing and other small box queries test only nearby entities; same results as the full scan |
| `idleWorkers` | `lean` | What threads do between ticks: `lean` spins nothing; `balanced` parks workers and keeps the server thread spinning until the next tick (about one core); `hot` keeps workers spinning too (every worker that ran holds a core: 24 workers, 24 cores). See [idle workers](#idle-workers) |
| `slowWaveMillis` | `0` | Warn when a wave exceeds this duration; `0` disables diagnostics |
| `detailedTickDiagnostics` | `false` | Add current task/type labels while wave diagnostics are enabled |

## Commands

`workers = 0` picks two thirds of the logical processors, because one worker per processor leaves
none for the server thread, C2ME and the garbage collector: on the 36-core, 72-thread test machine 72
workers ran 24–51% below the best setting. The best count still depends on the machine and the world
(24 to 48 there, where the default gives 48), so compare a few values with `/vanadium benchmark`; see
[worker count](PERFORMANCE.md#worker-count).

`isolation` sets the spatial exclusion distance independently of worker count, not the size of a region.

### Idle workers

At the normal 20 TPS the threads that tick the world sit idle for most of each 50 ms. On the
test VM a tick could then take twice as long as the same tick under `/tick sprint`, but only
at some times: in other measurement windows, with identical settings, idle threads cost
nothing (see [PERFORMANCE.md](PERFORMANCE.md#idle-workers-at-20-tps)). The VM cannot see the
host's power states, so check your own machine with `/vanadium profile` at 20 TPS:

- `lean` (default) spins nothing between ticks: the least CPU.
- `balanced` parks workers and keeps the server thread spinning until the next tick:
  about one core (`top` shows the server process at 100% or more even when ticks are short).
- `hot` keeps every worker that ran spinning as well, for one core per worker (`top` shows about
  `workers` × 100%). It halved tick times when idle threads were slow and was within noise of
  `lean` otherwise.

After a tick without parallel work (an empty or `/tick freeze` world) nothing spins in any mode,
and a server paused when empty (`pause-when-empty-seconds`) never spins.

`/vanadium set idleWorkers hot` switches live for a comparison; `/vanadium reload` restores the
file's value. System properties override the option and are shown by `/vanadium status`:
`-Dvanadium.idleWorkers=<mode>`, `-Dvanadium.serverSpinMicros=<µs>` (spin the server thread only
for that long before each tick, in every mode; `0` never),
`-Dvanadium.prewakeLeadMicros=<µs>` (in `balanced`, wake workers that long before each tick;
off by default because no lead measured faster) and `-Dvanadium.workerSpinMicros=<µs>` (a fixed
worker spin that ignores ticks).

Serial rules match exact registry IDs. Set them as TOML string arrays:

```toml
serialEntityTypes = ["minecraft:villager", "example:custom_mob"]
serialBlockEntityTypes = ["minecraft:hopper", "example:machine"]
```

Use `[]` to clear a rule, then run `/vanadium reload`.
Unknown but well-formed IDs are allowed and have no effect until that type is present.
Matching tickers run on the server thread before the stage's queued parallel work.
Projectiles, entities in portals and entities whose ticks mostly change other things (TNT, container
minecarts, the dragon, the wither, lightning, effect clouds, evoker fangs, copper golems, wardens)
always tick serially.
Rules govern these tickers only: they do not isolate a mod's shared state, callbacks,
scheduled ticks, or tracking. Validate the actual modpack before deployment.

| Command | Permission | Effect |
|---|---|---|
| `/vanadium` or `/vanadium status` | everyone | Version, enabled state, workers (with restart-pending detection), isolation, all stage flags |
| `/vanadium set <option> <value>` | op (2) | Change a boolean, integer or `idleWorkers` option live (not `workers`); not saved, `/vanadium reload` restores the file |
| `/vanadium reload` | op (2) | Re-read `vanadium.toml` and apply live |
| `/vanadium benchmark [seconds]` | op (2) | Unlock TPS (via vanilla tick-sprint; default 30s, range 5–300s) and report ticks run, average and peak TPS |
| `/vanadium profile [seconds]` | op (2) | Capture tick/stage timings at the current tick rate; default 30s, range 1–300s |
| `/vanadium profile stop` | op (2) | Finish the current capture early |
| `/vanadium profile report` | op (2) | Show the last completed capture for this server |
| `/vanadium profile report stages\|worlds\|details` | op (2) | Open one part of the last profile; also available by clicking its buttons |
| `/vanadium benchmark report [details]` | op (2) | Show the last benchmark summary or its details |

Reports are compact by default. Profile summaries show average/p95 tick work and the three
most expensive queued stages, measured in **ms per server tick** across all dimensions.
Hover a timing for percentiles, task/region counts, utilization and measurement definitions.
Click **Stages**, **Worlds** or **Details** to print just that view; **Summary** returns to
the short view. World rows have their phase breakdowns on hover. Click **JSON path** to
copy the server-relative result-file path. Each capture replaces the previous report of
its kind; buttons show that server's latest capture. Reports reset when the server stops.

Green/gold/red tick timings indicate proximity to the 50ms budget at 20 TPS, not a
comparison with another capture. Benchmark TPS is uncapped processing capacity;
its elapsed time per tick is not profiled MSPT. The best-window count is approximately
one second and is not normalized TPS. Console/RCON users can type the report subcommands
directly; hover and click actions require an in-game chat client.

Profiles report average/p50/p95/p99 tick work and queued stage execution, caller tail-wait
time, region/task counts, and average/maximum tasks per region. They split tick work into stage
waves, preparation waves and the serial remainder, which bounds what more workers can gain,
and give each stage's participant utilization (region time over wave time × participants). Stage measurements exclude
collection and serial fallbacks; whole-tick measurements include them but exclude idle
time between ticks. Worlds are aggregated, and regions adapt to the work with stable plans reused.
Keep configuration and workload fixed when comparing captures. Profiling
adds measurement overhead and does not unlock TPS. Reports are also written to the server log.
Per-world entries also report preparation (collection plus inline fallbacks), stage execution,
and whole-world time. Region execution percentiles expose uneven work within waves. Spawning
and thunder use `SPAWNING`; random/precipitation ticks use `CHUNK`. World totals include stage
work, so these overlapping measurements must not be added together. Region durations include
any lock waits; use JFR to distinguish waiting from CPU work.

Benchmarks and profiles are also published as JSON: one `VANADIUM_RESULT {...}` log line and a
file under `vanadium/results/` in the server directory. Each result records the active
configuration, worker count, isolation, CPU count, heap and mod versions it was measured with.
`/vanadium set` is meant for A/B runs in one JVM: for example `/vanadium set parallelTracking off`,
`/vanadium benchmark`, then `/vanadium reload`.

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

