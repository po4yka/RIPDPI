#!/usr/bin/env python3
"""Execute the Android controller sources against real host sockets.

Only build constraints are adapted in an isolated source copy. Production code
and public gomobile methods are unchanged. The AAR/emulator lane additionally
covers the Android build and JNI boundary.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("libxray", type=Path)
    parser.add_argument("xray_core", type=Path)
    parser.add_argument("--grpc", type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="ripdpi-libxray-test-") as directory:
        source = Path(directory) / "libxray"
        shutil.copytree(args.libxray, source, ignore=shutil.ignore_patterns(".git"))
        for original, replacement in (
            ("android_wrapper.go", "managed_android_wrapper.go"),
            ("controller/controller_android.go", "controller/managed_controller.go"),
            ("dns/dns_android.go", "dns/managed_dns.go"),
        ):
            code = (source / original).read_text(encoding="utf-8")
            (source / replacement).write_text(code.replace("//go:build android\n", ""), encoding="utf-8")
        (source / "dns_wrapper.go").unlink()
        for name in ("dns_other.go", "dns_linux.go", "dns_windows.go"):
            (source / "dns" / name).unlink()
        for test in (repository / "scripts/tests/libxray").glob("*.go"):
            target = source / ("xray" if test.name.startswith("lifecycle_") else "") / ("managed_" + test.name)
            shutil.copyfile(test, target)
        subprocess.run(
            ["go", "mod", "edit", "-replace", f"github.com/xtls/xray-core={args.xray_core.resolve()}"],
            cwd=source, check=True, timeout=30,
        )
        if args.grpc:
            subprocess.run(["go", "mod", "edit", "-replace", f"google.golang.org/grpc={args.grpc.resolve()}"],
                           cwd=source, check=True, timeout=30)
        environment = dict(os.environ, GOFLAGS="-p=2")
        subprocess.run(
            ["go", "test", "-mod=mod", "-count=1", "-timeout=90s", "-run", "TestManaged", "-v", ".", "./xray"],
            cwd=source, env=environment, check=True, timeout=900,
        )


if __name__ == "__main__":
    main()
