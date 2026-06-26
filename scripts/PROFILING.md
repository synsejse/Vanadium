# Profiling Vanadium

## Quick end-to-end MSPT (this repo)
`scripts/bench-server.sh [seconds]` boots the dev server headless, forceloads a 16x16-chunk
region, summons ~200 zombies, and runs the vanilla tick profiler (`/debug start|stop`). The
report lands in `run/debug/`. Run it on the current build and again after changes; compare
the world-tick section. Toggle `cellSize` in `run/config/vanadium.toml` between runs.

## Flight Recorder on a real world
Add to the server/client JVM args:
`-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=vanadium.jfr,settings=profile`
Open `vanadium.jfr` in JDK Mission Control. Look at: allocation by class (cell/list churn),
hot methods (worker compute vs barrier idle), and lock contention.
