#!/usr/bin/env python3
"""Capture and restore RIPDPI-owned state around destructive Android tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


VERSION = "ripdpi_android_device_session_v2"
SETTINGS = (
    ("secure", "always_on_vpn_app"),
    ("secure", "always_on_vpn_lockdown"),
    ("global", "stay_on_while_plugged_in"),
)
PERMISSION_RE = re.compile(
    r"^\s+(?P<name>[A-Za-z0-9_.]+): granted=(?P<granted>true|false)(?:, flags=\[(?P<flags>[^]]*)\])?"
)
APP_OP_RE = re.compile(
    r"^\s*(?P<name>[A-Z0-9_]+):\s*(?P<mode>allow|ignore|deny|default|foreground|ask)(?:;|$)"
)


class SessionError(RuntimeError):
    pass


def adb_command(adb: str, serial: str, *args: str) -> list[str]:
    return [adb, "-s", serial, *args]


def run(
    adb: str,
    serial: str,
    *args: str,
    check: bool = True,
    input_bytes: bytes | None = None,
) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        adb_command(adb, serial, *args),
        input=input_bytes,
        capture_output=True,
        check=False,
    )
    if check and result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        raise SessionError(f"adb {' '.join(args)} failed: {stderr or result.returncode}")
    return result


def text(result: subprocess.CompletedProcess[bytes]) -> str:
    return result.stdout.decode("utf-8", errors="strict").replace("\r", "").strip()


def package_paths(adb: str, serial: str, package: str) -> list[str]:
    result = run(adb, serial, "shell", "pm", "path", package, check=False)
    if result.returncode != 0:
        return []
    paths = []
    for line in text(result).splitlines():
        if line.startswith("package:"):
            paths.append(line.removeprefix("package:"))
    return paths


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def capture_permissions(
    adb: str, serial: str, package: str, user_id: str
) -> list[dict[str, object]]:
    dump = text(run(adb, serial, "shell", "dumpsys", "package", package))
    permissions: dict[str, dict[str, object]] = {}
    runtime_indent: int | None = None
    selected_user = False
    for line in dump.splitlines():
        stripped = line.strip()
        user_match = re.fullmatch(r"User (\d+):", stripped)
        if user_match:
            selected_user = user_match.group(1) == user_id
            runtime_indent = None
            continue
        if not selected_user:
            continue
        indent = len(line) - len(line.lstrip())
        if stripped == "runtime permissions:":
            runtime_indent = indent
            continue
        if runtime_indent is None:
            continue
        if line.strip() and indent <= runtime_indent:
            runtime_indent = None
            continue
        match = PERMISSION_RE.match(line)
        if match:
            permissions[match["name"]] = {
                "name": match["name"],
                "granted": match["granted"] == "true",
                "flags": sorted(flag.strip() for flag in (match["flags"] or "").split("|") if flag.strip()),
            }
    return [permissions[name] for name in sorted(permissions)]


def capture_app_ops(adb: str, serial: str, package: str, user_id: str) -> dict[str, str]:
    output = text(run(adb, serial, "shell", "cmd", "appops", "get", "--user", user_id, package))
    operations: dict[str, str] = {}
    for line in output.splitlines():
        match = APP_OP_RE.match(line)
        if match:
            operations[match["name"]] = match["mode"]
    return dict(sorted(operations.items()))


def capture_package(
    adb: str, serial: str, state_dir: Path, package: str, user_id: str
) -> dict[str, object]:
    remote_paths = package_paths(adb, serial, package)
    record: dict[str, object] = {"name": package, "installed": bool(remote_paths)}
    if not remote_paths:
        return record

    package_dir = state_dir / "packages" / package
    package_dir.mkdir(parents=True)
    apks: list[dict[str, str]] = []
    for index, remote_path in enumerate(remote_paths):
        local_path = package_dir / f"{index:02d}-{Path(remote_path).name}"
        run(adb, serial, "pull", remote_path, str(local_path))
        apks.append({"file": str(local_path.relative_to(state_dir)), "sha256": sha256(local_path)})

    data = run(adb, serial, "exec-out", "run-as", package, "tar", "-C", ".", "-cf", "-", ".")
    if not data.stdout:
        raise SessionError(f"refusing to replace {package}: run-as data backup was empty")
    data_path = package_dir / "data.tar"
    data_path.write_bytes(data.stdout)
    record.update(
        apks=apks,
        data={"file": str(data_path.relative_to(state_dir)), "sha256": sha256(data_path)},
        permissions=capture_permissions(adb, serial, package, user_id),
        appOps=capture_app_ops(adb, serial, package, user_id),
    )
    return record


def capture(args: argparse.Namespace) -> None:
    state_dir = args.state_dir.resolve()
    if state_dir.exists():
        raise SessionError(f"state directory already exists: {state_dir}")
    state_dir.mkdir(parents=True)
    try:
        device_serial = text(run(args.adb, args.serial, "get-serialno"))
        if device_serial != args.serial:
            raise SessionError(f"selected device mismatch: expected {args.serial}, got {device_serial}")
        user_id = text(run(args.adb, args.serial, "shell", "am", "get-current-user"))
        if not user_id.isdecimal():
            raise SessionError(f"could not identify the selected Android user: {user_id!r}")
        settings = {}
        for namespace, key in SETTINGS:
            value = text(run(args.adb, args.serial, "shell", "settings", "get", namespace, key))
            settings[f"{namespace}/{key}"] = None if value in {"", "null"} else value
        manifest = {
            "version": VERSION,
            "serial": args.serial,
            "userId": user_id,
            "settings": settings,
            "packages": [
                capture_package(args.adb, args.serial, state_dir, package, user_id)
                for package in args.package
            ],
        }
        (state_dir / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except BaseException:
        shutil.rmtree(state_dir, ignore_errors=True)
        raise


def require_digest(state_dir: Path, record: dict[str, str]) -> Path:
    path = state_dir / record["file"]
    if not path.is_file() or sha256(path) != record["sha256"]:
        raise SessionError(f"device-session backup is missing or corrupt: {path}")
    return path


def restore_setting(adb: str, serial: str, identity: str, value: str | None) -> None:
    namespace, key = identity.split("/", 1)
    if value is None:
        run(adb, serial, "shell", "settings", "delete", namespace, key)
    else:
        run(adb, serial, "shell", "settings", "put", namespace, key, value)
    restored = text(run(adb, serial, "shell", "settings", "get", namespace, key))
    actual = None if restored in {"", "null"} else restored
    if actual != value:
        raise SessionError(f"setting restoration mismatch for {identity}: {actual!r} != {value!r}")


def restore_permissions(
    adb: str,
    serial: str,
    package: str,
    user_id: str,
    expected: list[dict[str, object]],
) -> None:
    # Replacing an installed package preserves permission flags. Restore every
    # runtime grant explicitly, then verify both grants and flags from dumpsys.
    for permission in expected:
        run(
            adb,
            serial,
            "shell",
            "pm",
            "grant" if permission["granted"] else "revoke",
            "--user",
            user_id,
            package,
            str(permission["name"]),
        )
    actual = capture_permissions(adb, serial, package, user_id)
    if actual != expected:
        raise SessionError(f"runtime permission restoration mismatch for {package}")


def restore_app_ops(
    adb: str, serial: str, package: str, user_id: str, expected: dict[str, str]
) -> None:
    run(adb, serial, "shell", "cmd", "appops", "reset", "--user", user_id, package)
    for operation, mode in expected.items():
        run(adb, serial, "shell", "cmd", "appops", "set", "--user", user_id, package, operation, mode)
    actual = capture_app_ops(adb, serial, package, user_id)
    if actual != expected:
        raise SessionError(f"app-op restoration mismatch for {package}")


def restore_package(
    adb: str, serial: str, state_dir: Path, record: dict[str, object], user_id: str
) -> None:
    package = str(record["name"])
    run(adb, serial, "shell", "am", "force-stop", package, check=False)
    if not record["installed"]:
        run(adb, serial, "uninstall", package, check=False)
        return

    apk_paths = [require_digest(state_dir, apk) for apk in record["apks"]]  # type: ignore[arg-type]
    # -t is harmless for normal packages and required when restoring a captured
    # instrumentation/test-only APK.
    run(adb, serial, "install-multiple", "-r", "-d", "-t", *(str(path) for path in apk_paths))
    data_record = record["data"]
    assert isinstance(data_record, dict)
    data_path = require_digest(state_dir, data_record)  # type: ignore[arg-type]
    run(adb, serial, "shell", "pm", "clear", package)
    run(
        adb,
        serial,
        "shell",
        "run-as",
        package,
        "tar",
        "-C",
        ".",
        "-xf",
        "-",
        input_bytes=data_path.read_bytes(),
    )
    restore_permissions(adb, serial, package, user_id, record["permissions"])  # type: ignore[arg-type]
    restore_app_ops(adb, serial, package, user_id, record["appOps"])  # type: ignore[arg-type]
    run(adb, serial, "shell", "am", "force-stop", package, check=False)


def restore(args: argparse.Namespace) -> None:
    state_dir = args.state_dir.resolve()
    manifest_path = state_dir / "manifest.json"
    if not manifest_path.is_file():
        raise SessionError(f"device-session manifest is missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("version") != VERSION or manifest.get("serial") != args.serial:
        raise SessionError("device-session manifest does not match the selected device")
    if text(run(args.adb, args.serial, "get-serialno")) != args.serial:
        raise SessionError("selected device changed before restoration")
    current_user = text(run(args.adb, args.serial, "shell", "am", "get-current-user"))
    if manifest.get("userId") != current_user:
        raise SessionError("selected Android user changed before restoration")

    for record in reversed(manifest["packages"]):
        restore_package(args.adb, args.serial, state_dir, record, current_user)
    for identity, value in manifest["settings"].items():
        restore_setting(args.adb, args.serial, identity, value)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("capture", "restore"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--state-dir", required=True, type=Path)
    parser.add_argument("--package", action="append", default=[])
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "capture":
            if not args.package:
                raise SessionError("capture requires at least one --package")
            capture(args)
        else:
            restore(args)
    except (OSError, ValueError, KeyError, TypeError, SessionError) as error:
        print(f"Android device session: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
