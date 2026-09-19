# Development and testing

## Toolchain

The build targets Minecraft **26.2** and Java **25**, using official unobfuscated names.
There is no Yarn dependency or jar remapping step.
Use the checked-in Gradle wrapper; its version and download checksum are in
`gradle/wrapper/gradle-wrapper.properties`. Loom and library versions remain in
`build.gradle` and `gradle.properties`.

```sh
nix develop
java -version
./gradlew build
./gradlew genSources
```

The flake pins Nixpkgs through `flake.lock` and sets `JAVA_HOME`. It supplies the full
JDK (including `jcmd`, `jstack`, and `jfr`), Python 3 for the server harness, shellcheck,
nixfmt, Git, ripgrep, curl, jq, and archive tools. It defines shells for x86_64/aarch64
Linux and macOS; validation recorded below is specific to the local machine.
The shell supports compilation and headless server work and exposes the Vulkan and
OpenGL/EGL loaders on Linux for the graphical client (`./gradlew runClient`). The client
still needs a working display session and host graphics drivers, plus its graphics/audio dependencies.
After changing the flake, re-enter `nix develop` to refresh the library environment.

On 2026-09-14, a standalone LWJGL check using the development client's classpath
loaded OpenGL and created a hidden GLFW context: OpenGL 4.6, Mesa 26.2.2, Radeon
RX 9060 XT. This validates native library loading and context creation, not a full
Minecraft client session. The check is retained in `.vanadium/client-check/`.

Enable `nix-command` and `flakes` in your Nix installation if necessary. Optional direnv
integration uses the checked-in `.envrc`: with direnv/nix-direnv installed and your shell
hook configured, run `direnv allow`. Without Nix, install Java 25 and Python 3 and use
the same Gradle/Python commands.

Nix only sees tracked files in a Git flake. When introducing new Nix files, add them to
Git first or use `nix develop path:.` / `nix flake check path:.` temporarily (the path
form can copy ignored build/world caches and is slower). Update intentionally with
`nix flake update nixpkgs`, then recheck and include the changed lockfile in the commit.
The shell provides reproducible tools; it is not an offline or sandboxed Gradle package.
Initial builds still download Minecraft, Gradle, and Maven dependencies.

## Build and unit tests

```sh
./gradlew build
./gradlew test --tests 'com.synsenetwork.vanadium.tick.*'
./gradlew test --tests 'com.synsenetwork.vanadium.concurrent.*'
./gradlew test --rerun-tasks
./gradlew benchScheduler
./gradlew benchWorkerPool
./gradlew benchAreaMap
python3 scripts/check-block-updates.py
nix flake check
git diff --check
```

Distributable jars and source jars land in `build/libs/`. HTML test reports
are in `build/reports/tests/test/index.html`, with XML in `build/test-results/test/`.
`clean` deletes `build/`; ordinary development does not need a clean build every time.
JUnit tests cover cells, barriers, workers, lock tables, collection wrappers, tracking-area coverage, config,
and the vanilla fields/methods/superclasses targeted by mixins.
They do not start Fabric or apply Minecraft mixins.

`scripts/check-block-updates.py` packages a separate test mod under `build/test-mods/`,
then runs it with pinned C2ME in a fresh server directory using the same EULA and cleanup
rules as the smoke harness. It exercises creative and survival block breaking, captures
single-block and section update packets across successive broadcasts, and checks concurrent
dirty-chunk notifications before startup/tick/save/shutdown completes. It does not launch
a graphical client or reproduce client prediction, rendering, or an integrated-server session.
The test mod is excluded from the distributable mod jar.

`nix flake check` evaluates the shell and runs development-file checks; the Java tests
must be run separately. CI builds/tests on Java 25 and retains jars and test reports.

If an IDE and command-line build contend for Loom's shared cache, let the other import
finish. For isolated jobs, set `GRADLE_USER_HOME="$PWD/.vanadium/gradle-home"` before
running Gradle; this creates a separate dependency cache and needs initial downloads.
Do not delete cache lock files belonging to a running process.

## Isolated live-server checks

```sh
python3 scripts/dev-server.py prepare
```

This downloads the exact C2ME release listed in `scripts/runtime-mods.json` and checks
its SHA-512 before use. A cached file is also checked. It stays a release jar so Fabric
Loader can discover C2ME's nested mods. Gradle supplies Fabric API, Cloth Config, and
the project itself. To change C2ME, update the URL/hash together and validate both its
Minecraft version and the named mixins in `compat/C2ME.java`.

