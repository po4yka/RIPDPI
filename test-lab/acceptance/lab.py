#!/usr/bin/env python3
"""Entry point for strict local VPN acceptance. No external service is required."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import uuid

from contract import (
    ADAPTERS,
    ContractError,
    IDENTIFIER,
    load_json,
    require,
    validate_manifest,
)
from execution import run_suite

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("manifest.json")


def select(manifest: dict, scenarios: list[str] | None, profile: str) -> list[dict]:
    selected = scenarios or manifest["profiles"].get(profile)
    require(bool(selected), "unknown or empty profile")
    require(len(selected) == len(set(selected)), "duplicate selected scenario")
    indexed = {row["id"]: row for row in manifest["scenarios"]}
    require(set(selected) <= indexed.keys(), "unknown selected scenario")
    return [indexed[identity] for identity in selected]


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument(
        "command", choices=["validate", "list", "doctor", "prepare", "run", "verify"]
    )
    cli.add_argument("--manifest", type=Path, default=MANIFEST)
    cli.add_argument("--profile", default="core")
    cli.add_argument("--scenario", action="append")
    cli.add_argument("--run-id")
    cli.add_argument("--out-dir", type=Path)
    cli.add_argument("--serial")
    cli.add_argument(
        "--min-free-gib",
        type=int,
        default=10,
        help="Minimum free space before dependency preparation",
    )
    cli.add_argument(
        "--vm-address", help="Reachable private IPv4 for routed Xray Android acceptance"
    )
    cli.add_argument("--xray-artifact-dir", type=Path)
    cli.add_argument("--vm", help="Task-owned Lima VM for Linux routed scenarios")
    cli.add_argument("--prepared-dir", type=Path)
    cli.add_argument("--cache-dir", type=Path)
    cli.add_argument("--fixture-host")
    cli.add_argument("--fixture-control-port", type=int)
    cli.add_argument("--xray-control-port", type=int)
    return cli


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "verify":
            from verify_report import verify

            require(args.out_dir is not None, "--out-dir is required")
            report = verify(args.out_dir.resolve())
            print(
                f"Verified {len(report['results'])} recorded scenarios for {report['source_sha']}"
            )
            return 0
        manifest = validate_manifest(load_json(args.manifest))
        if args.command == "validate":
            missing = [
                name for name, path in ADAPTERS.items() if not (ROOT / path).is_file()
            ]
            require(not missing, "missing adapters: " + ", ".join(missing))
            print(
                f"Validated {len(manifest['scenarios'])} scenarios and {len(manifest['profiles'])} profiles"
            )
            return 0
        scenarios = select(manifest, args.scenario, args.profile)
        if args.command == "list":
            print(json.dumps(scenarios, indent=2))
            return 0
        if args.command == "doctor":
            tools = {"git"}
            for scenario in scenarios:
                tools.update(
                    {
                        "android": {"adb", "java", "go", "cargo"},
                        "peers": {"cargo", "go", "openssl"},
                        "vm": {"limactl"} if args.vm else {"ip", "nft", "tc"},
                    }[scenario["adapter"]]
                )
            checks = {name: shutil.which(name) is not None for name in sorted(tools)}
            supported = all(
                platform.system().lower() in row["platforms"]
                or (row["adapter"] == "vm" and bool(args.vm))
                for row in scenarios
            )
            print(
                json.dumps(
                    {
                        "tools": checks,
                        "checkout_free_gib": round(
                            shutil.disk_usage(ROOT).free / 2**30, 1
                        ),
                        "preparation_min_free_gib": args.min_free_gib,
                        "platform_supported": supported,
                        "note": "Tool presence does not prove runtime acceptance.",
                    },
                    indent=2,
                )
            )
            return 0 if supported and all(checks.values()) else 2
        run_id = args.run_id or ("run-" + uuid.uuid4().hex[:16])
        require(bool(IDENTIFIER.fullmatch(run_id)), "invalid run id")
        require(args.out_dir is not None, "--out-dir is required")
        out = args.out_dir.resolve()
        if out.is_relative_to(ROOT):
            ignored = (
                subprocess.run(
                    ["git", "check-ignore", "-q", str(out)], cwd=ROOT
                ).returncode
                == 0
            )
            require(
                ignored,
                "output inside the checkout must be ignored by Git (use build/acceptance/)",
            )
        if args.command == "prepare":
            require(args.min_free_gib >= 1, "--min-free-gib must be positive")
            roots = [ROOT, Path.home(), args.cache_dir or Path.home()]
            for root in roots:
                while not root.exists():
                    root = root.parent
                require(
                    shutil.disk_usage(root).free >= args.min_free_gib * 2**30,
                    f"preparation requires {args.min_free_gib} GiB free on {root}; serialize large builds and free task-owned outputs",
                )
            out.mkdir(parents=True, exist_ok=False, mode=0o700)
            for adapter in sorted({row["adapter"] for row in scenarios}):
                if adapter == "vm":
                    raise ContractError(
                        "Prepare the Linux VM with vm/lab_vm.py before routed scenarios"
                    )
                if adapter == "android":
                    command = [
                        sys.executable,
                        str(ROOT / ADAPTERS[adapter]),
                        "--prepare",
                        "--out-dir",
                        str(out / "android"),
                    ]
                    require(
                        bool(args.serial),
                        "--serial is required for Android preparation",
                    )
                    command += ["--run-id", run_id, "--serial", args.serial]
                    if args.xray_artifact_dir:
                        command += [
                            "--xray-artifact-dir",
                            str(args.xray_artifact_dir.resolve()),
                        ]
                    subprocess.run(command, cwd=ROOT, check=True)
                else:
                    for scenario in [
                        row for row in scenarios if row["adapter"] == adapter
                    ]:
                        command = [
                            sys.executable,
                            str(ROOT / ADAPTERS[adapter]),
                            "--prepare",
                            "--scenario",
                            scenario["id"],
                            "--run-id",
                            run_id,
                            "--out-dir",
                            str(out / scenario["id"]),
                        ]
                        if args.cache_dir:
                            command += ["--cache-dir", str(args.cache_dir.resolve())]
                        subprocess.run(command, cwd=ROOT, check=True)
            return 0
        options = {
            key: str(value.resolve()) if isinstance(value, Path) else value
            for key, value in vars(args).items()
        }
        report = run_suite(ROOT, manifest, scenarios, run_id, out, options)
        for row in report["results"]:
            print(f"{row['status']}: {row['scenario_id']} ({row['tier']})")
        print(f"Report: {out / 'report.json'}")
        return 0 if report["status"] == "passed" else 1
    except (ContractError, OSError, subprocess.SubprocessError) as error:
        print(f"acceptance: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
