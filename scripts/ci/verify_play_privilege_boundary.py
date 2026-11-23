#!/usr/bin/env python3
"""Verify privilege-boundary evidence in a RIPDPI APK or Android App Bundle.

For bundletool output, check the AAB and pass extracted APKs individually with
--split. The verifier checks known binary, resource, and wire fingerprints.
Dependency checks and runtime tests cover behavior without stable fingerprints.
"""

from __future__ import annotations

import argparse
import re
import stat
import sys
import zipfile
from pathlib import Path
from typing import BinaryIO

# EI_CLASS and e_machine for the Android ABIs in the release contract.
ABI_ELF = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
EXTENDED_ASSETS = ("ripdpi-root-helper", "nfqws2")
MAPPING_ENTRY = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
DEX_MAGIC = re.compile(rb"dex\n[0-9]{3}\x00")
CLASS_MAPPING = re.compile(rb"^([^\s]+) -> ([^\s]+):$")
OWNED_PRIVILEGE_CLASS = re.compile(
    rb"(?<![\w./$])L?com[./]poyka[./]ripdpi[./](?:[\w]+[./$])*"
    rb"(?:RootDetector|(?:Runtime)?RootCommand(?:Runner|Result)|RootAccess(?:Checker|Result|State)\w*"
    rb"|RootModeStrategies\w*|RootHelper(?:Manager|Ipc|Endpoint|State)\w*|NfqwsBackend\w*)(?![\w])"
)
NFQWS_EXECUTABLE_NAME = (
    re.compile(rb"(?<![a-z0-9_])nfqws2(?![a-z0-9_])"),
    re.compile(rb"(?<![a-z0-9_]\x00)n\x00f\x00q\x00w\x00s\x002\x00(?![a-z0-9_]\x00)"),
)

FINGERPRINTS = {
    "privileged executable": ("ripdpi-root-helper", "ripdpi_root_helper_protocol"),
    "root request": (
        "/system/bin/su",
        "/system/xbin/su",
        "android.permission.ACCESS_SUPERUSER",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
    ),
    "root detection": ("/data/adb/", "com.topjohnwu.magisk", "rootArtifacts", "rootArtifact="),
    "root settings": (
        "root_mode_", "rootModeEnabled", "rootHelperSocketPath", "root_helper_socket_path",
        "root_helper_available", "rooted_production", "exact_emitter_requires_root",
        "diagnostics_strategy_emitter_rooted",
    ),
    "privileged IPC": ("v4/probe_capabilities", "v4/send_raw_ip_packet", "v4/send_fake_rst", "v4/start_nfqws2"),
    "privileged Lua": ("rawsend_dissect_ipfrag",),
    "root disclosure": (
        "root mode", "root-mode strategies", "root access", "rooted production", "rooted devices",
        "root-modus", "root-zugriff", "modo root", "acceso root", "acesso root", "mode root", "accès root",
        "root-режим", "root-доступ", "root 模式", "root 权限", "रूट मोड", "रूट एक्सेस",
        "حالت روت", "دسترسی روت", "وضع الجذر", "صلاحية الجذر",
    ),
}
ENCODED_FINGERPRINTS = {
    reason: tuple(marker.lower().encode(encoding).lower() for marker in markers for encoding in ("utf-8", "utf-16-le"))
    for reason, markers in FINGERPRINTS.items()
}
OVERLAP = max(256, max(len(needle) for needles in ENCODED_FINGERPRINTS.values() for needle in needles))


class InvalidArtifact(ValueError):
    """The verifier cannot establish that the input has the expected layout."""


