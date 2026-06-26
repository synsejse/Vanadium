#!/usr/bin/env bash
# Headless MSPT harness: boots the dev server, injects load over RCON, samples the tick profiler.
# Usage: scripts/bench-server.sh [seconds]
set -euo pipefail
cd "$(dirname "$0")/.."

RUN=run
SECS="${1:-30}"
mkdir -p "$RUN"
echo "eula=true" > "$RUN/eula.txt"

# server.properties: enable RCON + a big simulation distance for real tick load
cat > "$RUN/server.properties" <<EOF
enable-rcon=true
rcon.port=25575
rcon.password=vanadiumbench
simulation-distance=16
view-distance=16
online-mode=false
EOF

echo ">> starting server (./gradlew runServer) in background..."
./gradlew runServer --console=plain > "$RUN/bench-server.log" 2>&1 &
GRADLE_PID=$!

# wait for "Done" in the log (server ready)
echo ">> waiting for server to finish loading..."
for _ in $(seq 1 120); do
  grep -q 'Done (' "$RUN/bench-server.log" && break
  sleep 1
done

rcon() { # tiny RCON via python (no extra deps); prints server reply
  python3 - "$@" <<'PY'
import socket,struct,sys
HOST,PORT,PW=("127.0.0.1",25575,"vanadiumbench")
def pkt(i,t,b): body=b.encode()+b"\x00\x00"; return struct.pack("<iii",len(body)+8,i,t)+body
s=socket.create_connection((HOST,PORT)); s.sendall(pkt(1,3,PW)); s.recv(4096)
s.sendall(pkt(2,2," ".join(sys.argv[1:])));
import time; time.sleep(0.1); print(s.recv(8192)[12:-2].decode(errors="replace"))
PY
}

echo ">> injecting load..."
rcon "forceload add -128 -128 128 128" || true
for _ in $(seq 1 200); do rcon "summon minecraft:zombie ~ ~ ~" >/dev/null 2>&1 || true; done

echo ">> sampling tick profiler for ${SECS}s..."
rcon "debug start"
sleep "$SECS"
rcon "debug stop"

echo ">> stopping server..."
rcon "stop" || true
wait "$GRADLE_PID" 2>/dev/null || true
echo ">> done. Profiler report is under $RUN/debug/ ; MSPT summary in $RUN/bench-server.log"
