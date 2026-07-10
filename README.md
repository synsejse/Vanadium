[![Issues](https://img.shields.io/github/issues/synsejse/Vanadium?style=for-the-badge)](https://github.com/synsejse/Vanadium/issues)
[![Build Status](https://img.shields.io/github/actions/workflow/status/synsejse/Vanadium/gradle.yml?style=for-the-badge)](https://github.com/synsejse/Vanadium/actions/workflows/gradle.yml)
[![Shenanagins](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

# Vanadium — Minecraft Multi-Threading Mod (Fabric)

Vanadium parallelizes server ticking: entities, block entities, and chunk ticks run on a worker pool, partitioned into a 4-color grid of chunk cells so adjacent cells never tick at the same time. It is based on the amazing work of [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) and the [MCMTFabric](https://github.com/himekifee/MCMTFabric) port — much of the underlying engineering is documented there.

Vanadium handles parallel *ticking*; [C2ME](https://modrinth.com/mod/c2me-fabric) handles parallel *generation and loading*. They are designed to run together, and C2ME is required.

## Requirements

- Minecraft 1.21.1, Fabric Loader, Java 21+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Cloth Config](https://modrinth.com/mod/cloth-config)
- [C2ME](https://modrinth.com/mod/c2me-fabric) — **required**

**Incompatible with [Lithium](https://modrinth.com/mod/lithium)** — it assumes single-threaded world access; the server refuses to start with both installed.

**Incompatible with [VMP](https://modrinth.com/mod/vmp-fabric)** — both mods thread entity tracking, with different threading models, in the same vanilla methods. Vanadium ships its own replacements for VMP's server-side features; the server refuses to start with both installed.

## Configuration

`config/vanadium.toml` — every option is also live-editable in game via `/vanadium` (except `workers`, which needs a restart).

| Option | Default | Effect |
|---|---|---|
| `enabled` | `true` | Master switch; `false` = fully vanilla ticking |
| `workers` | `0` | Worker threads: `<= 0` = one per CPU core, otherwise capped at this value (restart) |
| `cellSize` | `0` | Cell width/height in chunks; `0` = auto from core count |
| `parallelEntities` | `true` | Tick entities in parallel |
| `parallelBlockEntities` | `true` | Tick block entities in parallel |
| `parallelChunkTicks` | `true` | Tick chunks (weather, random ticks) in parallel |
| `parallelScheduledTicks` | `true` | Run scheduled block/fluid ticks (redstone, fluid spread, leaf decay) in parallel |
| `parallelSpawning` | `true` | Run per-chunk natural mob spawning in parallel |
| `parallelTracking` | `true` | Run entity tracking (movement/data packets to watching players) in parallel |
| `chunkCache` | `true` | Thread-safe chunk lookup cache for worker threads |
| `parallelChunkLoads` | `true` | Per-chunk load locks; `false` = one global lock |

## Commands

| Command | Permission | Effect |
|---|---|---|
| `/vanadium` or `/vanadium status` | everyone | Version, enabled state, workers (with restart-pending detection), cell size, all stage flags |
| `/vanadium toggle <flag>` | op (2) | Flip any boolean option |
| `/vanadium set <option> <value>` | op (2) | Set any option (type-checked) |
| `/vanadium save` | op (2) | Persist current values to `vanadium.toml` |
| `/vanadium reload` | op (2) | Re-read `vanadium.toml` and apply live |
| `/vanadium defaults` | op (2) | Reset all options in memory (`save` to persist) |
| `/vanadium benchmark` | op (2) | Unlock TPS for 30s (via vanilla tick-sprint) and report ticks run, average and peak TPS |

## Development

```
./gradlew build            # jar + tests
./gradlew test             # unit tests
./gradlew runServer        # dev server
./gradlew benchScheduler   # scheduler micro-benchmark
```

The dev server loads runtime mods from `run/mods` — drop the required release jars there yourself (at
minimum [C2ME](https://modrinth.com/mod/c2me-fabric); optionally spark etc.). Gradle does not manage them.

Happy playing!
