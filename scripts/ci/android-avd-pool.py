#!/usr/bin/env python3
"""Lease a local Android emulator for one command; never reset existing data."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path


POOL_LIMIT = 2
MANAGED_NAME = re.compile(r"RIPDPI_(?:Pool_[12]|Temp_[0-9a-f]{12})\Z")
LEGACY_NAME = re.compile(r"RIPDPI_[A-Za-z0-9_]+_20[0-9]{6}\Z")
SAFE_VALUE = re.compile(r"[A-Za-z0-9_.-]+\Z")


class PoolError(RuntimeError):
    pass


class Interrupted(PoolError):
    def __init__(self, signum: int):
        super().__init__(f"Interrupted by signal {signum}")
        self.signum = signum


def read_ini(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        raise PoolError(f"Missing or unsafe INI file: {path}")
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", ";")):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def avd_home(environ: dict[str, str]) -> Path:
    if environ.get("ANDROID_AVD_HOME"):
        return Path(environ["ANDROID_AVD_HOME"]).expanduser().absolute()
    if environ.get("ANDROID_USER_HOME"):
        return (Path(environ["ANDROID_USER_HOME"]).expanduser() / "avd").absolute()
    return (Path(environ.get("ANDROID_SDK_HOME", str(Path.home()))).expanduser()
            / ".android" / "avd").absolute()


def no_symlinks(path: Path) -> None:
    for item in [path, *path.parents]:
        if item.is_symlink():
            raise PoolError(f"Refuse symlink path: {item}")


def command(args: list[str], *, timeout: float = 10, **kwargs) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(args, timeout=timeout, text=True, capture_output=True, **kwargs)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PoolError(f"Cannot inspect or run {args[0]}: {exc}") from exc


class Lease:
    """Stable advisory lock file. File identity is never replaced or removed."""

    def __init__(self, path: Path):
        no_symlinks(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        self.acquired = False
        try:
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.acquired = True
        except BlockingIOError:
            pass

    def close(self) -> None:
        if self.acquired:
            fcntl.flock(self.fd, fcntl.LOCK_UN)
        os.close(self.fd)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class Pool:
    def __init__(self, environ: dict[str, str] | None = None):
        self.env = dict(os.environ if environ is None else environ)
        self.home = avd_home(self.env)
        no_symlinks(self.home)
        self.home.mkdir(parents=True, exist_ok=True)
        self.state = self.home / ".ripdpi-avd-pool"
        no_symlinks(self.state)
        self.state.mkdir(exist_ok=True)
        self.env["ANDROID_AVD_HOME"] = str(self.home)
        candidates = [self.env.get("ANDROID_SDK_ROOT"), self.env.get("ANDROID_HOME"),
                      str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk"),
                      str(Path.home() / "android-sdk"), str(Path.home() / ".android-sdk"),
                      str(Path.home() / ".local/share/android-sdk")]
        adb_path = shutil.which("adb", path=self.env.get("PATH"))
        if adb_path:
            candidates.append(str(Path(adb_path).resolve().parent.parent))
        self.sdk = next((Path(p) for p in candidates if p and (Path(p) / "platform-tools/adb").is_file()), None)
        if self.sdk is None:
            raise PoolError("Android SDK with platform-tools/adb is required")
        self.adb = str(self.sdk / "platform-tools/adb")
        self.emulator = str(self.sdk / "emulator/emulator")
        managers = [self.sdk / "cmdline-tools/latest/bin/avdmanager",
                    self.sdk / "cmdline-tools/bin/avdmanager"]
        self.manager = next((str(p) for p in managers if p.is_file()), None)

    def paths(self, name: str) -> tuple[Path, Path, Path]:
        if not SAFE_VALUE.fullmatch(name):
            raise PoolError(f"Unsafe AVD name: {name}")
        return self.home / f"{name}.avd", self.home / f"{name}.ini", self.state / f"{name}.json"

    def lease(self, name: str) -> Lease:
        return Lease(self.state / f"{name}.lock")

    def metadata(self, name: str) -> dict:
        _, _, path = self.paths(name)
        no_symlinks(path)
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise PoolError(f"No valid pool metadata for {name}") from exc
        expected_kind = "temporary" if name.startswith("RIPDPI_Temp_") else "pool"
        if (not isinstance(value, dict) or value.get("schema") != 1
                or value.get("name") != name or not MANAGED_NAME.fullmatch(name)
                or value.get("kind") != expected_kind or not isinstance(value.get("owner"), str)
                or not value["owner"] or value.get("state") not in ("creating", "ready")
                or any(not isinstance(value.get(k), str) or not SAFE_VALUE.fullmatch(value[k])
                       for k in ("api", "arch", "target", "profile"))
                or (expected_kind == "temporary" and not value.get("reason"))):
            raise PoolError(f"Invalid pool metadata for {name}")
        return value

    def write_metadata(self, name: str, value: dict) -> None:
        _, _, path = self.paths(name)
        no_symlinks(path)
        temporary = path.with_suffix(f".{os.getpid()}.tmp")
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
        temporary.replace(path)

    def safe_paths(self, name: str, *, incomplete: bool = False) -> tuple[Path, Path]:
        directory, ini, _ = self.paths(name)
        no_symlinks(directory)
        no_symlinks(ini)
        if directory.exists() and not directory.is_dir():
            raise PoolError(f"AVD is not a directory: {directory}")
        if ini.exists():
            configured = read_ini(ini).get("path")
            if not configured or Path(configured).absolute() != directory:
                raise PoolError(f"AVD {name} points outside its expected directory")
        elif directory.exists() and not incomplete:
            raise PoolError(f"Missing AVD pointer for {name}")
        if directory.exists():
            for root, directories, files in os.walk(directory, followlinks=False):
                for item in directories + files:
                    if (Path(root) / item).is_symlink():
                        raise PoolError(f"Refuse symlink inside AVD {name}")
        return directory, ini

    def compatible(self, name: str, requested: dict[str, str]) -> bool:
        metadata = self.metadata(name)
        directory, _ = self.safe_paths(name)
        if metadata["state"] != "ready":
            return False
        config = read_ini(directory / "config.ini")
        actual = {"api": config.get("target", "").removeprefix("android-"),
                  "arch": config.get("abi.type"), "target": config.get("tag.id"),
                  "profile": config.get("hw.device.name")}
        # avdmanager commonly omits target in config.ini; the pointer contains it.
        if not actual["api"]:
            actual["api"] = read_ini(self.paths(name)[1]).get("target", "").removeprefix("android-")
        image_path = config.get("image.sysdir.1")
        if not image_path:
            return False
        image = Path(image_path)
        if not image.is_absolute():
            image = self.sdk / image
        expected = self.sdk / "system-images" / f"android-{requested['api']}" / requested["target"] / requested["arch"]
        return (image.resolve() == expected.resolve()
                and all(metadata.get(k) == v and actual[k] == v for k, v in requested.items()))

    def active_reason(self, name: str) -> str | None:
        """All probes must succeed; uncertainty prevents launch and deletion."""
        processes = command(["ps", "-ax", "-o", "command="], env=self.env)
        if processes.returncode:
            raise PoolError("Cannot inspect emulator processes")
        for line in processes.stdout.splitlines():
            try:
                words = shlex.split(line)
            except ValueError:
                if name in line:
                    return "unreadable process command"
                continue
            if any(word == f"@{name}" for word in words):
                return "emulator process"
            if any(word in ("-avd", "--avd") and i + 1 < len(words) and words[i + 1] == name
                   for i, word in enumerate(words)):
                return "emulator process"
        devices = command([self.adb, "devices"], env=self.env)
        if devices.returncode:
            raise PoolError("Cannot inspect adb devices")
        for line in devices.stdout.splitlines():
            parts = line.split()
            if not parts or not parts[0].startswith("emulator-"):
                continue
            if len(parts) < 2 or parts[1] != "device":
                raise PoolError(f"Cannot inspect offline emulator {parts[0]}")
            identity = command([self.adb, "-s", parts[0], "emu", "avd", "name"], env=self.env)
            if identity.returncode or not identity.stdout.strip():
                raise PoolError(f"Cannot identify emulator {parts[0]}")
            if name in identity.stdout.splitlines():
                return f"adb device {parts[0]}"
        directory = self.paths(name)[0]
        if directory.is_dir():
            lsof = shutil.which("lsof", path=self.env.get("PATH"))
            if not lsof:
                raise PoolError(f"Cannot check open emulator files for {name}: lsof is required")
            result = command([lsof, "+D", str(directory)], env=self.env)
            if result.returncode == 0:
                return "open emulator files"
            if result.returncode != 1 or result.stderr.strip():
                raise PoolError(f"Cannot check open emulator files for {name}")
        return None

    def create(self, name: str, requested: dict[str, str], reason: str = "") -> None:
        directory, ini, meta = self.paths(name)
        if directory.exists() or ini.exists() or meta.exists():
            raise PoolError(f"AVD {name} already exists; no data was changed")
        image = self.sdk / "system-images" / f"android-{requested['api']}" / requested["target"] / requested["arch"]
        if not (image / "source.properties").is_file():
            raise PoolError(f"System image is not installed: {image}; install it explicitly first")
        if not self.manager:
            raise PoolError("Installed avdmanager is required to create a free pool slot")
        value = dict(requested, schema=1, name=name, kind="temporary" if reason else "pool",
                     owner=f"{self.env.get('USER', 'unknown')}:{os.getpid()}", reason=reason,
                     created_at=time.time(), state="creating")
        self.write_metadata(name, value)
        result = command([self.manager, "create", "avd", "-n", name, "-k",
                          f"system-images;android-{requested['api']};{requested['target']};{requested['arch']}",
                          "-p", str(directory), "-d", requested["profile"]],
                         timeout=120, input="no\n", env=self.env)
        if result.returncode:
            raise PoolError(f"avdmanager failed for {name}: {result.stderr.strip()}")
        self.safe_paths(name)
        value["state"] = "ready"
        self.write_metadata(name, value)
        if not self.compatible(name, requested):
            raise PoolError(f"Created AVD {name} does not match its requested image/profile")

    def acquire(self, args) -> tuple[str, Lease]:
        requested = {"api": "37.0" if args.api == "37" else args.api,
                     "arch": args.arch, "target": args.target, "profile": args.profile}
        if args.temporary:
            name = f"RIPDPI_Temp_{uuid.uuid4().hex[:12]}"
            lease = self.lease(name)
            try:
                self.create(name, requested, args.reason)
                return name, lease
            except BaseException:
                try:
                    if self.paths(name)[2].exists():
                        self.remove(name)
                finally:
                    lease.close()
                raise
        errors = []
        absent = []
        # Reuse a matching slot before filling an absent slot.
        for number in range(1, POOL_LIMIT + 1):
            name = f"RIPDPI_Pool_{number}"
            lease = self.lease(name)
            if not lease.acquired:
                lease.close()
                errors.append(f"{name}: leased")
                continue
            keep = False
            try:
                reason = self.active_reason(name)
                if reason:
                    errors.append(f"{name}: {reason}")
                    continue
                directory, ini, meta = self.paths(name)
                if not any(path.exists() for path in (directory, ini, meta)):
                    if number <= args.pool_size:
                        absent.append(name)
                elif self.compatible(name, requested):
                    keep = True
                    return name, lease
                else:
                    errors.append(f"{name}: incompatible; explicit idle cleanup is required")
            except PoolError as exc:
                errors.append(f"{name}: {exc}")
            finally:
                if not keep:
                    lease.close()
        for name in absent:
            lease = self.lease(name)
            if not lease.acquired:
                lease.close()
                continue
            try:
                if self.active_reason(name):
                    raise PoolError(f"{name} became active")
                self.create(name, requested)
                return name, lease
            except BaseException:
                lease.close()
                raise
        raise PoolError("No compatible idle AVD in the bounded pool. " + "; ".join(errors))

    def remove(self, name: str, *, legacy: bool = False) -> None:
        metadata = None if legacy else self.metadata(name)
        if legacy and not LEGACY_NAME.fullmatch(name):
            raise PoolError(f"Not an approved dated legacy AVD name: {name}")
        directory, ini = self.safe_paths(name, incomplete=bool(metadata and metadata["state"] == "creating"))
        reason = self.active_reason(name)
        if reason:
            raise PoolError(f"Keep {name}: {reason}")
        if directory.exists():
            shutil.rmtree(directory)
        ini.unlink(missing_ok=True)
        if metadata:
            self.paths(name)[2].unlink()

    def inspect(self, name: str, *, legacy: bool = False) -> dict:
        result = {"name": name, "eligible": False}
        with self.lease(name) as lease:
            if not lease.acquired:
                return dict(result, reason="leased")
            try:
                metadata = None if legacy else self.metadata(name)
                if legacy and not LEGACY_NAME.fullmatch(name):
                    raise PoolError("not a dated legacy RIPDPI name")
                self.safe_paths(name, incomplete=bool(metadata and metadata["state"] == "creating"))
                active = self.active_reason(name)
                return dict(result, eligible=not active, reason=active or "idle", metadata=metadata)
            except PoolError as exc:
                return dict(result, reason=str(exc))


def free_port(lock_directory: Path) -> tuple[int, Lease]:
    for port in range(5554, 5682, 2):
        lease = Lease(lock_directory / f"port-{port}.lock")
        if not lease.acquired:
            lease.close()
            continue
        sockets = []
        keep = False
        try:
            for number in (port, port + 1):
                sock = socket.socket()
                sockets.append(sock)
                sock.bind(("127.0.0.1", number))
            keep = True
            return port, lease
        except OSError:
            continue
        finally:
            for sock in sockets:
                sock.close()
            if not keep:
                lease.close()
    raise PoolError("No free emulator console/adb port pair")


def group_has_live_members(group: int) -> bool:
    # Use the system inspector, independent of SDK wrappers on PATH.
    result = command(["/bin/ps", "-ax", "-o", "pgid=,stat="], timeout=5)
    if result.returncode:
        raise PoolError(f"Cannot inspect process group {group}")
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 2 or not fields[0].isdigit():
            raise PoolError(f"Ambiguous process group inspection for {group}")
        if int(fields[0]) == group:
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9+<>=]*", fields[1]):
                raise PoolError(f"Unknown process state in group {group}")
            if not fields[1].startswith("Z"):
                return True
    return False


def signal_group(process: subprocess.Popen, signum: int) -> bool:
    try:
        os.killpg(process.pid, signum)
        # Linux can also report success for a zombie-only group. Signal 0
        # checks permission and existence; inspect execution state as well.
        return group_has_live_members(process.pid) if signum == 0 else True
    except ProcessLookupError:
        return False
    except PermissionError:
        # Darwin excludes zombies from killpg and returns EPERM for a group
        # with no signalable members. Reap the leader, then confirm that no
        # live members remain. A real permission failure must stay an error.
        process.poll()
        if group_has_live_members(process.pid):
            raise
        return False


def stop_process(process: subprocess.Popen | None) -> None:
    if process is None:
        return
    # Children use a distinct session, so interruption also stops their workers.
    signal_group(process, signal.SIGTERM)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        process.poll()
        if not signal_group(process, 0):
            break
        time.sleep(0.1)
    else:
        signal_group(process, signal.SIGKILL)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired as exc:
        raise PoolError(f"Process group {process.pid} did not stop") from exc
    deadline = time.monotonic() + 5
    while signal_group(process, 0):
        if time.monotonic() >= deadline:
            raise PoolError(f"Process group {process.pid} is still active after cleanup")
        time.sleep(.1)


def boot_probe(args: list[str], *, deadline: float, env: dict[str, str]) -> subprocess.CompletedProcess | None:
    """A cold-boot adb timeout is transient, but never exceeds the boot deadline."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return None
    try:
        return command(args, timeout=min(5, remaining), env=env)
    except PoolError as exc:
        if isinstance(exc.__cause__, subprocess.TimeoutExpired):
            return None
        raise


