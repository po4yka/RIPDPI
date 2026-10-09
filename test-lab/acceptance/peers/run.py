#!/usr/bin/env python3
"""Run one exact native acceptance scenario and write strict, run-bound evidence."""

import argparse
from contextlib import contextmanager, redirect_stdout, redirect_stderr
import json
import os
from pathlib import Path
import re
import runpy
import signal
import subprocess
import sys
import traceback
import time

from catalog import SCENARIOS, UPSTREAM, EXTERNAL_BOUNDARIES
import hysteria
import go_toolchain

ROOT = Path(__file__).resolve().parents[3]
RUST = ROOT / "native/rust"


class ProcessLedger:
    """Track subprocesses from this adapter and reused runners, including private sessions."""

    def __init__(self):
        self.children = []
        self.original = subprocess.Popen
        self.verified = False

    def __enter__(self):
        ledger = self

        class OwnedProcess(self.original):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                ledger.children.append((self, kwargs.get("start_new_session", False)))

        subprocess.Popen = OwnedProcess
        return self

    def __exit__(self, *_):
        subprocess.Popen = self.original
        for child, group in reversed(self.children):
            if group and self.group_exists(child.pid):
                hysteria.stop_group(child)
            elif child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=3)
        deadline = time.monotonic() + 2
        while any(
            group and self.group_exists(child.pid) for child, group in self.children
        ):
            if time.monotonic() >= deadline:
                raise RuntimeError("owned process group did not stop")
            time.sleep(0.05)
        if any(child.poll() is None for child, _ in self.children):
            raise RuntimeError("owned process did not stop")
        self.verified = True

    @staticmethod
    def group_exists(pid):
        try:
            os.killpg(pid, 0)
            return True
        except ProcessLookupError:
            return False


def checked(command, cwd=ROOT, env=None, timeout=900):
    with subprocess.Popen(command, cwd=cwd, env=env, start_new_session=True) as process:
        try:
            code = process.wait(timeout=timeout)
            if code:
                raise subprocess.CalledProcessError(code, command)
        finally:
            hysteria.stop_group(process)


def cargo_command(case, prepare=False):
    command = [
        "bash",
        str(ROOT / "scripts/ci/cargo-guarded.sh"),
        "cargo",
        "test",
        "--locked",
        "--jobs",
        "2",
        "-p",
        case["package"],
    ]
    if case["target"] == "lib":
        command += ["--lib"]
    elif case["target"] == "bin":
        command += ["--bin", case["package"]]
    else:
        command += ["--test", case["target"]]
    command += [case["test"]]
    if prepare:
        return command + ["--no-run"]
    return (
        command
        + ["--", "--exact", "--nocapture"]
        + (["--ignored"] if case.get("ignored") else [])
    )


def exact_success(output, name):
    """Exit zero is insufficient: a misspelled filter runs zero tests."""
    marker = re.compile(r"^test " + re.escape(name) + r" \.\.\. ok$", re.MULTILINE)
    summary = re.compile(
        r"test result: ok\. 1 passed; 0 failed; 0 ignored; 0 measured;"
    )
    return len(marker.findall(output)) == 1 and len(summary.findall(output)) == 1


@contextmanager
def capture(path):
    """Capture both Python output and inherited child descriptors in private artifacts."""
    sys.stdout.flush()
    sys.stderr.flush()
    saved = [os.dup(1), os.dup(2)]
    try:
        with path.open("w") as log:
            os.dup2(log.fileno(), 1)
            os.dup2(log.fileno(), 2)
            with redirect_stdout(log), redirect_stderr(log):
                yield
    finally:
        for descriptor, original in zip((1, 2), saved):
            os.dup2(original, descriptor)
            os.close(original)


@contextmanager
def environment(values):
    previous = dict(os.environ)
    os.environ.update(values)
    try:
        yield
    finally:
        os.environ.clear()
        os.environ.update(previous)


def source_dir(case, args):
    return (
        args.source_dir
        or args.cache_dir / f"{case['protocol']}-{case['peer_identity']['revision']}"
    )


def endpoint_case(case):
    return dict(
        case, package="ripdpi-hysteria2", target="upstream_interop", ignored=True
    )


