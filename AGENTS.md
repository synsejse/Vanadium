# Working on Vanadium

Vanadium is a Kotlin Fabric mod (Java 25 bytecode) targeting **Minecraft 26.3 with official unobfuscated
names**. Mixins stay in Java; everything else is Kotlin, loaded through Fabric Language Kotlin.
Read `README.md`, `docs/src/content/docs/development.mdx`, and `docs/src/content/docs/architecture.mdx` before changing tick paths.
Version sources are `gradle.properties`, `build.gradle.kts`, and `gradle/wrapper/gradle-wrapper.properties`.
Verification conventions and typed tasks live in `buildSrc/`; Loom's pinned version comes from `gradle.properties`.
Do not upgrade Minecraft, mappings, Loom, Gradle, Kotlin, or runtime mods as incidental cleanup.

## Environment and commands

- Enter `nix-shell` or `nix develop` (use `nix develop path:.` while new Nix files are untracked).
  Both use `nix/shell.nix` and the nixpkgs pin in `flake.lock`.
  The shell supplies Java 25, nixfmt, common CLI tools, and Linux client runtime libraries.
  `nix-shell --run './gradlew runClient'` launches the interactive client.
- Always use `./gradlew`, not a system Gradle. Kotlin and Java compile with a Java 25 toolchain.
  The first build needs network access for Gradle, Minecraft, and Maven artifacts.
