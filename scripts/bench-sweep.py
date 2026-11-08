#!/usr/bin/env python3
"""Worker/cell sweep on a live Fabric+C2ME server: uncapped TPS via /vanadium benchmark,
stage profile at 20 TPS, and per-thread CPU from /proc. Fresh flat world per server JVM.

Workloads (--workloads, comma separated; use ';' when a workload itself contains commas):
  idle                 forced chunks only
  mobs                 summon --mobs-per-chunk persistent zombies per forced chunk, then measure
                       (--off adds a Vanadium-disabled measurement in the same JVM)
  cells:1,2,3          re-measure with each cell size (live reload)
  variants:R:a=toml|b=toml
                       R alternating rounds (ABBA order) of TOML variants; ',' separates lines,
                       e.g. "mobs;variants:2:free=colorBarriers = false|colors=colorBarriers = true"
  jfr                  record a JFR profile (low lock/park thresholds) during one measurement
Accepted .vanadium/eula.txt required; see docs/DEVELOPMENT.md. Run inside nix develop.
"""

import argparse
import importlib.util
import json
import os
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(os.environ.get("VANADIUM_ROOT", Path(__file__).resolve().parent.parent))
spec = importlib.util.spec_from_file_location("dev_server", ROOT / "scripts/dev-server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
CLK = os.sysconf("SC_CLK_TCK")


class Rcon:
    def __init__(self, port, password):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=120)
        self.id = 1
        kind, _ = server.packet(self.sock, self.id, 3, password)
        if kind != 2:
            raise RuntimeError("rcon auth")

    def __call__(self, command):
        self.id += 1
        _, reply = server.packet(self.sock, self.id, 2, command)
        return reply


def find_server_pid(run):
    for entry in Path("/proc").iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            if (entry / "cwd").resolve() == run and (entry / "comm").read_text().strip() == "java":
                return int(entry.name)
        except OSError:
            pass
    return None


def thread_cpu(pid):
    """{thread name: cpu seconds} aggregated by name class."""
    out = {}
    for task in Path(f"/proc/{pid}/task").iterdir():
        try:
            stat = (task / "stat").read_text()
            name = (task / "comm").read_text().strip()
        except OSError:
            continue
        fields = stat[stat.rindex(")") + 2:].split()
        secs = (int(fields[11]) + int(fields[12])) / CLK
        key = re.sub(r"[-#\s]*\d+$", "", name) or name
        out[key] = out.get(key, 0.0) + secs
    return out


def diff(a, b):
    return {k: round(b.get(k, 0) - a.get(k, 0), 2) for k in b if b.get(k, 0) - a.get(k, 0) > 0.05}


def wait_log(log, pattern, start_offset, timeout):
    deadline = time.monotonic() + timeout
    rx = re.compile(pattern)
    while time.monotonic() < deadline:
        text = log.read_text(errors="replace")[start_offset:]
        m = rx.search(text)
        if m:
            return m, text
        time.sleep(0.5)
    raise RuntimeError(f"timeout waiting for {pattern}")


def gamerule(r, names, value):
    for name in names:
        reply = r(f"gamerule {name} {value}")
        if "Incorrect" not in reply and "Unknown" not in reply:
            return reply
    return reply


