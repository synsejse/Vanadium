#!/usr/bin/env python3
"""Summarize Vanadium's JFR events: how much tick work runs in parallel sections and what stays serial.

Usage: python3 scripts/jfr-summary.py recording.jfr [--json]
Needs the JDK's `jfr` tool on PATH (the Nix shell provides it). Events are recorded by default in any
JFR recording of a server running Vanadium: vanadium.ServerTick, vanadium.Wave, vanadium.Preparation.
"""

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone

EVENTS = "vanadium.ServerTick,vanadium.Wave,vanadium.Preparation"
DURATION = re.compile(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?")


def nanos(duration):
    match = DURATION.fullmatch(duration)
    if not match:
        raise ValueError(f"unexpected duration {duration!r}")
    hours, minutes, seconds = match.groups()
    whole, _, fraction = (seconds or "0").partition(".")
    return ((int(hours or 0) * 60 + int(minutes or 0)) * 60 + int(whole)) * 1_000_000_000 + int((fraction + "000000000")[:9])


def instant(text):
    """ISO instant with up to nanosecond precision -> epoch nanoseconds."""
    base, _, rest = text.partition(".")
    fraction = re.match(r"\d*", rest).group(0) if rest else ""
    offset = rest[len(fraction):] if rest else base[19:]
    moment = datetime.fromisoformat(base[:19] + (offset.replace("Z", "+00:00") or "+00:00"))
    return int(moment.astimezone(timezone.utc).timestamp()) * 1_000_000_000 + int((fraction + "000000000")[:9])


def percentile(values, percent):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[max(0, -(-len(ordered) * percent // 100) - 1)]


def load(path):
    output = subprocess.run(["jfr", "print", "--json", "--events", EVENTS, path],
                            check=True, capture_output=True, text=True).stdout
    return json.loads(output)["recording"]["events"]


def summarize(events):
    ticks, sections = [], []
    stages = defaultdict(lambda: {"count": 0, "nanos": 0, "cells": 0, "tasks": 0, "participants": 0})
    preparations = defaultdict(lambda: {"count": 0, "nanos": 0, "jobs": 0})
    for event in events:
        values = event["values"]
        start, duration = instant(values["startTime"]), nanos(values["duration"])
        if event["type"] == "vanadium.ServerTick":
            ticks.append((start, duration))
            continue
        sections.append((start, duration))
        if event["type"] == "vanadium.Wave":
            row = stages[values["stage"]]
            for key in ("cells", "tasks", "participants"):
                row[key] += values[key]
        else:
            row = preparations[values["kind"]]
            row["jobs"] += values["jobs"]
        row["count"] += 1
        row["nanos"] += duration

    # Attribute parallel sections to the tick that contains their start; JFR timestamps share a clock.
    ticks.sort()
    sections.sort()
    serial_per_tick, index = [], 0
    for start, duration in ticks:
        while index < len(sections) and sections[index][0] < start:
            index += 1
        parallel = 0
        while index < len(sections) and sections[index][0] <= start + duration:
            parallel += sections[index][1]
            index += 1
        serial_per_tick.append(max(0, duration - parallel))

    tick_total = sum(duration for _, duration in ticks)
    wave_total = sum(row["nanos"] for row in stages.values())
    preparation_total = sum(row["nanos"] for row in preparations.values())
    share = (lambda value: value / tick_total) if tick_total else (lambda value: 0.0)
    return {
        "ticks": {"count": len(ticks), "totalMs": tick_total / 1e6,
                  "p50Ms": percentile([d for _, d in ticks], 50) / 1e6,
                  "p95Ms": percentile([d for _, d in ticks], 95) / 1e6},
        "split": {"stageWaves": share(wave_total), "preparation": share(preparation_total),
                  "serial": share(max(0, tick_total - wave_total - preparation_total))},
        "serialPerTick": {"p50Ms": percentile(serial_per_tick, 50) / 1e6,
                          "p95Ms": percentile(serial_per_tick, 95) / 1e6},
        "stages": {stage: {"waves": row["count"], "totalMs": row["nanos"] / 1e6,
                           "avgCells": row["cells"] / row["count"], "avgTasks": row["tasks"] / row["count"],
                           "avgParticipants": row["participants"] / row["count"]}
                   for stage, row in sorted(stages.items(), key=lambda item: -item[1]["nanos"])},
        "preparations": {kind: {"runs": row["count"], "totalMs": row["nanos"] / 1e6, "avgJobs": row["jobs"] / row["count"]}
                         for kind, row in sorted(preparations.items(), key=lambda item: -item[1]["nanos"])},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recording")
    parser.add_argument("--json", action="store_true", help="print the summary as JSON")
    args = parser.parse_args()
    summary = summarize(load(args.recording))
    if args.json:
        json.dump(summary, sys.stdout, indent=2)
        print()
        return
    ticks, split = summary["ticks"], summary["split"]
    if not ticks["count"]:
        sys.exit("no vanadium.ServerTick events: was Vanadium running while the recording was taken?")
    print(f"{ticks['count']} ticks, {ticks['totalMs']:.0f} ms of tick work (p50 {ticks['p50Ms']:.2f} ms, p95 {ticks['p95Ms']:.2f} ms)")
    print(f"stage waves {split['stageWaves']:.1%}, preparation {split['preparation']:.1%}, serial {split['serial']:.1%}"
          f" (serial per tick p50 {summary['serialPerTick']['p50Ms']:.2f} ms, p95 {summary['serialPerTick']['p95Ms']:.2f} ms)")
    print("\nstage            waves   total ms  share  avg cells  avg tasks  avg participants")
    for stage, row in summary["stages"].items():
        print(f"{stage:<15}{row['waves']:>7}{row['totalMs']:>11.1f}{row['totalMs'] / ticks['totalMs']:>7.1%}"
              f"{row['avgCells']:>11.1f}{row['avgTasks']:>11.1f}{row['avgParticipants']:>18.1f}")
    if summary["preparations"]:
        print("\npreparation      runs   total ms  share  avg jobs")
        for kind, row in summary["preparations"].items():
            print(f"{kind:<15}{row['runs']:>6}{row['totalMs']:>11.1f}{row['totalMs'] / ticks['totalMs']:>7.1%}{row['avgJobs']:>10.1f}")


if __name__ == "__main__":
    main()
