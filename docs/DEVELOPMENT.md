# Development and testing

## Setup

Minecraft **26.2**, official unobfuscated names, Java **25**. Use the checked-in
Gradle wrapper. Versions live in `gradle.properties`, `build.gradle` and
`gradle/wrapper/gradle-wrapper.properties`; do not upgrade them incidentally.

```sh
nix develop
./gradlew build
./gradlew genSources
python3 scripts/dev-server.py prepare
```

The pinned Nix shell supplies the JDK, `jcmd`/`jfr`, Python and development tools.
It also exposes Linux Vulkan/OpenGL loaders for `./gradlew runClient`; a working
host display/driver is still required. Re-enter the shell after changing the flake.
Without Nix, install Java 25 and Python 3. Initial builds need network access.

C2ME is a verified release jar in `.vanadium/mods/`, pinned by
`scripts/runtime-mods.json`; Gradle must not flatten its nested mods. For interactive
clients/servers, copy that jar into `run/mods/`, avoiding duplicate versions.
Fabric API and Cloth Config are Gradle-managed. Preserve personal `run/` data.

## Checks

```sh
./gradlew test --tests 'com.synsenetwork.vanadium.tick.*' --rerun-tasks
./gradlew build
python3 scripts/check-block-updates.py
nix flake check
git diff --check
```

Before running Minecraft, read the [EULA](https://aka.ms/MinecraftEULA). If accepted,
put `eula=true` in `.vanadium/eula.txt`, or reuse an existing accepted file.
`python3 scripts/dev-server.py smoke` runs startup/ticking/save/shutdown alone.
Both harnesses use fresh `.vanadium/runs/` worlds, loopback ports and random RCON
passwords. Logs and worlds remain there, including after failure; the harness
cleans up only its own processes. An isolated interactive run is also supported:
`./gradlew -PvanadiumRunDir=/absolute/path runServer --args=nogui`.

Build artifacts: `build/libs/`; JUnit reports: `build/reports/tests/test/`.
The current suite has **165 unit tests**. They check bindings, scheduling, collections
and config without applying mixins. The separate live test mod exercises block
breaking/update packets, concurrent dirty notifications, scheduled-tick state,
passenger rules, event deduplication, random-position transitions, navigation,
tracking diffs, spawn counting, item conservation and chunk packet encoding/sending.
These fixtures complement gameplay testing; they do not certify a modpack.

For IDE/CLI cache contention, let the other import finish. Use a separate
`GRADLE_USER_HOME` for isolated jobs if needed; do not delete live cache lock files.
`nix flake check` checks development files, not Minecraft. Only native x86_64 Linux
execution has been validated here.

## Benchmarks and profiles

[26.2 feature results](BENCHMARKS_26_2.md) contains measured on/off timings, allocation,
server-thread CPU costs and JFR findings. Reproduce inside `nix develop`:

```sh
python3 scripts/bench-features.py
python3 scripts/report-feature-bench.py .vanadium/runs/features-FIRST \
  .vanadium/runs/features-SECOND .vanadium/runs/features-THIRD \
  --output .vanadium/feature-results.json
```

The default suite uses three fresh JVMs, four helpers plus the participating server
thread, cell size two, C2ME parallelism four, and a fixed 2GiB G1 heap. Each workload
warms both arms, alternates their order for 12 paired samples per JVM, then records
separate on/off JFR profiles in the first JVM. Timing samples exclude active JFR.
`--workers N` changes helpers; `--filter 'spawn-.*'` selects cases; `--no-jfr` skips
recordings. Reduced `--scale`/`--rounds` are for harness checks, not reported results.
The benchmark mod and its switches are excluded from release jars.

`./gradlew benchScheduler`, `benchWorkerPool` and `benchAreaMap` isolate scheduler,
worker and index overhead. `scripts/bench-server.sh 30` exercises a normal ticking
server with 200 clustered zombies; it is a smoke workload, not an all-feature A/B
comparison. For actual worlds use `/vanadium profile`, tick sprint for throughput,
and [JFR](../scripts/PROFILING.md) for CPU, allocation and locks. World and stage
profile times overlap: do not add them together. Caller tail wait is not lock time.

## Current limitations

- **Hardware and scope:** local measurements use a Ryzen 5 9600X, not the dual
  E5-2699 v3. They isolate the recent optimization paths; they do not measure whole
  modpack TPS, p95/p99 tick latency, exploration, or every older config flag.
  Timing batches and JFR samples are not individual-tick latency distributions.
- **Parallelism:** worlds, stages and four colors still have barriers; one cell's
  tasks execute serially. Player simulation, tracking dispatch, packet sending and
  several world bookkeeping paths remain on the server thread. Auto workers can
  oversubscribe the CPU alongside C2ME, GC and networking. NUMA behavior is untested.
- **Navigation:** the quadratic cleanup and redundant candidate copying are fixed.
  When most paths keep changing but edits are rare, refresh defers rebuilding and
  queries use the full dirty fallback. That avoids maintenance overhead but cannot
  reduce candidate scans until paths settle or edits become frequent.
  Custom predicates use conservative fallbacks; mods bypassing invalidation hooks
  require integration. The benchmark includes refresh but not pathfinding itself.
- **Tracking/spawning:** tracking uses reusable player-owned action rows and one task
  per tracker; caller-side dispatch/index maintenance remain. Rows retain capacity
  for peak tracker counts until the player leaves. Small sized entity views now count
  without copying; unsized mod-provided iterables still require a snapshot. Fixtures
  omit real network callbacks; spawn fixtures omit real player caps and charged-biome
  populations.
  The spawn check/accounting sequence is not made atomic by preparation parallelism.
- **Shared locks:** neighbor chains still hold one world monitor. Removing list
  copies does not parallelize redstone. Per-item locks cover known vanilla paths;
  arbitrary modded inventory writes are not coordinated. The item benchmark changes
  lock granularity on candidate checks, not the complete old merge implementation.
- **Chunk packets:** only section serialization is parallel; selection, heightmaps,
  block-entity callbacks, lighting and sending remain on the caller. Preparation
  starts at four chunks, caps at 64, and adds buffers/copies. Arbitrary asynchronous
  chunk mutations are unsupported. Packet timings exclude compression and clients.
- **Safety/compatibility:** `enabled=false` retains structural mixins and locks; it
  is not a no-Vanadium baseline. C2ME is required; Lithium and VMP are incompatible.
  The four correctness fixes remain enabled in every benchmark arm.

Before deployment, test the intended modpack with real players: tracking-range and
cell crossings, joining/leaving, portals/dimensions, redstone/fluid chains, inventories,
chunk unload/reload, save/restart and integrated-server reopen. On the Xeons, sweep
worker counts with cell size and C2ME budget fixed, then vary cell size and socket
placement. Keep a no-Vanadium baseline separate from disabled and enabled Vanadium.
Historical setup/port validation is available in Git history; current measurements
and reproducible commands take precedence over those older smoke timings.
