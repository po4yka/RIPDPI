"""Crash-recoverable ownership for a fresh physical acceptance installation."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import tempfile

PACKAGES = {"app": "com.poyka.ripdpi", "test": "com.poyka.ripdpi.test"}


class OwnedInstallation:
    def __init__(self, adb: list[str], receipt: Path, metadata: dict, apksigner: Path):
        self.adb = adb
        self.receipt = receipt
        self.metadata = metadata
        self.apksigner = apksigner
        self.state = None

    def installed_digest(self, package: str) -> str | None:
        result = subprocess.run(
            self.adb + ["shell", "pm", "path", package],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        paths = [
            line.removeprefix("package:").strip()
            for line in result.stdout.splitlines()
            if line.startswith("package:")
        ]
        if not paths:
            if (
                result.returncode not in (0, 1)
                or result.stdout.strip()
                or result.stderr.strip()
            ):
                raise RuntimeError("cannot inspect physical package installation")
            listed = subprocess.run(
                self.adb + ["shell", "pm", "list", "packages", "--user", "0", package],
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
            )
            names = listed.stdout.splitlines()
            if (
                any(not re.fullmatch(r"package:[A-Za-z0-9_.]+", name) for name in names)
                or f"package:{package}" in names
            ):
                raise RuntimeError("package manager did not confirm package absence")
            return None
        if result.returncode != 0:
            raise RuntimeError("cannot inspect physical package installation")
        if len(paths) != 1 or not re.fullmatch(
            r"/data/app/[A-Za-z0-9_=/+.~-]+/base\.apk", paths[0]
        ):
            raise RuntimeError(
                "physical acceptance requires one exact installed base APK"
            )
        process = subprocess.Popen(
            self.adb + ["exec-out", "cat", paths[0]],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        deadline = threading.Timer(30, process.kill)
        deadline.daemon = True
        deadline.start()
        try:
            checksum = hashlib.file_digest(process.stdout, "sha256").hexdigest()
            if process.wait(timeout=30) != 0:
                raise RuntimeError("cannot verify installed APK ownership")
            return checksum
        finally:
            deadline.cancel()
            if process.poll() is None:
                process.kill()
            process.wait()

    def persist(self) -> None:
        self.receipt.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.receipt.parent.stat().st_mode & 0o077:
            raise ValueError("installation receipt directory must be private")
        descriptor, name = tempfile.mkstemp(
            prefix=self.receipt.name + ".", dir=self.receipt.parent
        )
        temporary = Path(name)
        try:
            with os.fdopen(descriptor, "w") as stream:
                json.dump(self.state, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.receipt)
            directory = os.open(self.receipt.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)

    def prepare(self) -> None:
        current = {
            kind: self.installed_digest(package) for kind, package in PACKAGES.items()
        }
        old = None
        if self.receipt.exists():
            if self.receipt.is_symlink() or self.receipt.stat().st_mode & 0o077:
                raise ValueError("installation receipt must be a private regular file")
            old = json.loads(self.receipt.read_text())
            if old.get("schema_version") != 1 or old.get("serial") != self.adb[-1]:
                raise ValueError("installation receipt device mismatch")
        for kind, checksum in current.items():
            if checksum is None:
                continue
            accepted = old.get("packages", {}).get(kind, {}) if old else {}
            if checksum not in (
                accepted.get("planned_sha256"),
                accepted.get("previous_sha256"),
            ):
                raise RuntimeError(
                    "refusing to replace an unrelated physical-device installation"
                )
        packages = {}
        for kind, package in PACKAGES.items():
            artifact = self.metadata["apks"][kind]
            path = Path(artifact["path"])
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifact["sha256"]:
                raise ValueError("prepared APK content changed before installation")
            signed = subprocess.run(
                [str(self.apksigner), "verify", "--print-certs", str(path)],
                check=True,
                capture_output=True,
                text=True,
                timeout=60,
            )
            certificates = re.findall(
                r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$",
                signed.stdout,
                re.M,
            )
            if len(certificates) != 1:
                raise ValueError("acceptance APK must have one verified signer")
            packages[kind] = {
                "package": package,
                "previous_sha256": current[kind],
                "planned_sha256": artifact["sha256"],
                "signer_sha256": certificates[0],
                "installed": False,
            }
        if packages["app"]["signer_sha256"] != packages["test"]["signer_sha256"]:
            raise ValueError("app and test APK signing identities differ")
        self.state = {"schema_version": 1, "serial": self.adb[-1], "packages": packages}
        # This intent must reach disk before the first adb install starts.
        self.persist()

    def confirm(self, kind: str) -> None:
        item = self.state["packages"][kind]
        if self.installed_digest(item["package"]) != item["planned_sha256"]:
            raise RuntimeError(
                "installed APK does not match the owned installation intent"
            )
        item["installed"] = True
        self.persist()