def prepare(case, args):
    driver = case["driver"]
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    if driver == "cargo":
        checked(cargo_command(case, True), RUST)
    elif driver == "hysteria2":
        hysteria.binary(args.cache_dir, prepare=True)
        checked(cargo_command(endpoint_case(case), True), RUST)
    elif driver == "outbound":
        source = source_dir(case, args)
        repository, revision = UPSTREAM[case["protocol"]]
        if not source.exists():
            source.mkdir(parents=True)
            checked(["git", "init", "--quiet"], source)
            checked(
                ["git", "fetch", "--quiet", "--depth=1", repository, revision],
                source,
                timeout=120,
            )
            checked(["git", "checkout", "--quiet", "--detach", "FETCH_HEAD"], source)
        head = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=source, text=True
        ).strip()
        if head != revision or subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=source
        ):
            raise ValueError("upstream source must be clean and at the pinned revision")
        checked(
            ["go", "mod", "download"],
            source,
            dict(os.environ, GOTOOLCHAIN=go_toolchain.OUTBOUND_VERSION, GOWORK="off"),
        )
        go_toolchain.prepare(args.cache_dir, go_toolchain.OUTBOUND_VERSION, source)
        native = dict(
            case, package=f"ripdpi-{case['protocol']}", target="upstream_interop"
        )
        if case["test"].startswith("tests::"):
            native.update(package="ripdpi-relay-core", target="lib")
        checked(cargo_command(native, True), RUST)
    elif driver == "awg":
        oracle = runpy.run_path(
            str(ROOT / "scripts/tests/run-standalone-awg-interop.py")
        )
        checked(
            ["go", "mod", "download"],
            oracle["PEER"],
            dict(os.environ, GOTOOLCHAIN=oracle["peer_go_toolchain"]()),
        )
        go_toolchain.prepare(
            args.cache_dir, oracle["peer_go_toolchain"](), oracle["PEER"]
        )
        checked(
            [
                "bash",
                str(ROOT / "scripts/ci/cargo-guarded.sh"),
                "cargo",
                "test",
                "--locked",
                "--jobs",
                "2",
                "-p",
                "ripdpi-warp-core",
                "--features",
                "awg-interop",
                "--test",
                "standalone_awg_interop",
                "--no-run",
            ],
            RUST,
        )


def execute(case, args, result):
    driver = case["driver"]
    if driver == "cargo":
        checked(cargo_command(case), RUST)
    elif driver == "outbound":
        source = source_dir(case, args)
        if not source.is_dir():
            raise FileNotFoundError("upstream peer source absent; run --prepare")
        oracle = runpy.run_path(str(ROOT / "scripts/tests/run-outbound-interop.py"))
        go_env, identity = go_toolchain.resolve(
            args.cache_dir, go_toolchain.OUTBOUND_VERSION
        )
        result["peer_identity"]["compiler"] = identity
        with environment(go_env):
            oracle["run"](
                [
                    "--protocol",
                    case["protocol"],
                    "--source-dir",
                    str(source),
                    "--test",
                    case["test"],
                ]
            )
    elif driver == "awg":
        oracle = runpy.run_path(
            str(ROOT / "scripts/tests/run-standalone-awg-interop.py")
        )
        go_env, identity = go_toolchain.resolve(
            args.cache_dir, oracle["peer_go_toolchain"]()
        )
        result["peer_identity"]["compiler"] = identity
        with environment(go_env):
            oracle["run"]()
    elif driver == "hysteria2":
        # Compilation happens before starting a peer with a finite lifetime.
        checked(cargo_command(endpoint_case(case), True), RUST)
        result["_peer_cleanup"] = {"passed": True}
        with hysteria.peer(
            args.cache_dir, args.out_dir, args.run_id, result["_peer_cleanup"]
        ) as peer:
            checked(
                cargo_command(endpoint_case(case)),
                RUST,
                dict(os.environ, **peer["env"]),
                timeout=40,
            )
            expected = {"tcp", "udp"} if args.scenario.endswith("tcp-udp") else set()
            received = peer["receipts"]
            valid = (
                not peer["errors"]
                and len(received) == len(expected)
                and {item["transport"] for item in received} == expected
                and all(item["sha256"] == peer["payload_sha256"] for item in received)
            )
            result["checks"].append({"id": "destination-receipts", "passed": valid})
            (args.out_dir / "receipts.json").write_text(
                json.dumps({"run_id": args.run_id, "receipts": received}, indent=2)
                + "\n"
            )
            result["artifacts"].append("receipts.json")
            result["peer_identity"]["sha256"] = peer["sha256"]
            if not valid:
                raise RuntimeError("destination receipts do not match the scenario")


