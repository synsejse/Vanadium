# Working on Vanadium

Vanadium is a Kotlin Fabric mod (Java 25 bytecode) targeting **Minecraft 26.3 with official unobfuscated
names**. Mixins stay in Java; everything else is Kotlin, loaded through Fabric Language Kotlin.
Read `README.md`, `docs/DEVELOPMENT.md`, and `docs/ARCHITECTURE.md` before changing tick paths.
Version sources are `gradle.properties`, `build.gradle.kts`, and `gradle/wrapper/gradle-wrapper.properties`.
Verification conventions and typed tasks live in `buildSrc/`; Loom's pinned version comes from `gradle.properties`.
Do not upgrade Minecraft, mappings, Loom, Gradle, Kotlin, or runtime mods as incidental cleanup.

## Environment and commands

- Enter `nix develop` (or `nix develop path:.` while new Nix files are untracked).
  The shell supplies Java 25, nixfmt, and common CLI tools.
- Always use `./gradlew`, not a system Gradle. Kotlin and Java compile with a Java 25 toolchain.
- `./gradlew genSources`: generate Minecraft sources for reviewing injection targets.
- `./gradlew build`: compile, validate the access widener, test, and produce distributable jars.
- `./gradlew -p buildSrc check`: validate build logic and checksum/download task tests.
- `./gradlew test --tests 'com.synsenetwork.vanadium.tick.*'`: focused scheduler tests.
- `./gradlew test --rerun-tasks`: actually rerun tests when checking concurrency behavior.
- `./gradlew benchScheduler`: synthetic scheduler overhead/allocation benchmark.
- `./gradlew benchWorkerPool`: small/large, balanced/uneven wave timing and allocation benchmark.
- `./gradlew benchAreaMap`: tracking-index movement, resize, and teleport microbenchmark.
- `./gradlew benchSectionIndex`: entity section box queries, column index against the previous two-map layout.
- `./gradlew prepareRuntimeMods`: download and verify pinned C2ME and Lithium into `.vanadium/mods`.
- `./gradlew runIntegration`: live Kotlin/Java tests plus startup/tick/save/shutdown in a fresh
  `.vanadium/runs/` directory. Requires accepted `.vanadium/eula.txt`; never touches personal `run/`.
  Live runs require configuration caching off (the repository default); use `--no-configuration-cache` if overriding it.
- `./gradlew runFeatureBench`: paired on/off fixture benchmarks in a fresh server JVM;
  `-Pbench.filter=...`, `-Pbench.rounds=...`, `-Pbench.scale=...` configure cases.
- `./gradlew runIntegration -Plive.jfr=true`: include a JFR capture with 100µs monitor thresholds.
- `nix flake check`: checks Nix development files; does **not** build Minecraft.

MixinTargetsTest checks vanilla member bindings without initializing Minecraft; live tests
are still required to validate injection points and callback signatures.

The first Gradle build needs network access for Gradle, Minecraft, and Maven artifacts.
C2ME stays a release jar in the run directory's `mods/`; its nested mods are intentionally
not flattened into Gradle's development classpath. Fabric API and Cloth Config are Gradle-managed.

## Source map

Paths below are relative to `com/synsenetwork/vanadium/` under `src/main/kotlin/` (logic) or
`src/main/java/` (mixins and the bootstrap-time classes listed below).

- **Kotlin vs. Java:** Mixin classes must be Java. So are the duck interfaces they implement
  (`vanadium$`-prefixed methods: `NavigationAccess`, `NavigationState`, `InteractionAccess`,
  `PoiDistanceAccess`, `EntityTickAccess`, `ChunkTickAccess`, `ScheduledTickAccess`, `CrowdAccess`, `CrowdSlotAccess`,
  `CrowdedSectionsAccess`) and code that runs during Mixin
  bootstrap (`mixin/SynchronisePlugin.java`, `compat/C2ME.java`, `compat/Lithium.java`). Everything else is Kotlin.
  Kotlin APIs called from mixins use `@JvmField`/`@JvmStatic` (`Vanadium.config`, `Vanadium.scheduler`);
  call `vanadium$` methods from Kotlin with backticks. Keep collection adapters on fastutil's
  `Abstract*` bases and override every method that would read unused superclass storage.
