[![Issues](https://img.shields.io/github/issues/synsejse/Vanadium?style=for-the-badge)](https://github.com/synsejse/Vanadium/issues)
[![Build Status](https://img.shields.io/github/actions/workflow/status/synsejse/Vanadium/gradle.yml?style=for-the-badge)](https://github.com/synsejse/Vanadium/actions/workflows/gradle.yml)
[![Shenanagins](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

# Vanadium — Minecraft Multi-Threading Mod (Fabric)

Vanadium parallelizes server ticking: entities, block entities, and chunk ticks run on a worker pool, partitioned into a 4-color grid of chunk cells so adjacent cells never tick at the same time. It is based on the amazing work of [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) and the [MCMTFabric](https://github.com/himekifee/MCMTFabric) port — much of the underlying engineering is documented there.

Vanadium handles parallel *ticking*; [C2ME](https://modrinth.com/mod/c2me-fabric) handles parallel *generation and loading*. They are designed to run together, and C2ME is required.

## Requirements

- Minecraft 26.2, Fabric Loader 0.19.3+, Java 25+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Cloth Config](https://modrinth.com/mod/cloth-config)
- [C2ME](https://modrinth.com/mod/c2me-fabric) — **required**

**Incompatible with [Lithium](https://modrinth.com/mod/lithium)** — it assumes single-threaded world access; the server refuses to start with both installed.

**Incompatible with [VMP](https://modrinth.com/mod/vmp-fabric)** — both mods thread entity tracking, with different threading models, in the same vanilla methods. Vanadium ships its own replacements for VMP's server-side features; the server refuses to start with both installed.

## Configuration

Edit `config/vanadium.toml`, then run `/vanadium reload` to apply changes live.
`workers` requires a server restart. Commands do not modify or save configuration values.

| Option | Default | Effect |
|---|---|---|
| `enabled` | `true` | Master switch; `false` = fully vanilla ticking |
| `workers` | `0` | `<= 0` = available logical processors; positive values clamp to 2..max(2, available logical processors) (restart) |
| `cellSize` | `0` | Cell width/height in chunks; `0` = auto from resolved worker count |
| `parallelEntities` | `true` | Tick entities in parallel |
| `parallelBlockEntities` | `true` | Tick block entities in parallel |
| `serialEntityTypes` | `[]` | Entity type IDs that tick serially |
| `serialBlockEntityTypes` | `[]` | Block-entity type IDs that tick serially |
| `parallelChunkTicks` | `true` | Tick chunks (weather, random ticks) in parallel |
| `parallelScheduledTicks` | `true` | Run scheduled block/fluid ticks (redstone, fluid spread, leaf decay) in parallel |
| `parallelSpawning` | `true` | Run per-chunk natural mob spawning in parallel |
| `parallelTracking` | `true` | Run entity tracking (movement/data packets to watching players) in parallel |
| `parallelChunkPackets` | `true` | Prepare chunk section packet data in parallel between world ticks |
| `consolidateFlushes` | `true` | Batch each connection's packets into one flush per tick instead of one per packet |
| `chunkCache` | `true` | Thread-safe chunk lookup cache for worker threads |
| `parallelChunkLoads` | `true` | Per-chunk load locks; `false` = one global lock |
| `slowWaveMillis` | `0` | Warn when a wave exceeds this duration; `0` disables diagnostics |
| `detailedTickDiagnostics` | `false` | Add current task/type labels while wave diagnostics are enabled |

## Commands

Serial rules match exact registry IDs. Set them as TOML string arrays:

```toml
serialEntityTypes = ["minecraft:villager", "example:custom_mob"]
serialBlockEntityTypes = ["minecraft:hopper", "example:machine"]
```

Use `[]` to clear a rule, then run `/vanadium reload`.
Unknown but well-formed IDs are allowed and have no effect until that type is present.
Matching tickers run on the server thread before the stage's queued parallel work;
projectiles, portals, and existing fallback paths retain their serial treatment.
Rules govern these tickers only: they do not isolate a mod's shared state, callbacks,
scheduled ticks, or tracking. Validate the actual modpack before deployment.

| Command | Permission | Effect |
|---|---|---|
| `/vanadium` or `/vanadium status` | everyone | Version, enabled state, workers (with restart-pending detection), cell size, all stage flags |
| `/vanadium reload` | op (2) | Re-read `vanadium.toml` and apply live |
| `/vanadium benchmark` | op (2) | Unlock TPS for 30s (via vanilla tick-sprint) and report ticks run, average and peak TPS |
| `/vanadium profile [seconds]` | op (2) | Capture tick/stage timings at the current tick rate; default 30s, range 1–300s |
| `/vanadium profile stop` | op (2) | Finish the current capture early |
| `/vanadium profile report` | op (2) | Show the last completed capture for this server |

Profiles report average/p50/p95/p99 tick work and queued stage execution, caller tail-wait
time, cell/task counts, and average/maximum tasks per cell. Stage measurements exclude
collection and serial fallbacks; whole-tick measurements include them but exclude idle
time between ticks. Worlds are aggregated, and cell counts count executions rather than
unique locations. Keep configuration and workload fixed when comparing captures. Profiling
adds measurement overhead and does not unlock TPS. Reports are also written to the server log.
Per-world entries also report preparation (collection plus inline fallbacks), stage execution,
and whole-world time. Cell execution percentiles expose uneven work within waves. Spawning
and thunder use `SPAWNING`; random/precipitation ticks use `CHUNK`. World totals include stage
work, so these overlapping measurements must not be added together. Cell durations include
any lock waits; use JFR to distinguish waiting from CPU work.

For a stall investigation, set `slowWaveMillis = 1000` and optionally
`detailedTickDiagnostics = true` in TOML, then run `/vanadium reload`. A separate watchdog reports the stage,
dimension, color, cell size, elapsed time, and active caller/worker stacks while the wave
is still running. Cell coordinates are **cell units**, not chunks or blocks. Reports are
limited to one per 30 seconds, with up to eight participant stacks and twelve frames each.
Polling occurs every 100ms, so this is a diagnostic threshold, not a deadline.

Detailed mode labels queued entity and block-entity ticks with registry type IDs; other
tasks show their runnable class. It adds wrappers and per-task progress updates. Basic
mode tracks progress once per cell; disabled mode starts no watchdog thread. Diagnostics
never cancel, retry, or interrupt tick work. Set `slowWaveMillis = 0` and reload after
investigating; inline serial fallbacks outside scheduler waves are not monitored.

## Development

```sh
nix develop               # pinned Java 25 + development tools (optional)
./gradlew build            # jar + tests
./gradlew test             # unit tests
./gradlew runServer        # dev server
./gradlew benchScheduler   # scheduler micro-benchmark
python3 scripts/dev-server.py prepare  # verified C2ME runtime jar
python3 scripts/dev-server.py smoke    # isolated server test; see EULA setup below
```

See [development and testing](docs/DEVELOPMENT.md) for setup, EULA handling, isolated server
checks, reports, and troubleshooting; [architecture notes](docs/ARCHITECTURE.md) describe
the concurrency model and follow-up investigation areas. [AGENTS.md](AGENTS.md) records
repository guidance for coding agents.

The interactive dev server loads runtime mods from `run/mods`. The setup helper downloads
a pinned, checksum-verified C2ME jar to `.vanadium/mods/`; copy it to `run/mods` for interactive
use. Automated smoke tests and benchmarks use fresh `.vanadium/runs/` worlds. Gradle does
not manage C2ME's runtime jar.

Happy playing!