- Every task, what it checks and its `-P` properties are in [development.mdx](docs/src/content/docs/development.mdx#tasks)
  (live fixtures' properties and outputs in [profiling.mdx](docs/src/content/docs/profiling.mdx)). The ones you need most:
  - `./gradlew build`: compile, access widener, unit tests, `checkModDependencies` (a pin change in
    `gradle.properties` updates the `fabric.mod.json` minimum in the same commit), jars.
  - `./gradlew test --tests '<package>.*' --rerun-tasks`: focused tests; `--rerun-tasks` really reruns
    them when checking concurrency behaviour.
  - `./gradlew -p buildSrc check`: build-logic tests, when `buildSrc/` changes.
  - `./gradlew runIntegration` (`-Plive.jfr=true` adds a JFR capture with 100µs monitor thresholds):
    the live suite in a fresh `.vanadium/runs/` directory.
  - `./gradlew genSources`: Minecraft sources for reviewing injection targets.
- Live runs (`runIntegration`, `runFeatureBench`, `runMixedBench`, `runColdExplosion`) need an accepted
  `.vanadium/eula.txt`, never touch personal `run/`, and require configuration caching off (the
  repository default; `--no-configuration-cache` if overriding it). Never run two live servers at once.
- Benchmarks (`bench*`, `runFeatureBench`, `runMixedBench`, `runColdExplosion`) are opt-in performance
  investigations, never part of routine validation. Routine correctness validation is
  `./gradlew build runIntegration`.
- `nix flake check` checks Nix development files; it does **not** build Minecraft.

MixinTargetsTest checks vanilla member bindings without initializing Minecraft; live tests
are still required to validate injection points and callback signatures.
C2ME stays a release jar in the run directory's `mods/`; its nested mods are intentionally
not flattened into Gradle's development classpath. Fabric API and Cloth Config are Gradle-managed.

## Source map

Paths below are relative to `com/synsenetwork/vanadium/` under `src/main/kotlin/` (logic) or
`src/main/java/` (mixins, duck interfaces and the bootstrap-time classes listed below).
[architecture.mdx](docs/src/content/docs/architecture.mdx) explains how the parts work; the rules below are the invariants.

- **Kotlin vs. Java:** Mixin classes must be Java. So are the duck interfaces they implement
  (`vanadium$`-prefixed methods: `NavigationAccess`, `NavigationState`, `NodeAccess`, `NodePoolAccess`,
  `PathNavigationRegionAccess`, `InteractionAccess`, `PoiDistanceAccess`, `PoiTicketAccess`, `PoiSectionAccess`,
  `SectionStorageAccess`, `SpawnStateAccess`, `EntityTickAccess`, `ChunkTickAccess`, `BlockEntityTaskAccess`,
  `ScheduledTickAccess`, `CrowdAccess`, `CrowdSlotAccess`, `CrowdGridsAccess`) and code that runs during Mixin
  bootstrap (`mixin/VanadiumMixinPlugin.java`, `compat/C2ME.java`, `compat/Lithium.java`). Everything else is Kotlin.
  Kotlin APIs called from mixins are static on the JVM: `@JvmField`/`@JvmStatic`, or a `lateinit`
  property of an `object` (`Vanadium.config`, `Vanadium.scheduler`); call `vanadium$` methods from
  Kotlin with backticks.
- **Collection adapters:** an adapter for a field typed by a fastutil interface extends fastutil's
  `Abstract*` base. A field typed by a concrete fastutil class needs a subclass of that class
  (`ConcurrentLongSet` for vanilla's `LongLinkedOpenHashSet` fields, `ConcurrentLongObjectOpenHashMap`
  for Lithium's `Long2ObjectOpenHashMap` POI column index, `SynchronizedLong2ReferenceOpenHashMap` for
  Lithium's per-world callback map): override every method that would read the unused superclass
  storage, or, where the superclass storage is kept, guard every access to it.
- `Vanadium.kt`: config, server lifecycle ownership of the shared scheduler/pool, command registration.
- `tick/`: wave machinery only: adaptive region planning (`RegionGrid`), claim and dataflow waves, stage
  scheduler, caller-participating spin-then-park worker pool.
- `stage/`: what each stage queues, one Kotlin owner per level and stage, created at its vanilla owner's
  `<init>` RETURN: `EntityTickTasks`/`PersistentTickTasks` (entity wave), `ChunkStage` (spawning and chunk
  ticks), `BlockEntityStage`, `ScheduledTickStage`. Stage mixins are thin hooks that forward to them.
- `entity/`: lock-free entity section index; `CrowdGrid` cell grids prefilter box queries in crowded
  sections (every write to an entity's box or section must keep them in step: see its invariant).
- `chunk/`: chunk cache (`ParallelChunkCache`), server-thread load routing and per-position loading
  locks, chunk packet preparation.
- `collections/`: fastutil-compatible adapters over JDK/JCTools collections, including the concrete
  subtypes above and `PackedPositionKeys` (hash encoding for packed-position keys).
- `concurrent/`: entity ownership for the flat entity wave (`Interactions`, `OwnerLocks`, `ImpulseSlot` /
  `DeferredQueue` inboxes, `StableDistance`), POI claims (`PoiClaims`), striped locks and per-thread random sources.
- `tracking/`: area maps and nearby-player indexing for entity tracking.
- `navigation/`: navigation invalidation index, path-type cache, exact walk/finder fast paths, and Java navigation duck interfaces.
- `spawning/`: spawn counting and lazy count state.
- `diagnostics/`: tick profiles, JFR events, wave watchdog, crash-report section, benchmark capture, summaries, and result files.
- `mixin/`: each mixin and accessor sits in its target class's package relative to `net.minecraft`
  (`ServerLevel` → `mixin/server/level/ServerLevelMixin`); a nested class's mixin uses the outer class's
  package, and a multi-target mixin uses its targets' deepest common package, or the `mixin/` root when they
  share none (`SyncAllMixin`, `RandValueMixin`). Class names stay as they are.
  - Stage hooks: `server/level/` (`ServerLevelMixin`, `ServerChunkCacheMixin`, `SimulationChunkTrackerMixin`),
    `world/level/LevelMixin`, `world/level/entity/EntityTickListMixin`, `world/entity/EntityTickRuleMixin`,
    `world/ticks/LevelTicksMixin`.
  - Synchronization: `SyncAllMixin` (its targets get synchronized methods from the plugin) and
    `RandValueMixin` at the root; the rest (`Interactions` scopes, locks, concurrent collection
    replacements) sits with its vanilla target.
- `mixin/VanadiumMixinPlugin.java`: the Mixin config plugin: ASM synchronization of `SyncAllMixin` targets, the
  per-thread `randValue` rewrite (`RandValueMixin`) and the optional POI hook checks; it matches mixins by name.
- `compat/C2ME.java`: MixinSquared cancellation of two C2ME thread-detection mixins.
- `compat/LithiumCompat.kt` (POI section-storage and column-index replacement), `compat/Lithium.java`
  (MixinSquared canceller), `mixin/compat/lithium/` (mixins of Lithium classes): Lithium support (required,
  compiled against the pinned jar); `lithium:options` in `fabric.mod.json` disables the Lithium options whose
  job Vanadium does itself. Compatibility mixins of vanilla classes follow the vanilla package:
  `PoiTakeAtMixin` (Lithium's `takeAt`) and `PoiUnloadMixin` (C2ME's POI-unload veto) in
  `mixin/world/entity/ai/village/poi/`, `EntitySectionMovementTrackingMixin` in `mixin/world/level/entity/`.
- `config/`, `commands/`: TOML config (and its retired-key warnings), live options, validated
  `-Dvanadium.*` properties (`DeveloperProperties`), and Brigadier command registration. Every option,
  property and command is documented once in [configuration.mdx](docs/src/content/docs/configuration.mdx).
- `src/main/resources/vanadium.mixins.json`: required mixin registration.
- `src/main/resources/vanadium.accesswidener`: access required by mixins.
- `docs/`: the documentation site (Astro, TypeScript only, Tailwind; pages are MDX in `docs/src/content/docs/`).
  `cd docs && npm run format && npm run check && npm run build` after changing it (Prettier, 120 columns); see the documentation-site section of
  [development.mdx](docs/src/content/docs/development.mdx#documentation-site).
- `src/test/kotlin/`: JUnit 5 unit tests (`kotlin.test`), benchmarks, and the live test mods
  (`integration/`, `featurebench/`, `mixedbench/`, `coldexplosion/`, shared `live/`); `src/test/java/` holds only the
  fixtures' test mixins (feature bench, cold-explosion observer).

## Concurrency rules

- Worlds are processed sequentially through one shared scheduler. Enqueue/reset operations
  belong to the server thread; workers read/run regions only within a wave.
- Create a fresh scheduler/pool per server lifecycle and close the pool after the final wave.
  Chunk managers own their maintenance executors; `ChunkLoadLocks` owns no background threads.
- Entities tick in one flat wave with no regions: any two entity ticks may overlap,
  however close. A tick owns its vehicle tree; pushes go to the target's inbox; every other write to
  another tree, to blocks or to listeners must run inside an `Interactions` scope (`enter`/`enterShared`
  with the `Interactions.Write` it makes … `exit` in a `finally`). When adding or auditing entity code, look for writes to an entity that is not `this` and
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
  deferrable (`WorkerPool.runDeferrable`, rerun after the wave) rather than waiting. A task that
  cannot be deferred hands the work to the wave's caller with `WorkerPool.callOnWaveCaller`, which
  serves it between its own tasks and in the tail; hold no lock the server thread may wait for.
- Do not wait at a worker barrier while holding a monitor needed by those workers.
  Scheduled ticks are collected under vanilla scheduling and dispatched after its monitor releases.
- Keep lock ordering deterministic. Preserve reservation/eviction atomicity in `ChunkLoadLocks`,
  and take its chunk load locks only on the server thread: any other holder would wait
  for the server thread while holding one.
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
  initialized fields, Mixin may drop them. Install replacements explicitly at `<init>` RETURN, and carry over
  whatever the constructor configured on vanilla's collection (the chunk and POI tracker maps' default levels).

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

## Code style

Behaviour and performance are settled; new and changed code must also read well. In priority order:

- **No unnecessary abstractions (the first rule).** Don't wrap a call or an obvious operation in its own function,
  and don't add interfaces, classes or holders with one user or for hypothetical needs. Keep one concern's logic
  in one place, even if a function grows. Extract only for real complexity, reuse or a distinct responsibility; a
  seam that lets logic be unit-tested without Minecraft counts, and its KDoc says so.
- **Explicit control flow.** Guard clauses and early returns, `when` for several cases of one value, shallow
  nesting. Don't compress a decision into one expression: no `?.let { return it }`, chained Elvis fallbacks, or
  `let`/`run`/`also`/`apply`/`takeIf` chains where plain statements read better.
- **Names** are conventional and descriptive, not long. No variable that is only returned on the next line.
- **Kotlin features when they help:** collection operations off tick paths, `data class` for real data, `sealed`
  types for genuinely distinct states.
- **Types.** Nullable only where absence is a real outcome. Throw on violated rules. Avoid `!!`; prefer
  `checkNotNull(x) { "why" }` or a restructure. Trust data that is already validated.
- **Errors.** Let unexpected errors propagate; catch only where you recover, log or report. Never catch just to
  rethrow or turn a failure into a silent `null`/`false`. Use `try`/`finally` for cleanup.
- **Comments say why:** invariants, constraints, unusual decisions, and measurements with their build and date.
  No narration, no KDoc that restates the signature, no description of a removed design.
- `val` by default; mutable state only in the component that owns it. Named constants for thresholds.
- Minimal, targeted changes that preserve behaviour; moves in their own commit. Test critical behaviour, not getters.
- **Hot-path override.** On tick paths the concurrency and Kotlin hot-path rules above win: no capturing lambdas,
  boxing or per-item allocation. Keep primitive and indexed loops, `!!` on dense arrays whose slots are known to be
  filled, and JIT-motivated splits that a comment explains (`WorkerPool`'s single `runTask` call site, the
  `phase`/`drain` methods). A readability change that alters tick-path bytecode needs a `javap -c` check and an
  A/B benchmark ([profiling.mdx](docs/src/content/docs/profiling.mdx)); comments, renames and moving unchanged code do not.