Before starting Minecraft, read the [Minecraft EULA](https://aka.ms/MinecraftEULA).
If you agree, create `.vanadium/eula.txt` containing `eula=true`. If you already accepted
it in this checkout, you can reuse that file:

```sh
cp run/eula.txt .vanadium/eula.txt
python3 scripts/dev-server.py smoke
scripts/bench-server.sh 30
```

Every invocation creates a fresh directory in `.vanadium/runs/`, downloads/reuses the
verified mod, and binds the game and RCON to loopback on available ports. It uses a
random RCON password, a fixed world seed, and no additional optional mods. It checks
Vanadium's status command, loads a small region, lets the world tick, saves, and stops.
The benchmark adds 200 persistent zombies and runs the vanilla tick profiler.
`--seconds N` controls the tick/profile interval; `--timeout N` controls startup waiting
(default 300 seconds, including any Gradle preparation).

Logs (`console.log`, `logs/latest.log`), worlds, crash reports, and profiles (`debug/`)
remain in the printed directory on both success and failure. The harness exits nonzero
on startup timeout, failed commands/connections, abnormal process exit, or server errors,
and cleans up its process group. Inspect retained output before removing old runs.
It never rewrites your existing `run/server.properties` or world.

For interactive work, `./gradlew runServer` still uses `run/`. Copy the pinned C2ME jar
from `.vanadium/mods/` into `run/mods/` if needed, avoiding duplicate C2ME versions.
A custom server directory is supported with
`./gradlew -PvanadiumRunDir=/absolute/path runServer --args=nogui`.

## Validation beyond a smoke test

A startup/tick/save check catches dependency resolution and many mixin failures. It
does not certify long-running world safety or player interactions. For changes to
Minecraft-facing code, use disposable worlds and cover the relevant scenarios:

| Change | Exercise |
| --- | --- |
| Scheduler/locks | One and multiple workers; negative coordinates; cell boundaries; exceptions and interruption |
| Scheduled ticks | Redstone, repeaters, water/lava propagation, and updates crossing cells |
| Entity ticking | Mob AI/combat, item merging, projectiles, portals, cross-dimension teleports |
| Block entities | Hoppers, furnaces, inventories, chunk unload/reload |
| Tracking/networking | Multiple players, joining/leaving, dimension changes, movement across tracking range |
| Configuration | `enabled=false`, each stage flag disabled, live reload, workers requiring restart |
| Lifecycle/storage | Save/reload, shutdown under load, integrated-server restart |

For performance comparisons, keep hardware, Java/C2ME versions, seed, workload, flags,
worker count, and cell size fixed. Compare multiple warmed runs. The scheduler
microbenchmark measures synthetic overhead; it is not an end-to-end TPS claim.
`benchWorkerPool` isolates dispatch with 1 and the available CPU count of workers, 1–1,024
tasks, and no-op/balanced/uneven CPU work. It reports time and total warmed-thread allocation
per wave; run several fresh JVMs sequentially and compare identical configurations.
`benchAreaMap` measures time and caller-thread allocation for stationary updates, one-chunk
and diagonal moves, teleports, and radius changes at radii 2, 8, and 16. It uses overlapping
stationary coverage, warms each case for 250 ms, and measures 50,000 updates. Compare several
fresh JVMs; index timings exclude players, packets, and scheduler work.
See [profiling](../scripts/PROFILING.md) for Flight Recorder commands.

## Cleanup and lifecycle validation (2026-09-14)

Mixin and accessor names now follow their Minecraft targets. Source-level qualified
types use imports, wildcard package imports are expanded, and unused shadows,
interfaces, superclass constructors, and raw tick callback types were removed.
`SynchronisePlugin` uses one mixin-name comparison and logs directly.

Each server lifecycle now creates and closes its own worker pool, scheduler, and
watchdog. Shutdown releases the profiler and benchmark command references. Each
chunk manager owns one maintenance executor for cache clearing and lock eviction;
`ChunkLockTable` no longer creates a thread. Maintenance closes with the manager,
including when vanilla close throws.

- `./gradlew build` passed **166 tests**, including the vanilla mixin binding checks
  and a gated regression proving pool close waits for a worker without interrupting it.
- A disposable Fabric fixture exercised three stop/start callback cycles in one JVM,
  checked fresh scheduler identity and changed worker counts, discarded queued tasks,
  and verified that old workers, watchdogs, profiles, and benchmarks were released.
- The isolated server passed configuration commands, save/reload, profiling with
  nonzero tasks in all five stages, and actual server save/shutdown. The final stop
  left no Vanadium worker, watchdog, or chunk-maintenance threads alive.
- The smoke harness now recognizes the `/ERROR]` console format, so errors logged
  during shutdown fail validation even if the game process exits successfully.
- `nix flake check` passed on x86_64 Linux; `git diff --check` passed.

Evidence is retained in `.vanadium/cleanup-26.2/` and
`.vanadium/runs/smoke-5tzimr8z/`. Callback reinitialization is not a full interactive
singleplayer world-close/reopen test; that scenario and real modpack behavior remain
untested. This cleanup makes no new throughput claim. The scheduled-tick bookkeeping
and passenger serial-rule findings remain separate follow-up work.

## Minecraft 26.2 port validation (2026-09-14)

The port uses Java 25.0.4.1, Loom 1.17.12, Gradle 9.6.0, Fabric Loader 0.19.3,
Fabric API 0.160.0+26.2, Cloth Config 26.2.155, Mod Menu 20.0.2, and pinned
C2ME 0.4.2-alpha.0.52. `genSources` completed against the unobfuscated game.
The distributable version is `2.0.0+26.2`; earlier game releases are rejected
by the mod metadata.

- `./gradlew build --rerun-tasks` passed **165 tests**, including 57 vanilla
  mixin binding checks, plus access-widener validation and jar generation.
- `nix flake check` passed on x86_64 Linux. Other architectures were not executed.
- The isolated live check passed configuration toggles, bounds, serial-rule
  save/reload, profiler start/stop/report, diagnostic settings, and startup/save/shutdown.
  A capture with zombies, a hopper and water recorded nonzero work in all five stages.
- A temporary Fabric fixture blocked real caller/worker tasks until the watchdog
  reported their stage, dimension, cells and labels, then released both successfully.
- `scripts/bench-server.sh 5` passed a 200-zombie workload, profiling 100 ticks and
  saving/shutting down successfully in `.vanadium/runs/bench-foapcqvh/`. This was
  validation at the normal tick cap, not a before/after performance comparison.
- Linux cleanup of a JVM detached from Gradle's process group passed an isolated
  readiness-gated process check. Cleanup only targets Java processes whose working
  directory is this invocation's fresh run directory.
- Evidence is retained in `.vanadium/port-26.2/` and
  `.vanadium/runs/smoke-a5nm6wjz/`. No existing `run/` world was used.

The new chunk pipeline drains spawning/thunder before random block ticks and finishes
random ticks before custom spawning, broadcasts and tracking. Block-entity registration
uses a concurrent queue drained on the server thread. The old blanket saved-data lock
deadlocked the new asynchronous writer during startup; the replacement protects cache
access and save snapshots without holding a monitor across disk IO or completion waits.
The removed bubble-column query and old spawn-region count workaround have no matching
26.2 path and were removed. Command bodies retain their inline layout, with the new
gamemaster permission API replacing integer permission checks.

These checks do not establish a performance improvement or modpack safety. Real players,
integrated-server restart, modded machines, portals and long-running worlds still need
validation. The historical tick audit's remaining scheduled-tick bookkeeping, passenger
serial-rule and shared-state findings have not all been resolved by this port.

## Historical validation on Minecraft 1.21.1

The following entries describe the previous Java 21/Yarn build, not validation of the 26.2 port.

## Setup validation (2026-09-12)

Validated locally on x86_64 Linux/NixOS with the locked OpenJDK 21.0.12.1:

- `./gradlew clean build`: passed; **78 tests**, 11 suites, no failures/errors/skips.
- `nix flake check --all-systems`: all four shells/checks evaluated; the native Linux
  development-file check built and passed. Other platforms were not executed.
- `python3 scripts/dev-server.py smoke --seconds 5`: passed initialization, status,
  chunk loading, block edit, ticking, save, and shutdown with pinned C2ME.
- `scripts/bench-server.sh 5`: passed with 200 zombies; produced a vanilla tick profile
  covering 100 ticks. This verifies the harness, not a performance improvement.
- `./gradlew benchScheduler`: completed both synthetic workloads.
- Harness failure checks: fragmented RCON reads, rejected RCON request IDs, missing EULA,
  and corrupt cached runtime jars behaved as expected.
- A forced one-second startup timeout exited nonzero and left no harness server processes.

The test runs retain their evidence under `.vanadium/runs/`. C2ME emitted optional
Starlight class-lookup warnings; both server runs completed without error-level log entries.
Graphical clients, multiple players, long-running worlds, and the manual scenarios above
remain unverified by this setup pass.

## Readability refactor validation (2026-09-13)

Tracking separates staging migration from dispatch and centralizes accessor casts.
Chunk locking separates ordered key construction, atomic reservation, and acquisition.
These are method extractions with the existing operation order and lock scopes preserved;
no performance improvement is claimed and no new dependency was introduced.
The command readability changes were subsequently reverted to keep the inline handlers.

- Focused chunk/config/tracking tests passed with `--rerun-tasks`; the full build passed
  all **97 tests**, access-widener validation, and remapped-jar generation.
- An isolated server with pinned C2ME passed every boolean toggle/set, integer bounds,
  worker restart status, save/reload/defaults, and startup/tick/save/shutdown checks.
- The existing temporary tracking fixture also passed staging expiry, identity membership,
  movement, and watcher checks using simulated server-side players.
- `git diff --check` passed. Evidence is in `.vanadium/readability-review/` and
  `.vanadium/runs/smoke-d0uubtdu/`. Real multiplayer and modpack compatibility remain untested.

## Serial type rules validation (2026-09-13)

The full build passed **101 tests**, including exact-ID parsing, list edits, invalid-input
rejection, and reset behavior. A disposable Fabric fixture invoked the applied entity and
block-entity dispatch injections: unlisted tickers remained queued, listed tickers ran
immediately on the server thread, and neither ticked twice. The block-entity fixture used
a wrapped direct invoker with an instrumented ticker.

Live command checks covered both rule lists, rejected malformed IDs without changing the
active list, and verified TOML save/reload and defaults. Startup/tick/save/shutdown with
pinned C2ME passed in `.vanadium/runs/smoke-7v637gn1/`; fixture sources and logs are in
`.vanadium/features/serial/`. Actual modded machines, callbacks, and multiplayer remain
untested. Serial rules do not establish safety for a mod's other shared state.

## Stage profiler validation (2026-09-13)

`./gradlew build` passed **105 tests**. Scheduler tests were rerun, and new tests cover
nearest-rank percentiles, bounded sample storage, cell/task counts, detaching a capture,
and reporting caller wait after all tasks finish even when a task fails.

The isolated server exercised `/vanadium profile` with 16 zombies, automatic completion,
manual stop, duplicate starts, duration bounds, and report retrieval. The report contained
nonzero entity work, percentiles, wait time, and task distribution. Startup/tick/save/shutdown
and serial dispatch checks also passed with pinned C2ME in `.vanadium/runs/smoke-esv9urx3/`.
Evidence is under `.vanadium/features/profile/`. This validates instrumentation, not a
performance gain; real modpack, multiplayer, and dual-Xeon measurements remain outstanding.

## Wave diagnostics validation (2026-09-13)

The full build passed **108 tests**; scheduler tests were rerun after the final change.
New deterministic tests use an injected clock and bounded latches to verify disabled mode,
warning thresholds, global rate limiting, blocked caller/worker reports, task labels,
negative cell coordinates, unchanged inline execution, and cleanup on completion/failure.

A temporary Fabric fixture blocked two real scheduler cells until the watchdog reported
them. The warning included the overworld, ENTITY stage, negative/zero cell coordinates,
both type labels, and stacks for the server thread and a worker. Both tasks then finished
without interruption. The final isolated run also passed diagnostic config bounds,
save/reload, disabling, profile commands, and startup/tick/save/shutdown with pinned C2ME.
Evidence is `.vanadium/features/diagnostics/server-final.log` and
`.vanadium/runs/smoke-xlaurxa7/`. `git diff --check` passed.

See `PERFORMANCE_REVIEW.md` for the disabled-mode overhead comparison. Real modpack stalls,
multiplayer behavior, integrated-server restart, and enabled-mode overhead remain untested.

### Configuration command simplification

Configuration changes now come from editing `config/vanadium.toml` and running
`/vanadium reload`. The `toggle`, `set`, `defaults`, and `save` subcommands were removed.
Both status forms, profiling, and benchmarking remain available; no F3 integration was added.

Validation: `./gradlew build` passed with 161 tests. An isolated server with pinned C2ME
rejected all four removed subcommands, preserved the file on those rejected commands,
reloaded a changed enabled flag, cell size, and serial entity list, then restored the original
configuration. Both status forms, profile start/stop/report, and startup/tick/save/shutdown
passed. Evidence: `.vanadium/f3/commands-smoke.log` and `.vanadium/runs/smoke-zkm_3buv/`.

### Block updates and dirty-chunk notifications (26.2)

The old block-update array redirect lost the first changed position in a section because
26.2 adds it through a local set reference. The live regression check reproduced the missing
creative block-update packet with the old mixin. Concurrent calls to `setChunkUnsaved` also
lost dirty entries before the queue fix; the reported random-tick crash came from the same
unsynchronized save set.

With the fixes, `scripts/check-block-updates.py` passed creative and survival breaking,
single/multiple updates and subsequent broadcasts, and retention of 16,384 distinct dirty
notifications from four concurrent producers. The isolated C2ME server also passed ticking,
save, and shutdown. Evidence: `.vanadium/block-fixes/` and `.vanadium/runs/smoke-nkbm0ge9/`.
Interactive client prediction and the original integrated-server world were not retested.