def bench(r, log, pid, label, results, extra, profile_after=True):
    offset = len(log.read_text(errors="replace"))
    cpu0 = thread_cpu(pid)
    t0 = time.monotonic()
    r("vanadium benchmark")
    m, _ = wait_log(log, r"benchmark — ([\d,]+) ticks in ([\d.]+)s, avg ([\d.]+) TPS, peak ([\d,]+)", offset, 90)
    wall = time.monotonic() - t0
    cpu = diff(cpu0, thread_cpu(pid))
    tps = float(m.group(3))
    # Profile at 20 TPS: per-tick work percentiles and stage breakdown.
    offset = len(log.read_text(errors="replace"))
    if not profile_after:
        row = {"label": label, "tps": tps, "ticks": int(m.group(1).replace(",", "")),
               "peak": int(m.group(4).replace(",", "")), "wall": round(wall, 1),
               "cpu_s": cpu, "cpu_total": round(sum(cpu.values()), 1), **extra}
        results.append(row)
        print(json.dumps(row), flush=True)
        return row, ""
    r("vanadium profile 10")
    time.sleep(11)
    try:
        _, text = wait_log(log, r"(?s)Vanadium profile.*?(?=\n\[\d\d:\d\d:\d\d\][^\n]*(?:Rcon|Server thread/INFO\]: \[Not Secure\])|\Z)", offset, 30)
    except RuntimeError:
        text = ""
    profile = log.read_text(errors="replace")[offset:]
    row = {"label": label, "tps": tps, "ticks": int(m.group(1).replace(",", "")),
           "peak": int(m.group(4).replace(",", "")), "wall": round(wall, 1),
           "cpu_s": cpu, "cpu_total": round(sum(cpu.values()), 1), **extra}
    results.append(row)
    print(json.dumps(row), flush=True)
    return row, profile


