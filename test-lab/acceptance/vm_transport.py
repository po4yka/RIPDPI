"""Map an isolated checkout and evidence directory into a running Lima VM."""

from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
import re
import subprocess

from contract import ContractError, require


def vm_mappings(
    name: str, repo: Path, output: Path, inventory: str | None = None
) -> tuple[str, str]:
    require(
        bool(re.fullmatch(r"ripdpi-acceptance-[a-z0-9-]+", name)),
        "VM must have a task-owned acceptance name",
    )
    if inventory is None:
        inventory = subprocess.check_output(
            ["limactl", "list", "--json"], text=True, timeout=15
        )
    try:
        machines = [json.loads(line) for line in inventory.splitlines() if line.strip()]
    except json.JSONDecodeError as error:
        raise ContractError("invalid Lima inventory") from error
    matching = [vm for vm in machines if vm.get("name") == name]
    require(len(matching) == 1, "acceptance VM not found")
    vm = matching[0]
    require(vm.get("status") == "Running", "acceptance VM is not running")
    mounts = vm.get("config", {}).get("mounts", [])
    source = None
    target = None
    for mount in mounts:
        host = Path(mount["location"]).expanduser().resolve()
        guest = PurePosixPath(mount.get("mountPoint", mount["location"]))
        if host == repo.resolve():
            require(mount.get("writable") is False, "VM source mount must be read-only")
            source = str(guest)
        if output.resolve().is_relative_to(host) and mount.get("writable") is True:
            target = str(guest / output.resolve().relative_to(host).as_posix())
    require(source is not None, "VM must mount this exact checkout read-only")
    require(target is not None, "VM must mount the run output directory writable")
    return source, target


def vm_command(
    name: str, repo: Path, output: Path, scenario: str, run_id: str, sha: str
) -> list[str]:
    source, target = vm_mappings(name, repo, output)
    return [
        "limactl",
        "shell",
        "--workdir=" + source,
        name,
        "sudo",
        "-n",
        "python3",
        source + "/test-lab/acceptance/vm/run.py",
        "--scenario",
        scenario,
        "--run-id",
        run_id,
        "--out-dir",
        target,
        "--source-sha",
        sha,
    ]


def cancel_vm(name: str, repo: Path, output: Path, run_id: str) -> bool:
    """SSH termination does not establish remote cleanup; cancel the owned runner."""
    try:
        source, target = vm_mappings(name, repo, output)
        result = subprocess.run(
            [
                "limactl",
                "shell",
                "--workdir=" + source,
                name,
                "sudo",
                "-n",
                "python3",
                source + "/test-lab/acceptance/vm/cancel.py",
                "--run-id",
                run_id,
                "--out-dir",
                target,
            ],
            capture_output=True,
            timeout=45,
            check=False,
        )
        output.with_suffix(".cancel.private.log").write_bytes(
            result.stdout + result.stderr
        )
        return result.returncode == 0
    except (ContractError, OSError, subprocess.SubprocessError) as error:
        output.with_suffix(".cancel.private.log").write_text(str(error))
        return False
