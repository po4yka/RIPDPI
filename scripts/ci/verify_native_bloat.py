#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

PACKAGES = ("ripdpi-android", "ripdpi-tunnel-android")
HOST_TAGS = ("linux-x86_64", "darwin-arm64", "darwin-x86_64")
CLANG_TARGETS = {
    "aarch64-linux-android": "aarch64-linux-android",
    "armv7-linux-androideabi": "armv7a-linux-androideabi",
    "i686-linux-android": "i686-linux-android",
    "x86_64-linux-android": "x86_64-linux-android",
}


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def resolve_sdk_dir(repo_root: Path) -> Path:
    for env_key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(env_key)
        if value:
            return Path(value).expanduser().resolve()

    local_properties = read_properties(repo_root / "local.properties")
    sdk_dir = local_properties.get("sdk.dir")
    if sdk_dir:
        return Path(sdk_dir).expanduser().resolve()

    raise ValueError("Android SDK directory was not found. Set ANDROID_SDK_ROOT or define sdk.dir in local.properties.")


def resolve_ndk_bin_dir(repo_root: Path, sdk_dir: Path) -> Path:
    gradle_properties = read_properties(repo_root / "gradle.properties")
    ndk_version = gradle_properties.get("ripdpi.nativeNdkVersion")
    if not ndk_version:
        raise ValueError("ripdpi.nativeNdkVersion is missing from gradle.properties")

    toolchains_dir = sdk_dir / "ndk" / ndk_version / "toolchains" / "llvm" / "prebuilt"
    for host_tag in HOST_TAGS:
        bin_dir = toolchains_dir / host_tag / "bin"
        if bin_dir.is_dir():
            return bin_dir

    raise ValueError(f"Android NDK toolchain bin directory not found under {toolchains_dir}")


def resolve_min_sdk(repo_root: Path) -> int:
    gradle_properties = read_properties(repo_root / "gradle.properties")
    value = gradle_properties.get("ripdpi.minSdk")
    if not value:
        raise ValueError("ripdpi.minSdk is missing from gradle.properties")
    return int(value)


def cargo_environment(repo_root: Path, target: str) -> dict[str, str]:
    sdk_dir = resolve_sdk_dir(repo_root)
    ndk_bin_dir = resolve_ndk_bin_dir(repo_root, sdk_dir)
    min_sdk = resolve_min_sdk(repo_root)
    clang_target = CLANG_TARGETS.get(target)
    if not clang_target:
        raise ValueError(f"Unsupported Android Rust target for cargo-bloat: {target}")

    linker = ndk_bin_dir / f"{clang_target}{min_sdk}-clang"
    ar = ndk_bin_dir / "llvm-ar"
    if not linker.is_file():
        raise ValueError(f"Android linker not found: {linker}")
    if not ar.is_file():
        raise ValueError(f"Android archiver not found: {ar}")

    target_env = target.replace("-", "_")
    env = os.environ.copy()
    env.update(
        {
            f"CC_{target_env}": str(linker),
            f"AR_{target_env}": str(ar),
            f"CARGO_TARGET_{target_env.upper()}_LINKER": str(linker),
            f"CARGO_TARGET_{target_env.upper()}_AR": str(ar),
            "CARGO_TARGET_DIR": str((repo_root / "native/rust/target/cargo-bloat-ci").resolve()),
        },
    )
    return env


def parse_bloat_output(output: str) -> dict:
    for line in reversed(output.splitlines()):
        stripped = line.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            return json.loads(stripped)
    raise ValueError(f"cargo-bloat did not emit JSON output:\n{output}")


def run_cargo_bloat(repo_root: Path, package: str, target: str, profile: str, top_n: int, per_crate: bool) -> dict:
    command = [
        "cargo",
        "bloat",
        "-p",
        package,
        "--lib",
        "--profile",
        profile,
        "--target",
        target,
        "--message-format",
        "json",
        "-n",
        str(top_n),
    ]
    if per_crate:
        command.append("--crates")

    result = subprocess.run(
        command,
        cwd=(repo_root / "native/rust"),
        env=cargo_environment(repo_root, target),
        capture_output=True,
        text=True,
        check=False,
    )
    output = "\n".join(part for part in (result.stdout, result.stderr) if part)
    if result.returncode != 0:
        raise RuntimeError(f"cargo-bloat failed for {package}:\n{output}")
    return parse_bloat_output(output)


def analyze_package(repo_root: Path, package: str, target: str, profile: str, top_functions: int, top_crates: int) -> dict:
    function_report = run_cargo_bloat(repo_root, package, target, profile, top_functions, per_crate=False)
    crate_report = run_cargo_bloat(repo_root, package, target, profile, top_crates, per_crate=True)

    if function_report["file-size"] != crate_report["file-size"]:
        raise ValueError(f"cargo-bloat file-size mismatch for {package}")
    if function_report["text-section-size"] != crate_report["text-section-size"]:
        raise ValueError(f"cargo-bloat text-section-size mismatch for {package}")

    return {
        "fileSize": int(function_report["file-size"]),
        "textSectionSize": int(function_report["text-section-size"]),
        "functions": function_report["functions"],
        "crates": crate_report["crates"],
    }


