# Initial Minecraft 26.3 port

The branch targets Minecraft 26.3 with official unobfuscated names and Java 25.
Loom 1.17.12, Gradle 9.6.0, Kotlin 2.4.21 / Fabric Language Kotlin 1.14.2, and
Fabric Loader 0.20.1 remain unchanged. Version pins live in `gradle.properties`,
`build.gradle.kts` and the Gradle wrapper properties.

The target-specific dependencies are Fabric API 0.165.2+26.3, Cloth Config 26.3.159,
and C2ME 0.4.2-alpha.0.116+26.3. C2ME's release URL and SHA-512 are pinned in
`gradle.properties`; its nested mods remain outside the compilation classpath.
[Fabric's porting notes](https://fabricmc.net/2026/09/15/263.html) confirm that this
Minecraft release uses the existing Loom/Gradle generation.

## Binding and behavior changes

- `ServerChunkCache` no longer takes a saved-data supplier. The level constructor
  redirect and subclass constructor now match the ten-argument vanilla signature.
- `tickChunks(ProfilerFiller)` and its stage callbacks no longer take elapsed time.
  The spawning/thunder and random/precipitation barriers remain in vanilla order.
- `NaturalSpawner.createState` now takes a `ServerLevel` and resolves spawn costs
  through environment attributes. A post-expression bridge captures the final iterable
  after C2ME's simulated-chunk filter, then scopes per-worker entity slices around
  vanilla counting. Each slice retains vanilla's new cost logic; overrides clear in
  `finally`. Capturing the input currently creates one empty state per preparation;
  its allocation/performance cost has not been benchmarked against a 26.3 baseline.
- Tracking uses the native `syncPosition` request instead of rounding an integer
  update interval. The removed interval/tick-count accessors are unnecessary with
  `UpdateInterval`; live checks cover both periodic updates and `NEVER`.
- Packet fixtures encode with `ClientboundLevelChunkPacketData.STREAM_CODEC`.
  Entity section fixtures use the standalone `Continuation` enum.
- The two C2ME cancellation targets were checked in the pinned 26.3 release and
  remain present; Fabric boots with that release in the live fixture.

## Repository cleanup

The custom Python launchers, RCON/process cleanup, sweeps, report generators and shell
benchmark wrapper have been removed. Current tests and fixtures are Kotlin/Java under
`src/test/`, invoked by Gradle. Runtime preparation is a checksum-verifying JVM Gradle task.
The standard Gradle wrappers and normal Nix/JSON/YAML build metadata remain.

`runIntegration` and `runFeatureBench` use Loom's server launcher with fresh isolated
`.vanadium/runs/` directories and retained logs/worlds. Fixtures advance real ticks,
flush-save and stop themselves. Completion markers, JVM exit status, error logs and
optional JFR output are checked by Gradle. Live runs require configuration caching off
(the repository default), because Loom finalizes launch directories during configuration.
Flat-world settings include explicit bedrock/dirt/grass layers and plains biome, avoiding
26.3's empty-settings parse error. They never modify personal `run/` state or accept the EULA on the user's behalf.

Current workflows are in [DEVELOPMENT.md](DEVELOPMENT.md) and [PROFILING.md](PROFILING.md).
The 26.2 measurement reports and investigation plans are historical evidence under
[archive/](archive/), not instructions for the current branch.

## Validation and limits

- Focused mixin-binding/scheduler tests with `--rerun-tasks` passed.
- `./gradlew build --rerun-tasks` passed: 143 unit tests, no failures; access widener validated.
- `./gradlew runIntegration -Plive.jfr=true` passed the complete live suite plus real
  tick/save/shutdown. Evidence: `.vanadium/runs/integration-8cb73add-8d7a-49df-80be-31321d32c637/`.
- `./gradlew runFeatureBench -Pbench.rounds=1 -Pbench.scale=0.02 -Pbench.jfr=false`
  completed every fixture case and normal shutdown. This reduced run checks the harness,
  not performance. Evidence: `.vanadium/runs/featureBench-29520a71-73e0-4d18-95d5-8e41b489f5f6/`.
- `nix flake check` passed on x86_64 Linux; other host platforms were not executed.
- `git diff --check` passed. No Python files or custom shell harnesses remain tracked.

The integration JFR was recorded on the dual Xeon E5-2699 v3 KVM development guest
(72 vCPUs), with Java 25.0.4.1, four helpers, cell size two, C2ME parallelism four,
a fixed 2GiB G1 heap, default enabled features and the pinned mods above. It includes
startup, the integration fixtures and 20 ordinary ticks in fresh seed-12345 flat worlds.
Monitor/park thresholds were 100µs; execution samples were collected every 5ms.
The largest blocked-time stacks were JDK ZipFS resource/registry bootstrap work.
Allocation samples were likewise dominated by startup/class-file/resource work.
This is a compatibility/diagnostic capture, not a steady-state throughput or allocation
regression comparison with 26.2. No speedup is claimed.

Remaining manual work: real players and packet load, portals/dimensions, exploration
and simultaneous cold loads, charged environment-driven spawn rules and real mob caps,
interacting redstone/fluid machines, resource reloads/custom registries, modpacks, client configuration UI, and integrated
server reopen. The remaining shared vanilla four-entry chunk-cache miss path still
needs its separate concurrency audit. Passing these fixtures does not certify world
safety or mod compatibility.

## Verification follow-up

A fresh clean build and a forced follow-up rebuild passed on 2026-11-09. Two JVM
release-distribution tests were added; the suite now has **143 passing tests**. They inspect
the built jar for matching version/Minecraft metadata, Java 25 bytecode, packaged mixin
classes, and absence of JUnit, benchmark, integration and auto-shutdown fixture classes.
No production port fixes were needed during this verification pass.

The rebuilt artifacts passed another clean-log integration/command/tick/save/shutdown run:
`.vanadium/runs/integration-a9101ff1-154b-4704-8daf-1f39275645b2/`.
Its recording contains all 20 server ticks, stage events and allocation samples; the actual
JFR settings confirm 100,000ns monitor and park thresholds.

The **full default** `runFeatureBench` also passed: 20 cases, 12 paired rounds per case,
scale 1, JFR enabled. All **480 timing rows** have positive operation/time counts and
non-negative CPU/allocation counters. All **40 per-arm JFR files** were parsed to EOF
successfully. Evidence: `.vanadium/runs/featureBench-235d6217-3dc8-4894-aa82-757c320ea822/`.
The full run retained the same four-helper/cell-two/C2ME-four/2GiB G1 configuration and
pinned versions described above. This establishes fixture and recording integrity in one
JVM, not a cross-version or multiplayer performance comparison.

`nix flake check` and `git diff --check` passed again. The manual coverage and production
performance limits above still apply.