def archive_files(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    files: dict[str, zipfile.ZipInfo] = {}
    seen: set[str] = set()
    for entry in archive.infolist():
        name = entry.filename.rstrip("/")
        if (
            entry.orig_filename != entry.filename
            or any(part in ("", ".", "..") for part in name.split("/"))
            or "\\" in name
            or stat.S_ISLNK(entry.external_attr >> 16)
        ):
            raise InvalidArtifact("invalid member path or symbolic link")
        if name in seen:
            raise InvalidArtifact("duplicate member path")
        seen.add(name)
        if not entry.is_dir():
            files[name] = entry
    return files


def package_members(
    archive: zipfile.ZipFile, bundle: bool, split: bool
) -> tuple[dict[str, zipfile.ZipInfo], set[str]]:
    files = archive_files(archive)
    manifest = "base/manifest/AndroidManifest.xml" if bundle else "AndroidManifest.xml"
    dex = "base/dex/classes.dex" if bundle else "classes.dex"
    resources = "base/resources.pb" if bundle else "resources.arsc"
    required = {manifest}
    if not split:
        required.update((dex, resources))
    if bundle:
        required.add("BundleConfig.pb")
    if any(name not in files or not files[name].file_size for name in required):
        raise InvalidArtifact("missing manifest, compiled resources, DEX, or bundle configuration")
    if not split:
        with archive.open(dex) as stream:
            if not DEX_MAGIC.fullmatch(stream.read(8)):
                raise InvalidArtifact("invalid DEX header")

    prefix = "base/lib/" if bundle else "lib/"
    abis = set()
    for abi in ABI_ELF:
        library = f"{prefix}{abi}/libripdpi.so"
        if library in files:
            with archive.open(library) as stream:
                if not is_executable_for(stream, abi):
                    raise InvalidArtifact("invalid RIPDPI native library header")
            abis.add(abi)
    if not split and not abis:
        raise InvalidArtifact("no RIPDPI native library for a known ABI")
    return files, abis


def is_extended_path(name: str, bundle: bool) -> bool:
    parts = name.lower().split("/")
    return (
        parts[:2] == ["assets", "zapret2"]
        or (bundle and parts[1:3] == ["assets", "zapret2"])
        or any(re.match(r"^(?:lib)?(?:ripdpi[-_]root[-_]helper|nfqws2)(?:$|[._-])", part) for part in parts)
    )


def content_findings(stream: BinaryIO) -> set[str]:
    findings: set[str] = set()
    previous = b""
    while chunk := stream.read(1024 * 1024):
        window = previous + chunk
        folded = window.lower()
        for reason, needles in ENCODED_FINGERPRINTS.items():
            if reason not in findings and any(needle in folded for needle in needles):
                findings.add(reason)
        if "privileged executable" not in findings and any(pattern.search(folded) for pattern in NFQWS_EXECUTABLE_NAME):
            findings.add("privileged executable")
        if "app privilege class" not in findings and OWNED_PRIVILEGE_CLASS.search(window):
            findings.add("app privilege class")
        previous = window[-OVERLAP:]
    return findings


def mapping_has_privilege(stream: BinaryIO) -> bool:
    seen_class = False
    removed = False
    privilege = False
    for line in stream:
        line = line.rstrip()
        if match := CLASS_MAPPING.fullmatch(line):
            seen_class = True
            removed = match[2].startswith(b"R8$$REMOVED$$CLASS$$")
            privilege |= not removed and bool(OWNED_PRIVILEGE_CLASS.search(match[1]))
        elif not removed and line.startswith((b" ", b"\t")):
            privilege |= bool(OWNED_PRIVILEGE_CLASS.search(line))
    if not seen_class:
        raise InvalidArtifact("R8 mapping has no class mappings")
    return privilege


def is_executable_for(stream: BinaryIO, abi: str) -> bool:
    header = stream.read(64)
    elf_class, machine = ABI_ELF[abi]
    return (
        len(header) >= (64 if elf_class == 2 else 52)
        and header[:7] == b"\x7fELF" + bytes((elf_class, 1, 1))
        and int.from_bytes(header[16:18], "little") in (2, 3)  # ET_EXEC or ET_DYN (PIE)
        and int.from_bytes(header[18:20], "little") == machine
    )


def verify(package: Path, edition: str, mapping: Path | None = None, split: bool = False) -> list[str]:
    suffix = package.suffix.lower()
    if suffix not in (".apk", ".aab"):
        raise InvalidArtifact("expected an APK or AAB. Extract APK sets with bundletool first")
    bundle = suffix == ".aab"
    if split and (bundle or edition != "play"):
        raise InvalidArtifact("--split supports individual Play APK splits")
    if mapping is not None and edition != "play":
        raise InvalidArtifact("--mapping supports Play inspection")

    failures = []
    with zipfile.ZipFile(package) as archive:
        files, abis = package_members(archive, bundle, split)
        if edition != "play":
            prefix = "base/" if bundle else ""
            for abi in sorted(abis):
                for asset in EXTENDED_ASSETS:
                    name = f"{prefix}assets/bin/{abi}/{asset}"
                    if name not in files:
                        failures.append(f"missing extended executable for {abi}: {asset}")
                        continue
                    with archive.open(files[name]) as stream:
                        if not is_executable_for(stream, abi):
                            failures.append(f"invalid extended executable for {abi}: {asset}")
                        # Read through EOF so ZipFile validates the asset CRC.
                        while stream.read(1024 * 1024):
                            pass
            return failures

        for name, member in files.items():
            if is_extended_path(name, bundle):
                failures.append(f"extended entry {name!r}")
            if Path(name).suffix.lower() in (".apk", ".aab", ".apks"):
                failures.append(f"nested Android package {name!r}")
            with archive.open(member) as stream:
                if name == MAPPING_ENTRY:
                    if mapping_has_privilege(stream):
                        failures.append("embedded R8 mapping contains app privilege code")
                else:
                    markers = content_findings(stream)
                    if markers:
                        failures.append(f"{name!r} contains: {', '.join(sorted(markers))}")

    if mapping is not None:
        with mapping.open("rb") as stream:
            if mapping_has_privilege(stream):
                failures.append("R8 mapping contains app privilege code")
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--edition", choices=("play", "github", "fdroid"), required=True)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--split", action="store_true", help="Inspect a single Play APK split in addition to its AAB")
    args = parser.parse_args(argv)
    try:
        failures = verify(args.package, args.edition, args.mapping, args.split)
    except InvalidArtifact as exc:
        print(f"Cannot verify package: {exc}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, ValueError, zipfile.BadZipFile) as exc:
        print(f"Cannot read or verify package ({type(exc).__name__})", file=sys.stderr)
        return 1
    for failure in failures:
        print(failure, file=sys.stderr)
    if failures:
        return 1
    print(f"Verified {args.edition} privilege boundary for {args.package.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
