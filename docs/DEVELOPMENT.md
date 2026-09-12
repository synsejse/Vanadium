# Development and testing

## Toolchain

The build targets Minecraft **1.21.1**, Yarn **1.21.1+build.3**, and Java **21**.
Use the checked-in Gradle wrapper; its version and download checksum are in
`gradle/wrapper/gradle-wrapper.properties`. Loom and library versions remain in
`build.gradle` and `gradle.properties`.

```sh
nix develop
java -version
./gradlew build
```

The flake pins Nixpkgs through `flake.lock` and sets `JAVA_HOME`. It supplies the full
JDK (including `jcmd`, `jstack`, and `jfr`), Python 3 for the server harness, shellcheck,
nixfmt, Git, ripgrep, curl, jq, and archive tools. It defines shells for x86_64/aarch64
Linux and macOS; validation recorded below is specific to the local machine.
The shell is for compilation and headless server work. A graphical Minecraft client
on NixOS additionally needs a working graphics/audio/native-library environment.

Enable `nix-command` and `flakes` in your Nix installation if necessary. Optional direnv
integration uses the checked-in `.envrc`: with direnv/nix-direnv installed and your shell
hook configured, run `direnv allow`. Without Nix, install Java 21 and Python 3 and use
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
nix flake check
git diff --check
```

Distributable remapped jars and source jars land in `build/libs/`. HTML test reports
are in `build/reports/tests/test/index.html`, with XML in `build/test-results/test/`.
`clean` deletes `build/`; ordinary development does not need a clean build every time.
JUnit tests cover cells, barriers, workers, lock tables, collection wrappers, tracking-area coverage, and config.
They do not start Fabric or apply Minecraft mixins.

`nix flake check` evaluates the shell and runs development-file checks; the Java tests
must be run separately. CI builds/tests on Java 21 and retains jars and test reports.

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