def interrupted(signum, _frame):
    raise InterruptedError(f"acceptance interrupted by signal {signum}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--run-id")
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument(
        "--cache-dir", type=Path, default=Path.home() / ".cache/ripdpi-acceptance/peers"
    )
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument(
        "--prepare",
        action="store_true",
        help="allow dependency downloads; produces no acceptance pass",
    )
    args = parser.parse_args(argv)
    if args.list:
        print(
            json.dumps(
                {"scenarios": SCENARIOS, "external_boundaries": EXTERNAL_BOUNDARIES},
                indent=2,
            )
        )
        return 0
    if not args.scenario:
        parser.error("--scenario is required")
    case = SCENARIOS[args.scenario]
    args.cache_dir = args.cache_dir.resolve()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    if args.prepare:
        with ProcessLedger():
            prepare(case, args)
        return 0
    if (
        not args.run_id
        or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}", args.run_id)
        or not args.out_dir
    ):
        parser.error("--run-id (1..96 safe characters) and --out-dir are required")
    args.out_dir = args.out_dir.resolve()
    args.out_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(args.out_dir.iterdir()):
        parser.error("--out-dir must be empty to prevent stale evidence")
    args.out_dir.chmod(0o700)
    result = {
        "schema_version": 1,
        "run_id": args.run_id,
        "scenario_id": args.scenario,
        "source_sha": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "status": "failed",
        "tier": case["tier"],
        "checks": [],
        "artifacts": ["test.log"],
        "peer_identity": dict(case["peer_identity"]),
        "cleanup": {"passed": False},
        "offline_scope": "Dependency resolvers disabled; network egress isolation requires the VM lane.",
    }
    error = None
    ledger = ProcessLedger()
    try:
        with (
            capture(args.out_dir / "test.log"),
            environment(
                {
                    "CARGO_NET_OFFLINE": "true",
                    "GOPROXY": "off",
                    "GOSUMDB": "off",
                    "RUSTUP_AUTO_INSTALL": "0",
                }
            ),
        ):
            try:
                with ledger:
                    execute(case, args, result)
            finally:
                peer_cleanup = result.pop("_peer_cleanup", {"passed": True})
                result["cleanup"]["passed"] = ledger.verified and peer_cleanup["passed"]
        output = (args.out_dir / "test.log").read_text(errors="replace")
        result["checks"].append(
            {"id": "exact-test-executed", "passed": exact_success(output, case["test"])}
        )
        if case["tier"] == "independent-peer":
            identity = result["peer_identity"].get("sha256")
            if not identity:
                match = re.search(r"(?:peer |; )sha256=([0-9a-f]{64})", output)
                identity = match.group(1) if match else None
                if identity:
                    result["peer_identity"]["sha256"] = identity
            result["checks"].append(
                {"id": "pinned-peer-identity", "passed": bool(identity)}
            )
        result["status"] = (
            "passed" if all(item["passed"] for item in result["checks"]) else "failed"
        )
    except (FileNotFoundError, PermissionError) as exc:
        result["status"] = "blocked"
        error = exc
    except (Exception, KeyboardInterrupt) as exc:
        error = exc
    if error:
        with (args.out_dir / "test.log").open("a") as log:
            traceback.print_exception(error, file=log)
        output = (args.out_dir / "test.log").read_text(errors="replace")
        if result["status"] == "failed" and any(
            marker in output
            for marker in (
                "attempting to make an HTTP request, but --offline was specified",
                "no matching package named",
                "module lookup disabled by GOPROXY=off",
                "run this compiler-backed workflow through build-gate",
                "toolchain not installed",
                "could not find `Cargo.toml`",
            )
        ):
            result["status"] = "blocked"
        # Commands may contain temporary paths but never auth values. Do not serialize exception text.
        result["error_type"] = type(error).__name__
        print(
            f"{args.scenario}: {result['status']} ({type(error).__name__}); inspect private test.log",
            file=sys.stderr,
        )
    for check in case["checks"]:
        if not any(item["id"] == check for item in result["checks"]):
            result["checks"].append({"id": check, "passed": False})
    if result["status"] == "passed" and (
        not result["cleanup"]["passed"]
        or not all(check["passed"] for check in result["checks"])
    ):
        result["status"] = "failed"
    (args.out_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return 0 if result["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
