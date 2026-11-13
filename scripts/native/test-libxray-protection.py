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
    parser.add_argument("--stun", type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="ripdpi-libxray-test-") as directory:
        source = Path(directory) / "libxray"
        shutil.copytree(args.libxray, source, ignore=shutil.ignore_patterns(".git"))
        xray_core = Path(directory) / "xray-core"
        shutil.copytree(args.xray_core, xray_core, ignore=shutil.ignore_patterns(".git"))
        for original, replacement in (
            ("android_wrapper.go", "managed_android_wrapper.go"),
            ("controller/controller_android.go", "controller/managed_controller.go"),
            ("controller/proc_resolver_android.go", "controller/managed_proc_resolver.go"),
            ("dns/dns_android.go", "dns/managed_dns.go"),
        ):
            code = (source / original).read_text(encoding="utf-8")
            (source / replacement).write_text(code.replace("//go:build android\n", ""), encoding="utf-8")
        # The Android controller also registers the Android process-finder callback.
        # Select that real production implementation in this host source copy.
        process_source = xray_core / "common/net/find_process_android.go"
        (xray_core / "common/net/managed_find_process.go").write_text(
            process_source.read_text().replace("//go:build android\n", ""), encoding="utf-8",
        )
        for platform in ("darwin", "linux", "windows", "ios", "others"):
            for suffix in ("go", "s"):
                (xray_core / f"common/net/find_process_{platform}.{suffix}").unlink(missing_ok=True)
        for name in ("dns_desktop.go",):
            (source / "dns" / name).unlink()
        for test in (repository / "scripts/tests/libxray").glob("*.go"):
            if test.name.startswith("socks_"):
                target = xray_core / "proxy/socks" / ("managed_" + test.name)
            elif test.name.startswith("buf_"):
                target = xray_core / "common/buf" / ("managed_" + test.name)
            elif test.name.startswith("pipe_"):
                target = xray_core / "transport/pipe" / ("managed_" + test.name)
            else:
                target = source / ("xray" if test.name.startswith("lifecycle_") else "") / ("managed_" + test.name)
            shutil.copyfile(test, target)
        subprocess.run(
            ["go", "mod", "edit", "-replace", f"github.com/xtls/xray-core={xray_core}"],
            cwd=source, check=True, timeout=30,
        )
        if args.grpc:
            subprocess.run(["go", "mod", "edit", "-replace", f"google.golang.org/grpc={args.grpc.resolve()}"],
                           cwd=source, check=True, timeout=30)
        if args.stun:
            subprocess.run(["go", "mod", "edit", "-replace", f"github.com/pion/stun/v3={args.stun.resolve()}"],
                           cwd=source, check=True, timeout=30)
        environment = dict(os.environ, GOFLAGS="-p=2")
        if args.stun:
            subprocess.run(["go", "test", "-mod=mod", "-count=1", "-timeout=90s", "./..."],
                           cwd=args.stun, env=environment, check=True, timeout=900)
        subprocess.run(
            ["go", "test", "-mod=mod", "-count=1", "-timeout=90s", "-run", "TestManaged", "-v",
             ".", "./xray", "github.com/xtls/xray-core/proxy/socks"],
            cwd=source, env=environment, check=True, timeout=900,
        )
        subprocess.run(
            ["go", "test", "-mod=mod", "-count=1", "-timeout=90s", "-v",
             "github.com/xtls/xray-core/common/buf", "github.com/xtls/xray-core/transport/pipe"],
            cwd=source, env=environment, check=True, timeout=900,
        )


if __name__ == "__main__":
    main()
