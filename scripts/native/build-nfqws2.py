#!/usr/bin/env python3
"""Build the checksum-locked upstream nfqws2 backend without network access."""

import argparse
import hashlib
import io
import json
import lzma
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile


ABIS = {
    "armeabi-v7a": ("armv7a-linux-androideabi", "arm-linux-androideabi"),
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "x86": ("i686-linux-android", "i686-linux-android"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
}

ZLIB_BINARY_MANUALS = (
    "zlib-1.3.2/contrib/dotzlib/DotZLib.chm",
    "zlib-1.3.2/doc/crc-doc.1.0.pdf",
    "zlib-1.3.2/zlib.3.pdf",
)


def repack_zlib(source, original):
    """Keep all upstream code, tests and build inputs; omit three binary manuals."""
    manifest_path = source / "sources.json"
    manifest = json.loads(manifest_path.read_text())
    dep = next(item for item in manifest["dependencies"] if item["name"] == "zlib")
    original_sha = dep.get("upstreamSha256", dep["sha256"])
    if sha256(original) != original_sha:
        raise ValueError("Original zlib archive checksum mismatch")
    data = io.BytesIO()
    files = {}
    with tarfile.open(original) as upstream, tarfile.open(fileobj=data, mode="w", format=tarfile.USTAR_FORMAT) as output:
        members = sorted(upstream.getmembers(), key=lambda member: member.name)
        if not set(ZLIB_BINARY_MANUALS).issubset({member.name for member in members}):
            raise ValueError("Pinned zlib manual inventory changed")
        for member in members:
            if member.name in ZLIB_BINARY_MANUALS:
                continue
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError(f"Unsafe zlib entry: {member.name}")
            item = tarfile.TarInfo(member.name)
            item.size, item.mode, item.type = member.size, member.mode, member.type
            # USTAR defaults provide zero uid/gid/mtime and empty owner names.
            content = upstream.extractfile(member).read() if member.isfile() else None
            if content is not None:
                files[member.name] = hashlib.sha256(content).hexdigest()
            output.addfile(item, io.BytesIO(content) if content is not None else None)
    archive = lzma.compress(data.getvalue(), preset=9)
    if len(archive) > 512 * 1024:
        raise ValueError("Repacked zlib exceeds the repository file-size gate")
    name = f"zlib-{dep['version']}-source.tar.xz"
    (source / "archives" / name).write_bytes(archive)
    dep.update(archive=name, sha256=hashlib.sha256(archive).hexdigest(), upstreamSha256=original_sha,
               repack={"format": "USTAR, zero uid/gid/mtime, sorted names, XZ preset 9",
                       "excludedFiles": list(ZLIB_BINARY_MANUALS), "files": files})
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Repacked zlib: {len(archive)} bytes, {len(files)} unchanged files, SHA256={dep['sha256']}")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, env):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, check=True)


def verify_sources(source):
    manifest = json.loads((source / "sources.json").read_text())
    actual = {str(path.relative_to(source / "upstream")) for path in (source / "upstream").rglob("*") if path.is_file()}
    if actual != set(manifest["upstream"]["files"]):
        raise ValueError("Upstream file inventory does not match the source lock")
    for name, expected in manifest["upstream"]["files"].items():
        if sha256(source / "upstream" / name) != expected:
            raise ValueError(f"Upstream checksum mismatch: {name}")
    for dep in manifest["dependencies"]:
        if sha256(source / "archives" / dep["archive"]) != dep["sha256"]:
            raise ValueError(f"Dependency checksum mismatch: {dep['archive']}")
        if "repack" in dep:
            with tarfile.open(source / "archives" / dep["archive"]) as archive:
                files = {member.name: hashlib.sha256(archive.extractfile(member).read()).hexdigest()
                         for member in archive.getmembers() if member.isfile()}
            if files != dep["repack"]["files"] or any(name in files for name in dep["repack"]["excludedFiles"]):
                raise ValueError("Repacked dependency file inventory mismatch")
    integration = {str(path.relative_to(source)) for directory in ("patches", "bridge", "licenses") for path in (source / directory).rglob("*") if path.is_file()}
    if integration != set(manifest["integrationFiles"]):
        raise ValueError("Integration file inventory does not match the source lock")
    for name, expected in manifest["integrationFiles"].items():
        if sha256(source / name) != expected:
            raise ValueError(f"Integration checksum mismatch: {name}")
    return manifest


def unpack(source, manifest, work):
    for dep in manifest["dependencies"]:
        with tarfile.open(source / "archives" / dep["archive"]) as archive:
            # All checked source archives contain regular files/directories only.
            for member in archive.getmembers():
                target = (work / member.name).resolve()
                if not target.is_relative_to(work) or not (member.isfile() or member.isdir()):
                    raise ValueError(f"Unsafe archive entry: {member.name}")
            archive.extractall(work, filter="data")
    shutil.copytree(source / "upstream" / "nfq2", work / "nfq2")


def build_one(args, source, manifest, abi):
    work = (args.work / abi).resolve()
    # Remove only this task's private ABI output, never the source checkout.
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    unpack(source, manifest, work)
    env = os.environ.copy()
    for key in ("SDKROOT", "MACOSX_DEPLOYMENT_TARGET", "ARCHFLAGS", "CFLAGS", "CPPFLAGS", "LDFLAGS"):
        env.pop(key, None)
    cflags = ["-Os", "-fPIC", "-ffunction-sections", "-fdata-sections", f"-ffile-prefix-map={work}=/nfqws2"]
    ldflags = ["-Wl,--gc-sections", "-Wl,--build-id=sha1", "-pie"]
    if args.platform == "android":
        if not args.ndk:
            raise ValueError("Android builds require --ndk")
        properties = (args.ndk / "source.properties").read_text()
        if "Pkg.Revision = 29.0.14206865" not in properties:
            raise ValueError("nfqws2 requires the pinned NDK 29.0.14206865")
        host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
        tools = args.ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin"
        clang_target, configure_target = ABIS[abi]
        cc, ar, ranlib, strip = tools / f"{clang_target}{args.api}-clang", tools / "llvm-ar", tools / "llvm-ranlib", tools / "llvm-strip"
        build_cpu = "aarch64" if platform.machine() == "arm64" else platform.machine()
        configure_args = [f"--host={configure_target}", f"--build={build_cpu}-{'apple-darwin' if platform.system() == 'Darwin' else 'unknown-linux-gnu'}"]
        if abi == "armeabi-v7a":
            cflags.append("-mthumb")
        if abi in ("arm64-v8a", "x86_64"):
            ldflags.append("-Wl,-z,max-page-size=16384")
        run(["patch", "--batch", "-p1", "-i", source / "upstream/.github/workflows/libnetfilter_queue-android.patch"], work / "libnetfilter_queue-1.0.5", env)
        run(["patch", "--batch", "-p1", "-i", source / "patches/libnetfilter-ndk29.patch"], work / "libnetfilter_queue-1.0.5", env)
        run(["patch", "--batch", "-p1", "-i", source / "patches/android-vpn-protect.patch"], work / "nfq2", env)
        for path in (source / "bridge").glob("ripdpi_protect.*"):
            shutil.copy2(path, work / "nfq2" / path.name)
    else:
        if platform.system() != "Linux":
            raise ValueError("Host nfqws2 builds require Linux; use an isolated Linux container on macOS")
        cc, ar, ranlib, strip = env.get("CC", "cc"), env.get("AR", "ar"), "ranlib", "strip"
        configure_args = []
    stage = work / "stage"
    stage.mkdir()
    env.update(CC=str(cc), AR=str(ar), RANLIB=str(ranlib), CFLAGS=" ".join(cflags), LDFLAGS=" ".join(ldflags))
    env.update(PKG_CONFIG_PATH=str(stage / "lib/pkgconfig"), PKG_CONFIG_LIBDIR=str(stage / "lib/pkgconfig"))
    for name in ("libmnl", "libnfnetlink", "libnetfilter_queue"):
        dep = next(item for item in manifest["dependencies"] if item["name"] == name)
        directory = work / f"{name}-{dep['version']}"
        for prefix, lib in (("LIBMNL", "mnl"), ("LIBNFNETLINK", "nfnetlink")):
            env[f"{prefix}_CFLAGS"] = f"-I{stage / 'include'}"
            env[f"{prefix}_LIBS"] = f"-L{stage / 'lib'} -l{lib}"
        run(["./configure", f"--prefix={stage}", "--enable-static", "--disable-shared", "--disable-dependency-tracking", *configure_args], directory, env)
        run(["make", f"-j{args.jobs}", "install"], directory, env)
    zlib = next(item for item in manifest["dependencies"] if item["name"] == "zlib")
    zdir = work / f"zlib-{zlib['version']}"
    zenv = env.copy()
    if args.platform == "android":
        zenv.update(CHOST=configure_target, ARFLAGS="rcs")
    run(["./configure", "--static", f"--prefix={stage}"], zdir, zenv)
    run(["make", f"-j{args.jobs}", "install"], zdir, zenv)
    lua = next(item for item in manifest["dependencies"] if item["name"] == "lua")
    ldir = work / f"lua-{lua['version']}" / "src"
    objects = []
    for path in sorted(ldir.glob("*.c")):
        if path.name in ("lua.c", "luac.c"):
            continue
        obj = path.with_suffix(".o")
        run([cc, *cflags, "-DLUA_USE_LINUX", "-c", path, "-o", obj], ldir, env)
        objects.append(obj)
    lua_lib = stage / "lib/liblua.a"
    run([ar, "rcs", lua_lib, *objects], work, env)
    nfq = work / "nfq2"
    sources = sorted(nfq.glob("*.c")) + sorted((nfq / "crypto").glob("*.c"))
    if args.platform == "android":
        sources += sorted((nfq / "andr").glob("*.c"))
    binary = nfq / "nfqws2"
    commit = manifest["upstream"]["commit"]
    run([cc, *cflags, "-std=gnu99", "-DZAPRET_GH_VER=RIPDPI", f"-DZAPRET_GH_HASH={commit}", f"-I{ldir}", f"-I{stage / 'include'}", "-o", binary, *sources, lua_lib, stage / "lib/libnetfilter_queue.a", stage / "lib/libnfnetlink.a", stage / "lib/libmnl.a", stage / "lib/libz.a", "-lm", "-ldl", *( ["-llog"] if args.platform == "android" else []), *ldflags], nfq, env)
    run([strip, "--strip-unneeded", binary], nfq, env)
    dest = args.output / "bin" / abi
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, dest / "nfqws2")
    shutil.copytree(source / "upstream/lua", args.output / "zapret2/lua", dirs_exist_ok=True)
    shutil.copytree(source / "upstream/files", args.output / "zapret2/files", dirs_exist_ok=True)
    shutil.copytree(source / "licenses", args.output / "zapret2/licenses", dirs_exist_ok=True)
    shutil.copy2(source / "sources.json", args.output / "zapret2/sources.json")
    (dest / "nfqws2.build.json").write_text(json.dumps({"upstreamCommit": commit, "sourcesSha256": sha256(source / "sources.json"), "abi": abi, "api": args.api if args.platform == "android" else None, "ndk": "29.0.14206865" if args.platform == "android" else None, "luaVersion": lua["version"], "sha256": sha256(dest / "nfqws2")}, indent=2) + "\n")
    print(f"Built {abi}: {sha256(dest / 'nfqws2')}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("android", "host"), default="android")
    parser.add_argument("--abis", default=",".join(ABIS))
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--api", type=int, default=27)
    parser.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--work", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--repack-zlib", type=Path, metavar="ORIGINAL_ARCHIVE", help="Regenerate the locked source-only zlib archive from its verified official tar.gz")
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / "native/zapret2"
    if args.repack_zlib:
        repack_zlib(source, args.repack_zlib)
        return
    if not args.work or not args.output:
        parser.error("--work and --output are required for builds")
    args.work, args.output = args.work.resolve(), args.output.resolve()
    if args.work == source or source.is_relative_to(args.work) or args.work.is_relative_to(source):
        parser.error("--work must be outside the vendored source directory")
    if args.api < 27 or args.jobs < 1:
        parser.error("--api must be >=27 and --jobs must be >=1")
    manifest = verify_sources(source)
    abis = ["host"] if args.platform == "host" else args.abis.split(",")
    if args.platform == "android" and (not abis or any(abi not in ABIS for abi in abis) or len(set(abis)) != len(abis)):
        parser.error("--abis must contain unique supported Android ABIs")
    for abi in abis:
        build_one(args, source, manifest, abi)


if __name__ == "__main__":
    main()
