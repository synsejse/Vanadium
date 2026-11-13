# Development and testing

Minecraft **26.3**, official unobfuscated names, Java **25**. Versions and the C2ME release
URL/checksum and Loom/JCTools versions are pinned in `gradle.properties`; Kotlin is pinned
in `build.gradle.kts` and Gradle by the wrapper. Use `nix develop` and the checked-in `./gradlew`.
Initial dependency resolution needs network access.

Player-facing settings and commands are in [configuration](CONFIGURATION.md).

## Build and unit tests

```sh
./gradlew build
./gradlew test --tests 'com.synsenetwork.vanadium.tick.*' --rerun-tasks
./gradlew genSources
nix flake check
git diff --check
```

`build` compiles Kotlin/Java, validates the access widener, runs JUnit 5 tests, and produces
release/source jars in `build/libs/`. Test reports are in `build/reports/tests/test/`.
Build and verification configuration is separated:

- `build.gradle.kts`: mod dependencies, toolchains, resources and release packaging.
- `buildSrc/src/main/kotlin/vanadium.verification.gradle.kts`: unit tests, live fixtures and benchmarks.
- `buildSrc/src/main/kotlin/com/synsenetwork/vanadium/gradle/`: typed download/launch actions and fixture definitions.

Run `./gradlew -p buildSrc check` for the build-logic tests; they validate verified downloads
and rejection of corrupt artifacts without accessing the network.

Unit tests inspect vanilla bindings without booting Minecraft or applying mixins.
They are necessary but insufficient to validate a port.

## Live server integration tests

Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). If accepted, create
`.vanadium/eula.txt` containing `eula=true`, or copy your existing accepted file.
Then run:

```sh
./gradlew prepareRuntimeMods
./gradlew runIntegration
./gradlew runIntegration -Plive.jfr=true
```

`prepareRuntimeMods` downloads and SHA-512-verifies the pinned C2ME and Lithium releases into
`.vanadium/mods/`. Neither is flattened into Loom's development runtime classpath: live runs copy both
release jars into the run's `mods/`. Vanadium compiles against the verified Lithium jar (`compileOnly`),
and unit tests use it to check mixin bindings, including injections into methods Lithium merges.
Fabric API, Fabric Language Kotlin and Cloth Config are normal Gradle dependencies.

`runIntegration` builds a test-only Fabric mod from `src/test/kotlin/`, starts a dedicated
server through Loom, and runs the live assertions in-process. It also executes the mod's
status command through the server dispatcher. The fixture then advances
20 real server ticks, flush-saves, requests normal shutdown, and writes a completion marker
only after the server stops. The Gradle task checks that marker and has a five-minute timeout.
There is no nested Gradle process, RCON client, Python launcher, or process-scanning cleanup.
Loom finalizes launch paths before task execution, so live runs require configuration caching
to be off (the repository default). Use `--no-configuration-cache` if you enable it globally;
normal Gradle compilation/test up-to-date checks remain available.

Each invocation uses a fresh `.vanadium/runs/integration-UUID/` directory with a loopback-only
port, seed 12345, flat world, four helpers, isolation two, C2ME parallelism four, and a fixed
2GiB G1 heap. Personal `run/` state is never copied over or deleted. Worlds, `logs/latest.log`
and failure evidence stay in that directory. Test classes and the completion helper are
packaged only in `build/test-mods/integration-checks.jar`, never the release jar.

The live suite covers block update packets, dirty-chunk notifications, loaded chunk
publication/replacement/unload, both load-lock policies and disabled mode, scheduled ticks,
passengers, events, navigation invalidation, tracking diffs, spawn-count reduction,
item conservation and chunk packet encoding/sending. The JFR option records monitor/park
events at 100µs plus execution/allocation samples into the run's `live.jfr`.

Manual coverage is still needed for real multiplayer, portals/dimensions, exploration and
concurrent cold loads, interacting redstone/fluid machines, custom environment-driven spawn
costs, modpacks and integrated-server reopen. A passing build/live fixture is not a general
world-safety or performance claim.

## Benchmarks and profiling

```sh
./gradlew benchScheduler
./gradlew benchWorkerPool
./gradlew benchAreaMap
./gradlew benchSectionIndex
./gradlew benchCrowdQueries
./gradlew runFeatureBench
./gradlew runFeatureBench -Pbench.filter='spawn-.*' -Pbench.rounds=2 -Pbench.scale=0.1 -Pbench.jfr=false
```

`runFeatureBench` uses another fresh isolated server and a test-only mod. It runs paired
on/off workloads with warmup and alternating order, writing CSV and optional per-arm JFR
files under `feature-bench/` in that server directory, then ticks/saves/shuts down normally.
Defaults are four helpers, isolation two, 12 paired rounds, scale 1 and JFR enabled.
`bench.workers`, `bench.rounds`, `bench.scale`, `bench.filter` and `bench.jfr` are Gradle
properties. Reduced scale/rounds only check the harness; they are not performance evidence.
For independent JVM samples, invoke the task again; each run gets a new world and JVM.

For whole-server measurements use `/vanadium benchmark`, `/vanadium profile`, and the
JDK's `jcmd`/`jfr` tools; see [profiling](PROFILING.md). Old exploratory harnesses and 26.2 reports have been removed; current chart evidence is
in [performance](PERFORMANCE.md).
Record hardware, worker/isolation/C2ME settings, heap, flags, mod versions and workload whenever
publishing new measurements. Stage and world profile intervals overlap; do not add them.

## Interactive development

`./gradlew runServer` and `runClient` retain their normal Loom behavior. For interactive
use, place the verified C2ME and Lithium releases in that run directory's `mods/`,
avoiding duplicate versions. `-PvanadiumRunDir=/absolute/path` selects a separate interactive server directory.
The Nix shell supplies JDK diagnostic tools and Linux Vulkan/OpenGL loaders; graphical
clients still require a working host display/driver. Do not delete live Gradle cache locks
if IDE imports contend with the CLI; let the other operation finish instead.
