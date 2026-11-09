# Profiling Vanadium

Use the JDK tools supplied by `nix develop`, or open recordings in JDK Mission Control.
Performance measurements must hold worker count, cell size, C2ME budget, heap, flags,
mods and workload fixed. `enabled=false` keeps structural mixins and loaded-chunk lookups;
it is not a no-Vanadium control.

## In-game commands

- `/vanadium benchmark 30` measures uncapped whole-server throughput using tick sprint.
- `/vanadium profile 30` captures tick/stage distributions at the current tick rate.
- `/vanadium profile report` shows the last completed capture.

Results are logged as `VANADIUM_RESULT` JSON lines and written under `vanadium/results/`
in the server directory, including configuration, versions, heap and processor count.
World totals include stage work; do not sum overlapping measurements. Caller tail wait
is not a measurement of lock contention.

## Flight Recorder

Use the Minecraft JVM PID from `jcmd -l`, not the Gradle daemon PID. Always use an absolute
recording filename:

```sh
jcmd <pid> JFR.start name=vanadium settings=profile duration=60s filename=/absolute/path/vanadium.jfr 'jdk.JavaMonitorEnter#threshold=100us' 'jdk.ThreadPark#threshold=100us' 'jdk.ExecutionSample#period=5ms'
jcmd <pid> Thread.print
jfr summary /absolute/path/vanadium.jfr
jfr print --events 'vanadium.*,jdk.JavaMonitorEnter,jdk.ThreadPark' /absolute/path/vanadium.jfr
```

Vanadium emits `vanadium.ServerTick` (work excluding idle), `vanadium.Wave` (stage, world,
cells, tasks, participants and cell size), and `vanadium.Preparation` (independent jobs).
Correlate these intervals with worker samples, monitor blocking, parks and allocation
samples in Mission Control. The serial remainder of tick work bounds potential scaling.
Cell duration includes lock waits and does not establish CPU utilization by itself.

`./gradlew runIntegration -Plive.jfr=true` captures the isolated live suite with 100µs
monitor thresholds. `./gradlew runFeatureBench` writes separate on/off JFR recordings and
paired timing/allocation CSV files. See [development](DEVELOPMENT.md) for filters and scale.
Feature timing samples exclude active JFR and are subsystem measurements rather than
multiplayer TPS or p99 latency. The 26.2 reports in [archive/](archive/) retain their original
hardware/configuration evidence; their former Python commands are historical, not current
repository tooling.
