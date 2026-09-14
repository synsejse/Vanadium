# Working on Vanadium

Vanadium is a Java 25 Fabric mod targeting **Minecraft 26.2 with official unobfuscated names**.
Read `README.md`, `docs/DEVELOPMENT.md`, and `docs/ARCHITECTURE.md` before changing tick paths.
Version sources are `gradle.properties`, `build.gradle`, and `gradle/wrapper/gradle-wrapper.properties`.
Do not upgrade Minecraft, mappings, Loom, Gradle, or runtime mods as incidental cleanup.

## Environment and commands

- Enter `nix develop` (or `nix develop path:.` while new Nix files are untracked).
  The shell supplies Java 25, Python 3, shellcheck, nixfmt, and common CLI tools.
- Always use `./gradlew`, not a system Gradle. Java compilation uses a Java 25 toolchain.
- `./gradlew genSources`: generate Minecraft sources for reviewing injection targets.
- `./gradlew build`: compile, validate the access widener, test, and produce distributable jars.
- `./gradlew test --tests 'com.synsenetwork.vanadium.tick.*'`: focused scheduler tests.
- `./gradlew test --rerun-tasks`: actually rerun tests when checking concurrency behavior.
- `./gradlew benchScheduler`: synthetic scheduler overhead/allocation benchmark.
- `./gradlew benchWorkerPool`: small/large, balanced/uneven wave timing and allocation benchmark.
- `./gradlew benchAreaMap`: tracking-index movement, resize, and teleport microbenchmark.
- `nix flake check`: Nix environment and development-script checks; does **not** build Minecraft.
- `python3 scripts/dev-server.py prepare`: download and verify pinned C2ME into `.vanadium/mods`.
- `python3 scripts/dev-server.py smoke`: isolated server startup/tick/save/shutdown test.
  Requires an accepted `.vanadium/eula.txt`; see the development guide.
- `scripts/bench-server.sh 30`: isolated server profiler workload.

MixinTargetsTest checks vanilla member bindings without initializing Minecraft; live tests
are still required to validate injection points and callback signatures.

The first Gradle build needs network access for Gradle, Minecraft, and Maven artifacts.
C2ME stays a release jar in the run directory's `mods/`; its nested mods are intentionally
not flattened into Gradle's development classpath. Fabric API and Cloth Config are Gradle-managed.

## Source map

All Java paths below are relative to `src/main/java/com/synsenetwork/vanadium/`.

- `Vanadium.java`: config, server lifecycle ownership of the shared scheduler/pool, command registration.
- `tick/`: cell coordinates, four-color grid, stage scheduler, caller-participating worker pool.
- `chunk/`: chunk manager/cache and per-position loading locks.
- `concurrent/`: fastutil-compatible concurrent wrappers and striped entity locks.
- `tracking/`: area maps and nearby-player indexing for entity tracking.
- `mixin/server/world/`, `mixin/world/`: stage injection points and vanilla world access changes.
- Remaining `mixin/` packages: synchronization and concurrent collection replacements.
- `mixin/SynchronisePlugin.java`: ASM synchronization applied to `SyncAllMixin` targets.
- `compat/C2ME.java`: MixinSquared cancellation of two C2ME thread-detection mixins.
- `config/`, `commands/`: field-derived options, TOML config, live changes, tick benchmark.
- `src/main/resources/vanadium.mixins.json`: required mixin registration.
- `src/main/resources/vanadium.accesswidener`: access required by mixins.
- `src/test/java/`: JUnit 5 unit tests and standalone scheduler benchmark.

## Concurrency rules

- Worlds are processed sequentially through one shared scheduler. Enqueue/reset operations
  belong to the server thread; workers read/run cells only within a wave.
- Create a fresh scheduler/pool per server lifecycle and close the pool after the final wave.
  Chunk managers own their maintenance executors; `ChunkLockTable` owns no background threads.
- Same-color cells run concurrently; all tasks in a cell run serially in insertion order.
  Maintain a full completion barrier between colors and stages.
- The calling server thread participates in `WorkerPool.runWave`, and one-task waves run
  inline. Tick-path locks must work on both caller and worker threads; never gate those
  locks solely on `isWorkerThread()`.
- Wave input lists stay stable until `runWave` returns. Late helpers use a saved task count
  and must not read lists that the caller has already cleared/reused. Interruption waits
  for all tasks before restoring the caller's interrupt flag; it does not cancel a wave.
- Do not wait at a worker barrier while holding a monitor needed by those workers.
  Scheduled ticks are collected under vanilla scheduling and dispatched after its monitor releases.
- Keep lock ordering deterministic. Preserve reservation/eviction atomicity in `ChunkLockTable`.
- Publish loaded chunks into the cache before releasing their load lock; do not cache null results.
- Use floor division/modulo for negative chunk coordinates. Cell units, chunks, blocks,
  and packed positions are distinct; verify conversions at zero and negative boundaries.
- Four-color separation does not protect every cross-cell interaction. Preserve explicit
  entity locks, world synchronization, and collection semantics, including fastutil views.
- SavedDataStorage has asynchronous writes in 26.2: never synchronize saveAndJoin/close
  across their completion wait. Keep cache/snapshot locks separate from disk IO.
- Preserve disabled-stage fallback paths and the serial exceptions for projectiles/portals.
- C2ME is required; Lithium and VMP are declared incompatible. Recheck compatibility
  against the pinned C2ME jar when touching cancellation or Minecraft method injections.

## Change and validation discipline

- Follow the surrounding Java style (four spaces, existing package layout). Avoid broad formatting.
- Use deterministic gates/latches and bounded waits in new concurrency regression tests;
  do not use throughput thresholds as correctness assertions.
- Run focused tests during implementation, then `./gradlew build`. For mixin/world/tracking
  changes, also run the smoke test and document manual scenarios still untested.
- Unit tests do not boot Fabric or apply Minecraft mixins. A passing build is not proof
  of world safety, multiplayer correctness, or improved performance.
- Record benchmark hardware, worker count, cell size, flags, mod versions, and workload.
- Keep generated jars, worlds, logs, caches, profiler recordings, and IDE files untracked.
  Existing `run/` contains personal development state: do not overwrite or delete it.
  Automated runs use fresh `.vanadium/runs/` directories and retain evidence on failure.
- Check `git diff --check` and report actual validation results and any blockers.
