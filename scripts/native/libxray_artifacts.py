#!/usr/bin/env python3
"""Content-bound provenance and ABI/API verification for the managed libXray AAR."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PATCHES = ROOT / "native/xray/patches"
BUDGET = 167772160
MACHINES = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def policy() -> tuple[dict, dict, dict]:
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if line and not line.startswith("#") and "=" in line)
    patches = json.loads((PATCHES / "manifest.json").read_text())
    if patches["schemaVersion"] != 1 or patches["libxray"] != versions["libxray"] or patches["xrayCore"] != versions["xray-core"]:
        raise ValueError("patch set does not match the pinned stable sources")
    expected = {"libxray-managed-runtime.patch", "xray-core-protect-errors.patch", "grpc-stdlib-trailer-prefix.patch"}
    if set(patches["patches"]) != expected:
        raise ValueError("mandatory native protection/ownership patches are missing")
    for name, expected_digest in patches["patches"].items():
        if digest(PATCHES / name) != expected_digest:
            raise ValueError(f"patch digest mismatch: {name}")
    return versions, properties, patches


def regular(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"regular non-symlink file required: {path.name}")


def inspect_elf(payload: bytes, abi: str) -> None:
    elf_class, machine = MACHINES[abi]
    if len(payload) < 64 or payload[:4] != b"\x7fELF" or payload[4:6] != bytes([elf_class, 1]):
        raise ValueError(f"invalid ELF format for {abi}")
    if struct.unpack_from("<H", payload, 18)[0] != machine:
        raise ValueError(f"ELF machine mismatch for {abi}")
    if elf_class == 2:
        phoff = struct.unpack_from("<Q", payload, 32)[0]
        phsize, count = struct.unpack_from("<HH", payload, 54)
        alignment_offset, alignment_format = 48, "<Q"
    else:
        phoff = struct.unpack_from("<I", payload, 28)[0]
        phsize, count = struct.unpack_from("<HH", payload, 42)
        alignment_offset, alignment_format = 28, "<I"
    if phsize < alignment_offset + struct.calcsize(alignment_format) or phoff + phsize * count > len(payload):
        raise ValueError(f"invalid ELF program headers for {abi}")
    loads = 0
    for index in range(count):
        offset = phoff + index * phsize
        if struct.unpack_from("<I", payload, offset)[0] == 1:
            loads += 1
            alignment = struct.unpack_from(alignment_format, payload, offset + alignment_offset)[0]
            if alignment < 16384 or alignment % 16384:
                raise ValueError(f"ELF LOAD is not 16 KiB aligned for {abi}")
    if not loads:
        raise ValueError(f"ELF has no LOAD segments for {abi}")


def inspect_api(classes: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(classes)) as jar:
        required = {"libXray/LibXray.class", "libXray/DialerController.class", "go/Seq.class"}
        if not required.issubset(jar.namelist()):
            raise ValueError("gomobile Java API is missing")
    with tempfile.TemporaryDirectory(prefix="ripdpi-libxray-api-") as directory:
        jar_path = Path(directory) / "classes.jar"
        jar_path.write_bytes(classes)
        javap = str(Path(os.environ["JAVA_HOME"]) / "bin/javap") if "JAVA_HOME" in os.environ else "javap"
        result = subprocess.run([javap, "-classpath", str(jar_path), "libXray.LibXray", "libXray.DialerController"],
                                capture_output=True, text=True, check=True, timeout=30)
    for signature in (
        "String newXrayRunFromJSONRequest(java.lang.String, java.lang.String, java.lang.String) throws java.lang.Exception;",
        "String runXrayFromJSON(java.lang.String);", "String stopXray();", "boolean getXrayState();",
        "String xrayVersion();", "void registerDialerController(libXray.DialerController);",
        "void registerListenerController(libXray.DialerController);", "void initDns(libXray.DialerController, java.lang.String);",
        "void resetDns();", "boolean protectFd(long);",
    ):
        if signature not in result.stdout:
            raise ValueError(f"gomobile API signature missing: {signature}")


def verify(directory: Path, abis: str | None = None, release: bool = False) -> dict:
    versions, properties, patches = policy()
    for name in ("libxray.aar", "libxray-artifact.json", "build-go.mod", "build-go.sum"):
        regular(directory / name)
    metadata = json.loads((directory / "libxray-artifact.json").read_text())
    fields = {"schemaVersion": 2, "channel": "stable", "libxray": versions["libxray"],
              "libxrayCommit": patches["libxrayCommit"], "xrayCore": versions["xray-core"],
              "xrayCoreModuleSum": patches["xrayCoreModuleSum"], "grpc": patches["grpc"],
              "grpcModuleSum": patches["grpcModuleSum"], "gomobile": versions["gomobile"],
              "ndkVersion": properties["ripdpi.nativeNdkVersion"], "minSdk": properties["ripdpi.minSdk"],
              "patchManifestSha256": digest(PATCHES / "manifest.json"),
              "buildRecipeSha256": digest(ROOT / "scripts/native/build-libxray.sh")}
    for key, value in fields.items():
        if metadata.get(key) != value:
            raise ValueError(f"artifact provenance mismatch: {key}")
    for name, key in (("libxray.aar", "aarSha256"), ("build-go.mod", "goModSha256"), ("build-go.sum", "goSumSha256")):
        if digest(directory / name) != metadata.get(key):
            raise ValueError(f"artifact content digest mismatch: {name}")
    if not re.fullmatch(r"go1\.[0-9]+(?:\.[0-9]+)?", metadata.get("goVersion", "")):
        raise ValueError("missing Go toolchain provenance")
    supported = set(properties["ripdpi.nativeAbis"].split(","))
    expected = set((abis or properties["ripdpi.nativeAbis"]).split(","))
    if not expected or not expected.issubset(supported) or (release and expected != supported):
        raise ValueError("invalid expected ABI set")
    aar = directory / "libxray.aar"
    if aar.stat().st_size > BUDGET:
        raise ValueError("AAR archive exceeds native payload budget")
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names) or any(n.startswith("/") or ".." in Path(n).parts for n in names):
            raise ValueError("unsafe or duplicate AAR entries")
        native = {name.split("/")[1]: name for name in names if re.fullmatch(r"jni/[^/]+/libgojni\.so", name)}
        if set(native) != set(metadata.get("abis", [])) or not expected.issubset(native) or not set(native).issubset(supported):
            raise ValueError("AAR ABI coverage disagrees with required/declared ABIs")
        if any(n.endswith(".so") and n not in native.values() for n in names):
            raise ValueError("unexpected native library in AAR")
        if sum(archive.getinfo(name).file_size for name in native.values()) > BUDGET:
            raise ValueError("native payload exceeds budget")
        for abi, name in native.items():
            inspect_elf(archive.read(name), abi)
        if archive.getinfo("classes.jar").file_size > 4 * 1024 * 1024:
            raise ValueError("unexpectedly large Java API")
        inspect_api(archive.read("classes.jar"))
    return metadata


def create(directory: Path, sources: Path, abis: str) -> None:
    versions, properties, patches = policy()
    for name in ("go.mod", "go.sum"):
        (directory / ("build-" + name)).write_bytes((sources / name).read_bytes())
    metadata = {"schemaVersion": 2, "channel": "stable", "libxray": versions["libxray"],
                "libxrayCommit": patches["libxrayCommit"], "xrayCore": versions["xray-core"],
                "xrayCoreModuleSum": patches["xrayCoreModuleSum"], "grpc": patches["grpc"],
                "grpcModuleSum": patches["grpcModuleSum"], "gomobile": versions["gomobile"],
                "ndkVersion": properties["ripdpi.nativeNdkVersion"], "minSdk": properties["ripdpi.minSdk"],
                "patchManifestSha256": digest(PATCHES / "manifest.json"), "abis": abis.split(","),
                "buildRecipeSha256": digest(ROOT / "scripts/native/build-libxray.sh"),
                "goVersion": subprocess.check_output(["go", "env", "GOVERSION"], cwd=sources, text=True).strip(),
                "aarSha256": digest(directory / "libxray.aar"), "goModSha256": digest(directory / "build-go.mod"),
                "goSumSha256": digest(directory / "build-go.sum")}
    (directory / "libxray-artifact.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--abis")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--create", type=Path, metavar="SOURCES")
    args = parser.parse_args()
    if args.create:
        create(args.directory, args.create, args.abis)
    metadata = verify(args.directory, args.abis, args.release)
    print(f"OK: verified patched libXray {metadata['libxray']} ABIs={','.join(metadata['abis'])} sha256={metadata['aarSha256']}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        raise SystemExit(f"FAIL: {error}") from error
