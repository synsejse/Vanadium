#!/usr/bin/env python3
"""Prepare pinned runtime mods, or exercise a fresh loopback-only development server."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / ".vanadium"


def prepare():
    mods = STATE / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    paths = []
    for mod in json.loads((ROOT / "scripts/runtime-mods.json").read_text()):
        path = mods / mod["filename"]
        if not path.exists():
            print(f"Downloading {path.name}", flush=True)
            request = urllib.request.Request(mod["url"], headers={"User-Agent": "Vanadium-dev-setup"})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
            if hashlib.sha512(data).hexdigest() != mod["sha512"]:
                raise RuntimeError(f"Checksum mismatch: {path.name}")
            path.write_bytes(data)
        if hashlib.sha512(path.read_bytes()).hexdigest() != mod["sha512"]:
            raise RuntimeError(f"Checksum mismatch: remove {path} and run prepare again")
        paths.append(path)
    return paths


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def receive(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise RuntimeError("RCON closed the connection")
        data += chunk
    return data


def packet(sock, request_id, kind, text):
    body = struct.pack("<ii", request_id, kind) + text.encode() + b"\0\0"
    sock.sendall(struct.pack("<i", len(body)) + body)
    length = struct.unpack("<i", receive(sock, 4))[0]
    if not 10 <= length <= 4 * 1024 * 1024:
        raise RuntimeError(f"Invalid RCON packet length: {length}")
    reply = receive(sock, length)
    reply_id, reply_kind = struct.unpack("<ii", reply[:8])
    if reply_id != request_id:
        raise RuntimeError("RCON authentication/request failed")
    return reply_kind, reply[8:-2].decode(errors="replace")


def rcon(port, password, command, expect=None):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        kind, _ = packet(sock, 1, 3, password)
        if kind != 2:
            raise RuntimeError("Unexpected RCON authentication response")
        _, reply = packet(sock, 2, 2, command)
        if expect is not None and expect not in reply:
            raise RuntimeError(f"Command {command!r} failed: {reply}")
        return reply


def wait_for(process, log, marker, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Server exited with code {process.returncode}; see {log}")
        if marker in log.read_text(errors="replace"):
            return
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {marker!r}; see {log}")


def exercise(args):
    eula = STATE / "eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text().splitlines():
        raise RuntimeError(
            "Read https://aka.ms/MinecraftEULA, then if you agree, create "
            ".vanadium/eula.txt containing eula=true (or copy your existing accepted eula.txt)."
        )
    mods = prepare()
    runs = STATE / "runs"
    runs.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix=f"{args.mode}-", dir=runs))
    (run / "mods").mkdir()
    for mod in mods:
        shutil.copy2(mod, run / "mods" / mod.name)
    shutil.copy2(eula, run / "eula.txt")
    port, rcon_port = free_port(), free_port()
    while port == rcon_port:
        rcon_port = free_port()
    password = secrets.token_hex(24)
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nenable-rcon=true\n"
        f"rcon.port={rcon_port}\nrcon.password={password}\n"
        "online-mode=true\nlevel-seed=12345\nview-distance=4\nsimulation-distance=4\n"
        "max-players=1\nspawn-protection=0\n"
    )
    log = run / "console.log"
    print(f"Run directory: {run}", flush=True)
    # A new session lets cleanup target only this invocation and its child JVMs.
    with log.open("w") as output:
        process = subprocess.Popen(
            [str(ROOT / "gradlew"), "--no-daemon", "--no-configuration-cache",
             "--console=plain", f"-PvanadiumRunDir={run}", "runServer", "--args=nogui"],
            cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
        )
        try:
            wait_for(process, log, "RCON running on", args.timeout)
            status = rcon(rcon_port, password, "vanadium status", expect="workers:")
            print(status, flush=True)
            if "vanadium" not in status.lower():
                raise RuntimeError("Vanadium status command was not registered")
            print(rcon(rcon_port, password, "forceload add -32 -32 31 31",
                       expect="Marked 16 chunks"), flush=True)
            print(rcon(rcon_port, password, "setblock 0 100 0 minecraft:stone",
                       expect="Changed the block"), flush=True)
            if args.mode == "bench":
                rcon(rcon_port, password, "fill 0 100 0 15 100 15 minecraft:stone",
                     expect="filled")
                for i in range(200):
                    rcon(rcon_port, password,
                         f'summon minecraft:zombie {i % 16} 101 {i // 16} '
                         '{PersistenceRequired:1b,Invulnerable:1b}', expect="Summoned new")
                print(rcon(rcon_port, password, "debug start", expect="Started tick profiling"), flush=True)
            time.sleep(args.seconds)
            if args.mode == "bench":
                print(rcon(rcon_port, password, "debug stop", expect="Stopped tick profiling"), flush=True)
            print(rcon(rcon_port, password, "save-all flush", expect="Saved the game"), flush=True)
            rcon(rcon_port, password, "stop")
            if process.wait(timeout=60) != 0:
                raise RuntimeError(f"Server/Gradle failed; see {log}")
            contents = log.read_text(errors="replace")
            if "Vanadium Initialized" not in contents or "Saving chunks" not in contents:
                raise RuntimeError(f"Missing initialization/save evidence; see {log}")
            if " ERROR]" in contents or "Exception in server tick loop" in contents:
                raise RuntimeError(f"Server logged errors; see {log}")
            print(f"{args.mode} passed. Logs, world and profiles retained in {run}")
        finally:
            # Also clean up a child left behind after Gradle exits unexpectedly.
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "smoke", "bench"])
    parser.add_argument("--seconds", type=positive_int, default=30, help="time to tick/sample (default: 30)")
    parser.add_argument("--timeout", type=positive_int, default=300, help="startup timeout (default: 300)")
    args = parser.parse_args()
    try:
        if args.mode == "prepare":
            for path in prepare():
                print(path)
        else:
            exercise(args)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"{error}\n")
    except KeyboardInterrupt:
        parser.exit(130, "Interrupted; server processes stopped.\n")


if __name__ == "__main__":
    main()