- `Vanadium.kt`: config, server lifecycle ownership of the shared scheduler/pool, command registration.
- `tick/`: adaptive region planning (`RegionGrid`), claim and dataflow waves, batched stage collection,
  stage scheduler, caller-participating spin-then-park worker pool.
- `entity/`: lock-free entity section index; `CrowdedSection` cell grids prefilter box queries in crowded
  sections (every write to an entity's box or section must keep them in step: see its invariant).
- `chunk/`: chunk manager/cache and per-position loading locks.
- `collections/`: fastutil-compatible adapters over JDK/JCTools collections.
- `concurrent/`: entity ownership for the flat entity wave (`Interactions`, `OwnerLocks`), striped
  locks and per-thread random sources.
- `tracking/`: area maps and nearby-player indexing for entity tracking.
- `mixin/server/world/`, `mixin/world/`: stage injection points and vanilla world access changes.
- Remaining `mixin/` packages: synchronization and concurrent collection replacements.
- `mixin/SynchronisePlugin.java`: ASM synchronization applied to `SyncAllMixin` targets.
- `compat/C2ME.java`: MixinSquared cancellation of two C2ME thread-detection mixins.
- `compat/LithiumCompat.kt`, `compat/Lithium.java` (MixinSquared canceller), `mixin/compat/lithium/`:
  Lithium support (required, compiled against the pinned jar); `lithium:options` in `fabric.mod.json`
  disables the Lithium options whose job Vanadium does itself.
- `config/`, `commands/`: TOML config, status, live reload, tick benchmark.
- `src/main/resources/vanadium.mixins.json`: required mixin registration.
- `src/main/resources/vanadium.accesswidener`: access required by mixins.
- `src/test/kotlin/`: JUnit 5 unit tests (`kotlin.test`), benchmarks, and the live test mods
  (`integration/`, `featurebench/`); `src/test/java/` holds only the feature-bench test mixin.

## Concurrency rules

- Worlds are processed sequentially through one shared scheduler. Enqueue/reset operations
  belong to the server thread; workers read/run regions only within a wave.
- Create a fresh scheduler/pool per server lifecycle and close the pool after the final wave.
  Chunk managers own their maintenance executors; `ChunkLockTable` owns no background threads.
- Entities tick in one flat wave with no regions: any two entity ticks may overlap,
  however close. A tick owns its vehicle tree; pushes go to the target's inbox; every other write to
  another tree, to blocks or to listeners must run inside an `Interactions` scope (`enter`/`enterShared`
  … `exit`). When adding or auditing entity code, look for writes to an entity that is not `this` and
  for pairwise check-then-act (both sides run the same logic in one tick): give the whole operation a
  pair scope (`enterPair`) and recheck its condition inside. Avoid reaching a scope while holding
  another lock or monitor that a scope's body can take: such a deadlock is only broken by the 100 ms
  timeout. Check the `cycles`/`timeouts` counts (`-Dvanadium.interactionLogWaves=N`) after touching
  interactions.
- In region-scheduled stages (everything but entities), work in chunks within `isolation` chunks of
  each other (Chebyshev distance) never runs concurrently; all tasks in a region run serially in enqueue order. Cheap regions are ordered by
  colour; heavy regions claim dynamically. Keep colouring proper and conflict lists symmetric. Maintain a full
  completion barrier between stages. Order between conflicting regions may vary between ticks.
- Keep tick-path hot spots lock-free: every moving mob, chunk lookup or random draw on every
  worker multiplies any shared monitor. Check JFR monitor events (threshold 100µs) on the
  target machine after touching entity, chunk, navigation or saved-data paths.
- The calling server thread participates in `WorkerPool.runWave`, and one-task waves run
  inline. Tick-path locks must work on both caller and worker threads; never gate those
  locks solely on `isWorkerThread()`.
- Wave input lists stay stable until `runWave` returns. Late helpers use a saved task count
  and must not read lists that the caller has already cleared/reused. Interruption waits
  for all tasks before restoring the caller's interrupt flag; it does not cancel a wave.
- Workers must never block on work only the server thread can perform (chunk loading/generation,
  main-thread tasks): it participates in the wave, so the wait never ends. Make such tasks
  deferrable (`WorkerPool.runDeferrable`, rerun after the wave) rather than waiting.
- Do not wait at a worker barrier while holding a monitor needed by those workers.
  Scheduled ticks are collected under vanilla scheduling and dispatched after its monitor releases.
- Keep lock ordering deterministic. Preserve reservation/eviction atomicity in `ChunkLockTable`.
- Full chunks are published by `LevelChunk.setLoaded` before their load completes; recheck that
  map under the load lock, and never publish unloaded or null chunks.
- Use floor division/modulo for negative chunk coordinates. Chunks, blocks,
  and packed positions are distinct; verify conversions at zero and negative boundaries.
- Isolation does not protect every cross-region interaction. Preserve world synchronization
  and collection semantics, including fastutil views.
- SavedDataStorage has asynchronous writes in 26.3: never synchronize saveAndJoin/close
  across their completion wait. Keep cache/snapshot locks separate from disk IO.
- Preserve disabled-stage fallback paths and the serial exceptions for projectiles/portals.
- C2ME is required; other mods that rewrite ticking (VMP, Async, Moonrise, ServerCore, MCMT, DimThread)
  are declared incompatible. Recheck compatibility against the pinned C2ME jar when touching
  cancellation or Minecraft method injections.
- Lithium is required. When a change touches a method or shared structure Lithium also rewrites,
  either disable that Lithium option in `lithium:options` or make both compose and keep Lithium's
  shared state thread-safe. Lithium state reached by more than one region (per-world maps, listener
  registries, lazily filled caches) needs the same care as vanilla's. Injections into methods Lithium
  merges use priority > 1000 and `require = 0` (users can disable Lithium options).
- The server thread runs chunk-system work (status changes, mods' load/unload callbacks) when it loads
  during a wave; prefer `WorkerPool.runDeferrable`, or queue callbacks with `runAfterParallelWave`.
- Do not rely on `@Shadow` field initializers to replace vanilla collections: once a mixin adds other
  initialized fields, Mixin may drop them. Install replacements explicitly at `<init>` RETURN.

## Change and validation discipline

- Follow the surrounding style (four spaces, existing package layout). Avoid broad formatting.
- Kotlin hot paths: a boxed value (`Long?`, collection element) assigned to a non-null local is unboxed
  and re-boxed on the next nullable write; keep it in a nullable local instead. Avoid capturing
  lambdas in per-item paths (enqueue, lookups). Check suspicious hot classes with
  `javap -c` for `valueOf`/`invokedynamic`, and compare JFR allocation samples against a baseline.
- Use deterministic gates/latches and bounded waits in new concurrency regression tests;
  do not use throughput thresholds as correctness assertions.
- Run focused tests during implementation, then `./gradlew build`. For mixin/world/tracking
  changes, also run the smoke test and document manual scenarios still untested.
- Unit tests do not boot Fabric or apply Minecraft mixins. A passing build is not proof
  of world safety, multiplayer correctness, or improved performance.
- Record benchmark hardware, worker count, isolation, flags, mod versions, and workload.
- Keep generated jars, worlds, logs, caches, profiler recordings, and IDE files untracked.
  Existing `run/` contains personal development state: do not overwrite or delete it.
  Automated runs use fresh `.vanadium/runs/` directories and retain evidence on failure.
- Check `git diff --check` and report actual validation results and any blockers.
