#!/usr/bin/env python3
"""Materialize the private runner config for the emulator evidence lane.

The runner refuses a config that is group- or world-readable, and refuses hooks
that are symlinks, non-executable, or share an inode between the two vantages.
It also demands four distinct 256-bit identifiers. Producing that by hand on
every runner is exactly the step that never happened for the physical lane --
per the 2026-07-17 audit there was no `/etc/ripdpi/network-evidence-runner.json`
at all -- so it is generated here instead.

The two collector copies are byte-identical on purpose: the runner distinguishes
the vantages by inode, and the collector derives its role from the ready-marker
name. Deploying one reviewed file twice keeps the allowlist honest.

Identifiers are freshly random per invocation. They are redaction salts, not
secrets to be reused: a stable value across runs would let published manifests
be correlated with each other.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COLLECTOR = ROOT / "test-lab/scripts/network-evidence-emulator-collector.py"
WORKLOAD = ROOT / "test-lab/scripts/network-evidence-emulator-workload.py"
CONFIG_VERSION = "ripdpi_network_evidence_runner_v1"


def deploy(source: Path, destination: Path) -> Path:
    if not source.is_file():
        raise SystemExit(f"missing producer source: {source}")
    shutil.copyfile(source, destination)
    # Executable and read-only: the runner re-reads and re-digests the file, so
    # it must not be writable between the digest check and execution.
    destination.chmod(0o500)
    return destination


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--directory",
        type=Path,
        required=True,
        help="private directory to hold the config and the deployed hooks",
    )
    options = parser.parse_args(argv)

    directory: Path = options.directory
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)

    client_hook = deploy(COLLECTOR, directory / "client-collector.py")
    observer_hook = deploy(COLLECTOR, directory / "observer-collector.py")
    workload_hook = deploy(WORKLOAD, directory / "workload.py")
    if client_hook.stat().st_ino == observer_hook.stat().st_ino:
        raise SystemExit("collector copies must not share an inode")

    config = {
        "clientHook": str(client_hook.resolve()),
        "clientNetworkId": secrets.token_hex(32),
        "clientVantageId": secrets.token_hex(32),
        "observerHook": str(observer_hook.resolve()),
        "observerNetworkId": secrets.token_hex(32),
        "observerVantageId": secrets.token_hex(32),
        "version": CONFIG_VERSION,
        "workloadHook": str(workload_hook.resolve()),
    }
    config_path = directory / "network-evidence-runner.json"
    descriptor = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(config, handle, indent=2, sort_keys=True)
        handle.write("\n")
    config_path.chmod(0o600)
    print(config_path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
