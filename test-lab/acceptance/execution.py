"""Run bounded adapters and publish a strict, metadata-only acceptance report."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

from contract import ADAPTERS, ContractError, load_json, validate_result


def source_identity(repo: Path) -> dict:
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repo), *args])

    sha = git("rev-parse", "HEAD").decode().strip()
    patch = git("diff", "HEAD", "--binary")
    untracked = git("ls-files", "--others", "--exclude-standard", "-z")
    digest = hashlib.sha256(patch)
    for raw in sorted(part for part in untracked.split(b"\0") if part):
        file = repo / os.fsdecode(raw)
        digest.update(raw)
        if file.is_symlink():
            digest.update(os.fsencode(os.readlink(file)))
        elif file.is_file():
            digest.update(hashlib.sha256(file.read_bytes()).digest())
    return {
        "source_sha": sha,
        "source_dirty": bool(patch or untracked),
        "worktree_digest": digest.hexdigest(),
    }


def signal_group(pid: int, signum: int) -> None:
    try:
        os.killpg(pid, signum)
    except ProcessLookupError:
        return
    except PermissionError:
        # Darwin can return EPERM after the last group member has exited.
        groups = subprocess.check_output(["ps", "-axo", "pgid="], text=True).split()
        if str(pid) in groups:
            raise


def stop_process(process: subprocess.Popen, grace: float = 20) -> None:
    # Descendants can outlive a shell leader. Always stop the owned group.
    try:
        signal_group(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait()
        return
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        pass
    try:
        signal_group(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def execute(
    command: list[str], repo: Path, log: Path, timeout: int
) -> tuple[int, bool]:
    with log.open("wb") as output:
        process = subprocess.Popen(
            command,
            cwd=repo,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return process.wait(timeout=timeout), False
        except subprocess.TimeoutExpired:
            stop_process(process)
            return 124, True
        finally:
            stop_process(process)


def write_report(out: Path, report: dict) -> None:
    temporary = out / "report.json.tmp"
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(out / "report.json")
    results = list(report["results"])
    if report.get("source_unchanged") is False:
        results.append(
            {
                "scenario_id": "source-integrity",
                "tier": "provenance",
                "status": "failed",
                "message": "source changed during execution",
            }
        )
    suite = ET.Element(
        "testsuite",
        name="local-vpn-acceptance",
        tests=str(len(results)),
        failures=str(sum(row["status"] != "passed" for row in results)),
        skipped="0",
    )
    for row in results:
        case = ET.SubElement(
            suite,
            "testcase",
            name=row["scenario_id"],
            classname=row["tier"],
            time=str(row.get("duration_seconds", 0)),
        )
        if row["status"] != "passed":
            ET.SubElement(case, "failure", type=row["status"], message=row["message"])
    ET.ElementTree(suite).write(
        out / "report.xml", encoding="utf-8", xml_declaration=True
    )


def adapter_command(
    repo: Path, scenario: dict, run_id: str, out: Path, options: dict
) -> list[str]:
    import sys

    command = [
        sys.executable,
        str(repo / ADAPTERS[scenario["adapter"]]),
        "--scenario",
        scenario["id"],
        "--run-id",
        run_id,
        "--out-dir",
        str(out),
    ]
    allowed = {
        "android": [
            "serial",
            "prepared_dir",
            "xray_artifact_dir",
            "fixture_host",
            "fixture_control_port",
            "xray_control_port",
        ],
        "peers": ["cache_dir"],
        "vm": [],
    }
    for key in allowed[scenario["adapter"]]:
        if options.get(key) is not None:
            command.extend(["--" + key.replace("_", "-"), str(options[key])])
    return command


def run_suite(
    repo: Path,
    manifest: dict,
    selected: list[dict],
    run_id: str,
    out: Path,
    options: dict,
    executor=execute,
) -> dict:
    # Never reuse evidence directories, including empty ones and symlinks.
    out.mkdir(parents=True, exist_ok=False, mode=0o700)
    identity = source_identity(repo)
    manifest_hash = hashlib.sha256(
        json.dumps(manifest, sort_keys=True).encode()
    ).hexdigest()
    report = {
        "schema_version": 1,
        "run_id": run_id,
        **identity,
        "manifest_sha256": manifest_hash,
        "selected_scenarios": [row["id"] for row in selected],
        "started_at": datetime.now(timezone.utc).isoformat(),
        "host": {"system": platform.system(), "architecture": platform.machine()},
        "status": "failed",
        "results": [],
        "external_boundaries": manifest["external_boundaries"],
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    try:
        for scenario in selected:
            row = {
                "scenario_id": scenario["id"],
                "tier": scenario["tier"],
                "protocol": scenario["protocol"],
                "status": "blocked",
                "message": "not executed",
            }
            report["results"].append(row)
            start = time.monotonic()
            try:
                if platform.system().lower() not in scenario["platforms"] and not (
                    scenario["adapter"] == "vm" and options.get("vm")
                ):
                    row["message"] = "scenario requires another operating system"
                    continue
                if not (repo / ADAPTERS[scenario["adapter"]]).is_file():
                    row["message"] = "required adapter is missing"
                    continue
                target = out / scenario["id"]
                if scenario["adapter"] == "vm" and options.get("vm"):
                    from vm_transport import vm_command

                    command = vm_command(
                        options["vm"],
                        repo,
                        target,
                        scenario["id"],
                        run_id,
                        identity["source_sha"],
                    )
                else:
                    command = adapter_command(repo, scenario, run_id, target, options)
                if scenario["id"] == "android-xray" and options.get("vm"):
                    from routed_android import execute_routed

                    code, timed_out = execute_routed(
                        command,
                        repo,
                        out / (scenario["id"] + ".private.log"),
                        scenario["timeout_seconds"],
                        options,
                        executor,
                    )
                else:
                    try:
                        code, timed_out = executor(
                            command,
                            repo,
                            out / (scenario["id"] + ".private.log"),
                            scenario["timeout_seconds"],
                        )
                    except BaseException:
                        if scenario["adapter"] == "vm" and options.get("vm"):
                            from vm_transport import cancel_vm

                            row["remote_cleanup"] = cancel_vm(
                                options["vm"], repo, target, run_id
                            )
                        raise
                    if timed_out and scenario["adapter"] == "vm" and options.get("vm"):
                        from vm_transport import cancel_vm

                        row["remote_cleanup"] = cancel_vm(
                            options["vm"], repo, target, run_id
                        )
                if timed_out:
                    row.update(
                        status="failed",
                        message="adapter timeout; inspect private log and cleanup",
                    )
                    continue
                evidence = load_json(target / "result.json")
                verified = validate_result(
                    evidence, scenario, run_id, identity["source_sha"], target
                )
                if code != 0 and verified["status"] == "passed":
                    raise ContractError(
                        "adapter process failed despite passing evidence"
                    )
                row.update(verified)
                row["message"] = (
                    "verified"
                    if row["status"] == "passed"
                    else "adapter did not pass; inspect private result"
                )
                row["result_sha256"] = hashlib.sha256(
                    (target / "result.json").read_bytes()
                ).hexdigest()
            except (ContractError, OSError, subprocess.SubprocessError) as error:
                row.update(
                    status="failed",
                    message=str(error)
                    if isinstance(error, ContractError)
                    else type(error).__name__,
                )
            finally:
                row["duration_seconds"] = round(time.monotonic() - start, 3)
                write_report(out, report)
    except KeyboardInterrupt:
        report["interrupted"] = True
        if report["results"]:
            report["results"][-1].update(
                status="failed", message="execution interrupted"
            )
    finally:
        completed = {row["scenario_id"] for row in report["results"]}
        for scenario in selected:
            if scenario["id"] not in completed:
                report["results"].append(
                    {
                        "scenario_id": scenario["id"],
                        "tier": scenario["tier"],
                        "protocol": scenario["protocol"],
                        "status": "blocked",
                        "message": "not executed",
                    }
                )
        report["source_unchanged"] = source_identity(repo) == identity
        report["status"] = (
            "passed"
            if report["source_unchanged"]
            and all(row["status"] == "passed" for row in report["results"])
            else "failed"
        )
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        write_report(out, report)
    return report
