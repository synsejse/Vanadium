#!/usr/bin/env python3
"""Run paired feature benchmarks in fresh Fabric/C2ME JVMs, retaining CSV and JFR evidence."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("dev_server", ROOT / "scripts/dev-server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def run_fork(args, fork, mods):
    run = Path(tempfile.mkdtemp(prefix="features-", dir=server.STATE / "runs"))
    (run / "mods").mkdir()
    (run / "config").mkdir()
    for mod in mods:
        shutil.copy2(mod, run / "mods" / mod.name)
    shutil.copy2(server.STATE / "eula.txt", run / "eula.txt")
    (run / "config/vanadium.toml").write_text(f"workers = {args.workers}\ncellSize = 2\n")
    (run / "config/c2me.toml").write_text("version = 3\nglobalExecutorParallelism = 4\n")
    port, rcon_port = server.free_port(), server.free_port()
    while port == rcon_port:
        rcon_port = server.free_port()
    password = secrets.token_hex(24)
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nenable-rcon=true\n"
        f"rcon.port={rcon_port}\nrcon.password={password}\n"
        "online-mode=true\nlevel-seed=12345\nlevel-type=minecraft:flat\n"
        'generator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},'
        '{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],'
        '"biome":"minecraft:plains","structure_overrides":[]}\n'
        "view-distance=4\nsimulation-distance=4\nmax-players=1\n"
        "spawn-protection=0\nmax-tick-time=-1\n"
    )
    metadata = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "cpu": subprocess.check_output(["lscpu"], text=True),
        "fork": fork, "workers": args.workers, "cell_size": 2, "c2me_workers": 4,
        "heap": "2GiB fixed", "gc": "G1", "scale": args.scale, "rounds": args.rounds,
        "filter": args.filter, "jfr": fork == 0 and not args.no_jfr,
        "runtime_mods": json.loads((ROOT / "scripts/runtime-mods.json").read_text()),
    }
    (run / "benchmark-environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (
        "-Xms2g -Xmx2g -XX:+UseG1GC "
        f"-Dvanadium.bench.workers={args.workers} -Dvanadium.bench.fork={fork} "
        f"-Dvanadium.bench.rounds={args.rounds} -Dvanadium.bench.scale={args.scale} "
        f"-Dvanadium.bench.jfr={str(metadata['jfr']).lower()} "
        f"-Dvanadium.bench.filter={args.filter}"
    )
    log = run / "console.log"
    print(f"Fork {fork}: {run}", flush=True)
    with log.open("w") as output:
        process = subprocess.Popen(
            [str(ROOT / "gradlew"), "--no-daemon", "--no-configuration-cache", "--console=plain",
             "-Dorg.gradle.jvmargs=-Xms256m -Xmx1g",
             f"-PvanadiumRunDir={run}", "runServer", "--args=nogui"],
            cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
        )
        try:
            server.wait_for(process, log, "FEATURE_BENCH_PASSED", args.timeout)
            server.wait_for(process, log, "RCON running on", args.timeout)
            server.rcon(rcon_port, password, "save-all flush", expect="Saved the game")
            server.rcon(rcon_port, password, "stop")
            if process.wait(timeout=120) != 0:
                raise RuntimeError(f"Server failed; see {log}")
            contents = log.read_text(errors="replace")
            if "/ERROR]" in contents or " ERROR]" in contents:
                raise RuntimeError(f"Server logged errors; see {log}")
            print(f"Passed: {run}", flush=True)
        finally:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(process.pid, sig)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
            server.stop_detached_server(run)
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--forks", type=server.positive_int, default=3)
    parser.add_argument("--workers", type=server.positive_int, default=4)
    parser.add_argument("--rounds", type=server.positive_int, default=12)
    parser.add_argument("--scale", type=float, default=1, help="duration multiplier; <1 is for harness checks only")
    parser.add_argument("--filter", default="", help="Java regular expression selecting workload names")
    parser.add_argument("--no-jfr", action="store_true")
    parser.add_argument("--timeout", type=server.positive_int, default=1800)
    args = parser.parse_args()
    if args.scale <= 0:
        parser.error("--scale must be positive")
    eula = server.STATE / "eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text().splitlines():
        parser.error("Accepted .vanadium/eula.txt required; see docs/DEVELOPMENT.md")
    subprocess.run([str(ROOT / "gradlew"), "featureBenchJar"], cwd=ROOT, check=True)
    mods = [*server.prepare(), ROOT / "build/test-mods/feature-bench.jar"]
    (server.STATE / "runs").mkdir(exist_ok=True)
    runs = [str(run_fork(args, fork, mods)) for fork in range(args.forks)]
    print(json.dumps({"runs": runs}, indent=2), flush=True)


if __name__ == "__main__":
    main()
