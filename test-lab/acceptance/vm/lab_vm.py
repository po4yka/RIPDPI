#!/usr/bin/env python3
"""Manage only a VM created by this run; never select an existing user VM."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
MARKER = "mounts: [] # ACCEPTANCE_MOUNTS"


def command(*args: str, capture: bool = False) -> str:
    result = subprocess.run(args, check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def validate_name(name: str) -> str:
    if not re.fullmatch(r"ripdpi-acceptance-[a-z0-9][a-z0-9-]{0,40}", name):
        raise ValueError("VM name must start with ripdpi-acceptance- and use safe lowercase characters")
    return name


def render(template: str, workspace: Path, output: Path) -> str:
    if MARKER not in template:
        raise ValueError("VM mount marker is missing")
    if workspace == output or workspace in output.parents or output in workspace.parents:
        raise ValueError("VM output must be outside the workspace to avoid overlapping mounts")
    mounts = [{"location": str(workspace), "mountPoint": "/srv/ripdpi", "writable": False},
              {"location": str(output), "mountPoint": "/srv/acceptance-output", "writable": True}]
    return template.replace(MARKER, "mounts: " + json.dumps(mounts))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["create", "start", "stop", "doctor", "exec"])
    parser.add_argument("--name", required=True)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--workspace", type=Path, default=ROOT)
    args, extra = parser.parse_known_args()
    if extra and args.action != "exec":
        parser.error("unexpected arguments: " + " ".join(extra))
    validate_name(args.name)
    output = args.out_dir.resolve()
    state_path = output / "vm-owner.json"
    if args.action == "create":
        if state_path.exists():
            raise ValueError("VM ownership file already exists; use start or a new output directory")
        listed = command("limactl", "list", "--json", capture=True)
        if any(json.loads(line)["name"] == args.name for line in listed.splitlines() if line):
            raise ValueError("VM already exists; refusing to adopt it")
        workspace = args.workspace.resolve(strict=True)
        if command("git", "-C", str(workspace), "branch", "--show-current", capture=True).strip() == "main":
            raise ValueError("Use an isolated job worktree, not main")
        output.mkdir(parents=True, exist_ok=True)
        config = render((ROOT / "test-lab/lima/ripdpi-acceptance.yaml").read_text(), workspace, output)
        config_path = output / "lima.yaml"
        config_path.write_text(config)
        command("limactl", "create", "--tty=false", "--name=" + args.name, str(config_path))
        state_path.write_text(json.dumps({"name": args.name, "workspace": str(workspace),
                                         "config_sha256": hashlib.sha256(config.encode()).hexdigest()}, indent=2) + "\n")
        return 0
    state = json.loads(state_path.read_text())
    if state.get("name") != args.name:
        raise ValueError("VM name does not match this run's ownership record")
    if args.action in ("start", "stop"):
        command("limactl", args.action, "--tty=false", args.name)
    elif args.action == "exec":
        cmd = extra[1:] if extra[:1] == ["--"] else extra
        if not cmd:
            raise ValueError("exec requires a command after --")
        command("limactl", "shell", "--workdir=/srv/ripdpi", args.name, *cmd)
    else:
        addr = json.loads(command("limactl", "shell", args.name, "ip", "-j", "-4", "addr", "show", "dev", "acceptance0", capture=True))
        ips = [a["local"] for i in addr for a in i.get("addr_info", []) if a["scope"] == "global"]
        if len(ips) != 1:
            raise ValueError("Expected exactly one VM ingress IPv4")
        version = command("limactl", "shell", args.name, "uname", "-r", capture=True).strip()
        print(json.dumps({"name": args.name, "ingress_ipv4": ips[0], "kernel": version,
                          "workspace": "/srv/ripdpi", "output": "/srv/acceptance-output",
                          "packet_fidelity": "Linux client namespace only; Android crosses emulator NAT"}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