def run(pool: Pool, args) -> int:
    name = lease = None
    port_lease = None
    emulator = child = None
    interrupted = None
    previous = {}

    def handle_signal(signum, _frame):
        raise Interrupted(signum)

    for signum in (signal.SIGINT, signal.SIGTERM):
        previous[signum] = signal.signal(signum, handle_signal)
    try:
        name, lease = pool.acquire(args)
        port_directory = Path("/tmp").resolve() / f"ripdpi-android-emulator-{os.getuid()}"
        no_symlinks(port_directory)
        port_directory.mkdir(mode=0o700, exist_ok=True)
        if port_directory.stat().st_uid != os.getuid():
            raise PoolError("Emulator port-lock directory belongs to another user")
        with Lease(port_directory / "ports.lock") as ports:
            if not ports.acquired:
                raise PoolError("Another wrapper is allocating an emulator port; retry after boot")
            port, port_lease = free_port(port_directory)
            serial = f"emulator-{port}"
            log = pool.state / f"{name}.emulator.log"
            no_symlinks(log)
            with log.open("w", encoding="utf-8") as stream:
                emulator = subprocess.Popen(
                    [pool.emulator, "-avd", name, "-port", str(port), "-no-window", "-no-audio",
                     "-no-boot-anim", "-no-snapshot", "-no-snapshot-save", "-gpu",
                     "host" if sys.platform == "darwin" else "swiftshader_indirect"],
                    env=pool.env, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                    start_new_session=True)
            deadline = time.monotonic() + args.boot_timeout
            while time.monotonic() < deadline:
                if emulator.poll() is not None:
                    raise PoolError(f"Emulator {name} exited before boot; inspect {log}")
                identity = boot_probe([pool.adb, "-s", serial, "emu", "avd", "name"],
                                      env=pool.env, deadline=deadline)
                if identity is not None and identity.returncode == 0 and name in identity.stdout.splitlines():
                    break
                time.sleep(min(0.25, max(0, deadline - time.monotonic())))
            else:
                raise PoolError(f"Emulator {name} did not reserve {serial} before timeout")
        # Release allocation serialization; keep this port lease through shutdown.
        while time.monotonic() < deadline:
            if emulator.poll() is not None:
                raise PoolError(f"Emulator {name} exited during boot; inspect {log}")
            boot = boot_probe([pool.adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                              env=pool.env, deadline=deadline)
            if boot is not None and boot.returncode == 0 and boot.stdout.strip() == "1":
                complete = boot_probe([pool.adb, "-s", serial, "shell", "getprop", "dev.bootcomplete"],
                                      env=pool.env, deadline=deadline)
                packages = boot_probe([pool.adb, "-s", serial, "shell", "pm", "path", "android"],
                                      env=pool.env, deadline=deadline)
                if (complete is not None and complete.returncode == 0 and complete.stdout.strip() == "1"
                        and packages is not None and packages.returncode == 0
                        and packages.stdout.startswith("package:")):
                    break
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
        else:
            raise PoolError(f"Boot timeout for {serial}; inspect {log}")
        print(f"Leased {name} as {serial}", file=sys.stderr, flush=True)
        child_env = dict(pool.env, ANDROID_SERIAL=serial, RIPDPI_AVD_NAME=name)
        child = subprocess.Popen(args.command, env=child_env, start_new_session=True)
        while True:
            if emulator.poll() is not None:
                raise PoolError(f"Emulator {name} exited while command was running; inspect {log}")
            status = child.poll()
            if status is not None:
                return status
            time.sleep(0.1)
    except Interrupted as exc:
        interrupted = exc
        return 128 + exc.signum
    finally:
        # A second signal must not interrupt ownership cleanup.
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        cleanup_errors = []
        try:
            for process in (child, emulator):
                try:
                    stop_process(process)
                except (PoolError, OSError) as exc:
                    cleanup_errors.append(str(exc))
            if args.temporary and name and not cleanup_errors:
                try:
                    pool.remove(name)
                    pool.paths(name)[2].with_suffix(".emulator.log").unlink(missing_ok=True)
                except (PoolError, OSError) as exc:
                    cleanup_errors.append(str(exc))
        finally:
            if port_lease:
                port_lease.close()
            if lease:
                lease.close()
            for signum, handler in previous.items():
                signal.signal(signum, handler)
        if interrupted:
            print(str(interrupted), file=sys.stderr)
        if cleanup_errors:
            raise PoolError("Cleanup incomplete; retain metadata for explicit cleanup: " + "; ".join(cleanup_errors))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="action", required=True)
    execute = commands.add_parser("run", help="lease an AVD for the full command lifetime")
    execute.add_argument("--api", required=True)
    execute.add_argument("--arch", required=True)
    execute.add_argument("--target", default="google_apis")
    execute.add_argument("--profile", default="pixel_7")
    execute.add_argument("--pool-size", type=int, choices=range(1, POOL_LIMIT + 1), default=POOL_LIMIT)
    execute.add_argument("--temporary", action="store_true")
    execute.add_argument("--reason", default="")
    execute.add_argument("--boot-timeout", type=int, default=600)
    execute.add_argument("command", nargs=argparse.REMAINDER)
    commands.add_parser("status", help="show managed and dated legacy AVD safety state")
    cleanup = commands.add_parser("cleanup", help="report eligible AVDs; deletion requires --apply")
    cleanup.add_argument("--apply", action="store_true")
    cleanup.add_argument("--legacy", action="store_true", help="allow explicitly named dated RIPDPI AVDs")
    cleanup.add_argument("--names", nargs="+", default=[])
    return result


