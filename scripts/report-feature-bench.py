#!/usr/bin/env python3
"""Summarize paired CSV timings and separate JFR recordings from bench-features.py runs."""

import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path
import statistics
import subprocess


def summarize(runs):
    samples = defaultdict(lambda: defaultdict(list))
    settings = None
    if len({run.resolve() for run in runs}) != len(runs):
        raise ValueError("Each JVM run must be supplied only once")
    for run in runs:
        metadata = json.loads((run / "benchmark-environment.json").read_text())
        comparable = {key: metadata[key] for key in (
            "commit", "workers", "cell_size", "c2me_workers", "heap", "gc", "scale",
            "rounds", "filter", "runtime_mods")}
        if settings is not None and comparable != settings:
            raise ValueError("Runs have different benchmark settings; summarize them separately")
        settings = comparable
        with (run / "feature-bench/samples.csv").open() as source:
            rounds = defaultdict(set)
            for row in csv.DictReader(source):
                identity = (row["case"], row["enabled"])
                index = int(row["round"])
                if index in rounds[identity]:
                    raise ValueError(f"Duplicate round in {run}: {identity}, {index}")
                rounds[identity].add(index)
                n = int(row["operations"])
                values = {key: int(row[key]) / n for key in (
                    "wall_ns", "caller_cpu_ns", "worker_cpu_ns", "allocated_bytes")}
                samples[row["case"]][row["enabled"]].append((run.name, int(row["round"]), values))
            if not rounds or any(indices != set(range(metadata["rounds"])) for indices in rounds.values()):
                raise ValueError(f"Incomplete timing samples in {run}")
    results = {}
    for name, arms in samples.items():
        medians = {arm: {key: statistics.median(row[2][key] for row in rows)
                         for key in rows[0][2]} for arm, rows in arms.items()}
        paired = {arm: {(run, index): values["wall_ns"] for run, index, values in rows}
                  for arm, rows in arms.items()}
        ratios = [paired["false"][pair] / paired["true"][pair] for pair in paired["false"]]
        by_fork = {}
        for fork in dict.fromkeys(row[0] for row in arms["false"]):
            times = {arm: statistics.median(row[2]["wall_ns"] for row in rows if row[0] == fork)
                     for arm, rows in arms.items()}
            by_fork[fork] = times["false"] / times["true"]
        results[name] = {"off": medians["false"], "on": medians["true"],
                         "paired_speedup_median": statistics.median(ratios),
                         "fork_speedups": by_fork, "samples_per_arm": len(ratios)}
    return results


def seconds(value):
    # JFR's JSON formatter uses ISO-8601 durations, e.g. PT0.003S.
    if not value:
        return 0
    return float(value.removeprefix("PT").removesuffix("S"))


def profile(path):
    result = subprocess.run(["jfr", "print", "--json", "--stack-depth", "20", "--events",
                             "jdk.ExecutionSample,jdk.JavaMonitorEnter,jdk.ThreadPark,jdk.ObjectAllocationSample",
                             str(path)], check=True, capture_output=True, text=True)
    execution = defaultdict(Counter)
    allocations = Counter()
    waits = defaultdict(Counter)
    wait_sites = defaultdict(Counter)
    for event in json.loads(result.stdout)["recording"]["events"]:
        values = event["values"]
        thread = values.get("sampledThread") or values.get("eventThread") or {}
        name = thread.get("javaName", "")
        role = "caller" if name == "Server thread" else "worker" if name.startswith("Vanadium-Worker-") else "other"
        if role == "other":
            continue
        frames = (values.get("stackTrace") or {}).get("frames", [])
        leaf = "unknown"
        if frames:
            method = frames[0]["method"]
            leaf = method["type"]["name"].replace("/", ".") + "." + method["name"]
        if event["type"] == "jdk.ExecutionSample":
            execution[role][leaf] += 1
        elif event["type"] == "jdk.ObjectAllocationSample":
            # Samples identify allocation sites. Weights can include the preceding disabled
            # interval when repeatedly starting JFR; use ThreadMXBean for bytes per operation.
            allocations[values["objectClass"]["name"]] += 1
        else:
            waits[role][event["type"]] += seconds(values["duration"])
            for frame in frames:
                method = frame["method"]
                owner = method["type"]["name"].replace("/", ".")
                if owner.startswith(("net.minecraft.", "com.synsenetwork.vanadium.")):
                    wait_sites[role][event["type"] + ": " + owner + "." + method["name"]] += seconds(values["duration"])
                    break
    return {"cpu_samples": {role: {"count": counter.total(), "top_frames": counter.most_common(6)}
                            for role, counter in execution.items()},
            "allocation_sample_counts": allocations.most_common(6), "wait_seconds": dict(waits),
            "wait_sites": {role: sites.most_common(6) for role, sites in wait_sites.items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--skip-jfr", action="store_true")
    args = parser.parse_args()
    results = summarize(args.runs)
    profiles = {} if args.skip_jfr else {
        f"{run.name}/{path.stem}": profile(path)
        for run in args.runs for path in sorted((run / "feature-bench").glob("*.jfr"))
    }
    args.output.write_text(json.dumps({"results": results, "profiles": profiles}, indent=2) + "\n")
    print("| Workload | Off µs | On µs | Paired speedup | Fork range | Caller CPU off/on µs | Allocation off/on KiB |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for name, data in results.items():
        off, on = data["off"], data["on"]
        forks = data["fork_speedups"].values()
        print(f"| {name} | {off['wall_ns'] / 1e3:.2f} | {on['wall_ns'] / 1e3:.2f} | "
              f"{data['paired_speedup_median']:.2f}× | {min(forks):.2f}–{max(forks):.2f}× | "
              f"{off['caller_cpu_ns'] / 1e3:.2f} / {on['caller_cpu_ns'] / 1e3:.2f} | "
              f"{off['allocated_bytes'] / 1024:.1f} / {on['allocated_bytes'] / 1024:.1f} |")


if __name__ == "__main__":
    main()
