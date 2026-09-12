# Profiling Vanadium

## Quick end-to-end MSPT (this repo)
Enter `nix develop` and follow the [runtime/EULA setup](../docs/DEVELOPMENT.md).
`scripts/bench-server.sh [seconds]` boots a fresh headless server, forceloads a 4x4-chunk
region, summons 200 persistent zombies, and runs the vanilla tick profiler
(`/debug start|stop`). Reports land in the printed `.vanadium/runs/bench-*/debug/`
directory and console output in `console.log`. Existing `run/` data is untouched.
Run it on the current build and again after changes; compare the world-tick section.
This generated workload is a repeatable smoke workload, not a representative multiplayer world.
For custom config comparisons, use a separate interactive run directory and keep the
world, mods, worker count, and JVM settings fixed.

## Flight Recorder on a real world
Add to the server/client JVM args:
`-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=vanadium.jfr,settings=profile`
Open `vanadium.jfr` in JDK Mission Control. Look at: allocation by class (cell/list churn),
hot methods (worker compute vs barrier idle), and lock contention.

The Nix shell includes the JDK diagnostic tools. For an already running server, find
its JVM with `jcmd -l`, then run:

```sh
jcmd <server-pid> JFR.start name=vanadium settings=profile duration=60s filename=/absolute/path/vanadium.jfr
jcmd <server-pid> Thread.print > /tmp/vanadium-threads.txt
jfr summary /absolute/path/vanadium.jfr
```

Capture several thread dumps a few seconds apart when investigating a stall. Use the
Minecraft JVM PID rather than the Gradle daemon PID. Mission Control is an optional
separate GUI tool; it is not included in the development shell.