def run_point(args, workers, cell, results, outdir):
    mods = server.prepare()
    runs = server.STATE / "runs"
    runs.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix=f"sweep-w{workers}-c{cell}-", dir=runs))
    (run / "mods").mkdir()
    (run / "config").mkdir()
    for mod in mods:
        shutil.copy2(mod, run / "mods" / mod.name)
    shutil.copy2(server.STATE / "eula.txt", run / "eula.txt")
    toml = run / "config/vanadium.toml"
    toml.write_text(f"workers = {workers}\ncellSize = {cell}\n")
    (run / "config/c2me.toml").write_text(f"version = 3\nglobalExecutorParallelism = {args.c2me}\n")
    port, rcon_port = server.free_port(), server.free_port()
    while port == rcon_port:
        rcon_port = server.free_port()
    password = secrets.token_hex(24)
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nenable-rcon=true\n"
        f"rcon.port={rcon_port}\nrcon.password={password}\n"
        "online-mode=true\nlevel-seed=12345\nlevel-type=minecraft:flat\n"
        "view-distance=4\nsimulation-distance=4\nmax-players=1\n"
        "spawn-protection=0\nmax-tick-time=-1\npause-when-empty-seconds=-1\n"
    )
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = f"-Xms{args.heap} -Xmx{args.heap} -XX:+UseG1GC {args.jvm}"
    log = run / "console.log"
    print(f"== workers={workers} cell={cell} run={run}", flush=True)
    with log.open("w") as output:
        prefix = ["numactl", *args.numactl.split()] if args.numactl else []
        process = subprocess.Popen(
            [*prefix, str(ROOT / "gradlew"), "--no-daemon", "--no-configuration-cache", "--console=plain",
             "-Dorg.gradle.jvmargs=-Xms256m -Xmx1g", f"-PvanadiumRunDir={run}", "runServer", "--args=nogui"],
            cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            server.wait_for(process, log, "RCON running on", args.timeout)
            pid = find_server_pid(run)
            r = Rcon(rcon_port, password)
            print(r("vanadium status").replace("\n", " | "), flush=True)
            gamerule(r, ["spawn_mobs", "doMobSpawning"], "false")
            gamerule(r, ["advance_time", "doDaylightCycle"], "false")
            gamerule(r, ["advance_weather", "doWeatherCycle"], "false")
            r("time set midnight")
            r("weather clear")
            half = args.radius
            for x0 in range(-half, half, 16):
                for z0 in range(-half, half, 16):
                    r(f"forceload add {x0 * 16} {z0 * 16} {(x0 + 16) * 16 - 1} {(z0 + 16) * 16 - 1}")
            time.sleep(args.settle)
            extra = {"workers": workers, "cell": cell, "chunks": (2 * half) ** 2}
            for wl in args.workloads:
                if wl == "idle":
                    bench(r, log, pid, "idle", results, extra)
                elif wl == "mobs":
                    per = args.mobs_per_chunk
                    for cx in range(-half, half):
                        for cz in range(-half, half):
                            for i in range(per):
                                x = cx * 16 + 2 + (i % 2) * 8
                                z = cz * 16 + 2 + (i // 2) * 8
                                r(f"summon minecraft:zombie {x} -60 {z} {{PersistenceRequired:1b,Invulnerable:1b}}")
                    time.sleep(5)
                    print(r("execute if entity @e[type=zombie]"), flush=True)
                    bench(r, log, pid, "mobs", results, {**extra, "mobs": per * (2 * half) ** 2})
                    if args.off:
                        toml.write_text(f"enabled = false\nworkers = {workers}\ncellSize = {cell}\n")
                        r("vanadium reload")
                        bench(r, log, pid, "mobs-off", results, {**extra, "mobs": per * (2 * half) ** 2})
                        toml.write_text(f"workers = {workers}\ncellSize = {cell}\n")
                        r("vanadium reload")
                elif wl.startswith("cells:"):
                    for c in wl.split(":")[1].split(","):
                        toml.write_text(f"workers = {workers}\ncellSize = {c}\n")
                        r("vanadium reload")
                        bench(r, log, pid, f"mobs-cell{c}", results, {**extra, "cell": int(c)})
                elif wl.startswith("variants:"):
                    # variants:ROUNDS:name=toml lines separated by '|'; alternating order each round.
                    _, rounds, spec = wl.split(":", 2)
                    variants = [v.split("=", 1) for v in spec.split("|")]
                    for rnd in range(int(rounds)):
                        order = variants if rnd % 2 == 0 else list(reversed(variants))
                        for name, body in order:
                            toml.write_text(f"workers = {workers}\n" + body.replace(",", "\n") + "\n")
                            r("vanadium reload")
                            row, _ = bench(r, log, pid, f"v-{name}", results, {**extra, "variant": name, "round": rnd},
                                           profile_after=False)
                    toml.write_text(f"workers = {workers}\ncellSize = {cell}\n")
                    r("vanadium reload")
                elif wl == "jfr":
                    jfr = outdir / f"w{workers}-c{cell}.jfr"
                    subprocess.run(["jcmd", str(pid), "JFR.start", "settings=profile", "name=sweep",
                                    "jdk.JavaMonitorEnter#threshold=100us", "jdk.ThreadPark#threshold=100us",
                                    "jdk.ExecutionSample#period=5ms",
                                    f"filename={jfr}"], check=True, capture_output=True)
                    bench(r, log, pid, "mobs-jfr", results, extra)
                    subprocess.run(["jcmd", str(pid), "JFR.stop", "name=sweep"], check=True, capture_output=True)
            shutil.copy2(log, outdir / f"console-w{workers}-c{cell}.log")
            r("stop")
            process.wait(timeout=120)
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


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--workers", default="4,8,16,24,36,48,0")
    p.add_argument("--cell", type=int, default=2)
    p.add_argument("--radius", type=int, default=16, help="half-width in chunks of the forced square")
    p.add_argument("--mobs-per-chunk", type=int, default=4)
    p.add_argument("--workloads", default="idle,mobs")
    p.add_argument("--off", action="store_true")
    p.add_argument("--c2me", type=int, default=4)
    p.add_argument("--heap", default="4g")
    p.add_argument("--jvm", default="")
    p.add_argument("--numactl", default="", help="numactl options for the server process tree, e.g. '--cpunodebind=0 --preferred=0' (numactl must be on PATH)")
    p.add_argument("--settle", type=int, default=20)
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--out", required=True, help="directory for results.json, console logs and JFR files")
    args = p.parse_args()
    args.workloads = args.workloads.split(";") if ";" in args.workloads else args.workloads.split(",")
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)
    results = []
    for w in [int(x) for x in args.workers.split(",")]:
        try:
            run_point(args, w, args.cell, results, outdir)
        except Exception as e:  # keep sweeping; evidence remains in the run dir
            print(f"!! workers={w} failed: {e}", flush=True)
        (outdir / "results.json").write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