def main(argv: list[str] | None = None) -> int:
    arguments = parser()
    args = arguments.parse_args(argv)
    if args.action == "run":
        if args.command and args.command[0] == "--":
            args.command.pop(0)
        if not args.command:
            arguments.error("run requires -- COMMAND [ARGS...]; no detached start is supported")
        if any(not SAFE_VALUE.fullmatch(v) for v in (args.api, args.arch, args.target, args.profile)):
            arguments.error("API, ABI, image tag and profile must use simple SDK identifiers")
        if args.temporary and not args.reason.strip():
            arguments.error("--temporary requires --reason")
        if not args.temporary and args.reason:
            arguments.error("--reason requires --temporary")
        if args.boot_timeout <= 0:
            arguments.error("--boot-timeout must be positive")
    elif args.action == "cleanup" and args.legacy and not args.names:
        arguments.error("--legacy requires explicit --names")
    try:
        pool = Pool()
        if args.action == "run":
            return run(pool, args)
        managed = sorted(path.stem for path in pool.state.glob("RIPDPI_*.json"))
        legacy = sorted(path.stem for path in pool.home.glob("RIPDPI_*.ini") if LEGACY_NAME.fullmatch(path.stem))
        names = sorted(set(managed + legacy)) if args.action == "status" else (args.names or managed)
        failed = False
        for name in names:
            use_legacy = name not in managed and (args.action == "status" or args.legacy)
            if args.action == "cleanup" and args.apply:
                with pool.lease(name) as lease:
                    if not lease.acquired:
                        print(f"Keep {name}: leased")
                        failed = True
                        continue
                    try:
                        pool.remove(name, legacy=use_legacy)
                        print(f"Removed {name}")
                    except PoolError as exc:
                        print(f"Keep {name}: {exc}")
                        failed = True
            else:
                print(json.dumps(pool.inspect(name, legacy=use_legacy), sort_keys=True))
        return 1 if failed else 0
    except (PoolError, OSError) as exc:
        print(f"AVD pool: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
