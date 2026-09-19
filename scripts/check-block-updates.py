#!/usr/bin/env python3
"""Run block-update and dirty-chunk regression checks in an isolated Fabric server."""

import importlib.util
from pathlib import Path
import subprocess
from types import SimpleNamespace


def main():
    root = Path(__file__).resolve().parent.parent
    subprocess.run([str(root / "gradlew"), "blockUpdateChecksJar"], cwd=root, check=True)
    spec = importlib.util.spec_from_file_location("dev_server", root / "scripts/dev-server.py")
    server = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(server)

    prepare = server.prepare
    server.prepare = lambda: [*prepare(), root / "build/test-mods/block-update-checks.jar"]
    wait_for = server.wait_for

    def wait_for_checks(process, log, marker, timeout):
        wait_for(process, log, marker, timeout)
        wait_for(process, log, "BLOCK_AND_DIRTY_CHECKS_PASSED", timeout)

    server.wait_for = wait_for_checks
    server.exercise(SimpleNamespace(mode="smoke", timeout=300, seconds=3))


if __name__ == "__main__":
    main()