def dump_current(
    repo_root: Path,
    target: str,
    profile: str,
    top_functions: int,
    top_crates: int,
    max_text_section_growth_bytes: int,
    max_function_growth_bytes: int,
    max_crate_growth_bytes: int,
    max_new_function_size_bytes: int,
    max_new_crate_size_bytes: int,
) -> str:
    payload = {
        "target": target,
        "profile": profile,
        "topFunctions": top_functions,
        "topCrates": top_crates,
        "maxTextSectionGrowthBytes": max_text_section_growth_bytes,
        "maxFunctionGrowthBytes": max_function_growth_bytes,
        "maxCrateGrowthBytes": max_crate_growth_bytes,
        "maxNewFunctionSizeBytes": max_new_function_size_bytes,
        "maxNewCrateSizeBytes": max_new_crate_size_bytes,
        "packages": {
            package: analyze_package(repo_root, package, target, profile, top_functions, top_crates)
            for package in PACKAGES
        },
    }
    return json.dumps(payload, indent=2) + "\n"


def compare_named_items(
    package: str,
    kind: str,
    baseline_items: list[dict],
    current_items: list[dict],
    max_growth_bytes: int,
    max_new_item_size_bytes: int,
) -> list[str]:
    failures: list[str] = []
    baseline_sizes = {item["name"]: int(item["size"]) for item in baseline_items}
    current_sizes = {item["name"]: int(item["size"]) for item in current_items}

    for name, baseline_size in baseline_sizes.items():
        actual_size = current_sizes.get(name)
        if actual_size is None:
            continue
        allowed = baseline_size + max_growth_bytes
        if actual_size > allowed:
            failures.append(
                f"{package} {kind} regression: {name} baseline={baseline_size} actual={actual_size} allowed={allowed}",
            )

    for name, actual_size in current_sizes.items():
        if name in baseline_sizes:
            continue
        if actual_size > max_new_item_size_bytes:
            failures.append(
                f"{package} new {kind[:-1]} in top set: {name} size={actual_size} exceeds={max_new_item_size_bytes}",
            )

    return failures


def verify(repo_root: Path, baseline: dict) -> None:
    target = baseline["target"]
    profile = baseline["profile"]
    top_functions = int(baseline["topFunctions"])
    top_crates = int(baseline["topCrates"])
    max_text_section_growth_bytes = int(baseline["maxTextSectionGrowthBytes"])
    max_function_growth_bytes = int(baseline["maxFunctionGrowthBytes"])
    max_crate_growth_bytes = int(baseline["maxCrateGrowthBytes"])
    max_new_function_size_bytes = int(baseline["maxNewFunctionSizeBytes"])
    max_new_crate_size_bytes = int(baseline["maxNewCrateSizeBytes"])

    failures: list[str] = []
    for package, baseline_report in baseline["packages"].items():
        current_report = analyze_package(repo_root, package, target, profile, top_functions, top_crates)
        baseline_text = int(baseline_report["textSectionSize"])
        current_text = int(current_report["textSectionSize"])
        allowed_text = baseline_text + max_text_section_growth_bytes
        if current_text > allowed_text:
            failures.append(
                f"{package} text section regression: baseline={baseline_text} actual={current_text} allowed={allowed_text}",
            )

        failures.extend(
            compare_named_items(
                package,
                "functions",
                baseline_report["functions"],
                current_report["functions"],
                max_function_growth_bytes,
                max_new_function_size_bytes,
            ),
        )
        failures.extend(
            compare_named_items(
                package,
                "crates",
                baseline_report["crates"],
                current_report["crates"],
                max_crate_growth_bytes,
                max_new_crate_size_bytes,
            ),
        )

    if failures:
        raise ValueError("\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify representative Android cargo-bloat output against a checked-in baseline.")
    parser.add_argument("--baseline", default="scripts/ci/native-bloat-baseline.json", help="Checked-in JSON baseline file.")
    parser.add_argument("--target", default="x86_64-linux-android", help="Representative Android Rust target triple.")
    parser.add_argument("--profile", default="android-jni", help="Cargo profile to analyze.")
    parser.add_argument("--top-functions", type=int, default=20, help="Number of top functions to record when dumping.")
    parser.add_argument("--top-crates", type=int, default=20, help="Number of top crates to record when dumping.")
    parser.add_argument(
        "--max-text-section-growth-bytes",
        type=int,
        default=131072,
        help="Allowed representative text-section growth when dumping a baseline.",
    )
    parser.add_argument(
        "--max-function-growth-bytes",
        type=int,
        default=4096,
        help="Allowed growth for a tracked top function when dumping a baseline.",
    )
    parser.add_argument(
        "--max-crate-growth-bytes",
        type=int,
        default=16384,
        help="Allowed growth for a tracked top crate when dumping a baseline.",
    )
    parser.add_argument(
        "--max-new-function-size-bytes",
        type=int,
        default=12288,
        help="Fail when a new top function exceeds this size.",
    )
    parser.add_argument(
        "--max-new-crate-size-bytes",
        type=int,
        default=65536,
        help="Fail when a new top crate exceeds this size.",
    )
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a current cargo-bloat baseline payload instead of verifying against the checked-in file.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    baseline_path = (repo_root / args.baseline).resolve()

    if args.dump_current:
        print(
            dump_current(
                repo_root,
                args.target,
                args.profile,
                args.top_functions,
                args.top_crates,
                args.max_text_section_growth_bytes,
                args.max_function_growth_bytes,
                args.max_crate_growth_bytes,
                args.max_new_function_size_bytes,
                args.max_new_crate_size_bytes,
            ),
            end="",
        )
        return 0

    if not baseline_path.is_file():
        raise ValueError(f"Native bloat baseline not found: {baseline_path}")

    verify(repo_root, json.loads(baseline_path.read_text(encoding="utf-8")))
    print(f"Verified cargo-bloat output against {baseline_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native bloat verification failed: {exc}", file=sys.stderr)
        raise
