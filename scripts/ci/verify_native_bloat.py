#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGES = ("ripdpi-android", "ripdpi-tunnel-android")
HOST_TAGS = ("linux-x86_64", "darwin-arm64", "darwin-x86_64")
MIN_ANDROID_CMAKE_VERSION = (3, 28, 0)
CLANG_TARGETS = {
    "aarch64-linux-android": "aarch64-linux-android",
    "armv7-linux-androideabi": "armv7a-linux-androideabi",
    "i686-linux-android": "i686-linux-android",
    "x86_64-linux-android": "x86_64-linux-android",
}
APPLE_HOST_ENV_KEYS = (
    "SDKROOT",
    "MACOSX_DEPLOYMENT_TARGET",
    "IPHONEOS_DEPLOYMENT_TARGET",
    "TVOS_DEPLOYMENT_TARGET",
    "WATCHOS_DEPLOYMENT_TARGET",
    "XROS_DEPLOYMENT_TARGET",
    "ARCHFLAGS",
    "RC_ARCHS",
    "CMAKE_OSX_ARCHITECTURES",
    "CMAKE_OSX_SYSROOT",
    "CFLAGS",
    "CXXFLAGS",
    "CPPFLAGS",
    "LDFLAGS",
)


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


def resolve_ndk_dir(repo_root: Path, sdk_dir: Path) -> Path:
    gradle_properties = read_properties(repo_root / "gradle.properties")
    ndk_version = gradle_properties.get("ripdpi.nativeNdkVersion")
    if not ndk_version:
        raise ValueError("ripdpi.nativeNdkVersion is missing from gradle.properties")

    ndk_dir = sdk_dir / "ndk" / ndk_version
    if not ndk_dir.is_dir():
        raise ValueError(f"Android NDK directory not found: {ndk_dir}")

    return ndk_dir


def resolve_ndk_bin_dir(repo_root: Path, sdk_dir: Path) -> Path:
    ndk_dir = resolve_ndk_dir(repo_root, sdk_dir)
    toolchains_dir = ndk_dir / "toolchains" / "llvm" / "prebuilt"
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


def parse_cmake_version(output: str) -> tuple[int, int, int] | None:
    match = re.search(r"cmake version (\d+)\.(\d+)\.(\d+)", output)
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def read_cmake_version(cmake_path: Path) -> tuple[int, int, int] | None:
    result = subprocess.run(
        [str(cmake_path), "--version"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return parse_cmake_version(result.stdout)


def format_version(version: tuple[int, int, int]) -> str:
    return ".".join(str(part) for part in version)


def resolve_executable_path(value: str) -> Path | None:
    expanded = Path(value).expanduser()
    if expanded.is_file():
        return expanded.resolve()
    resolved = shutil.which(value)
    return Path(resolved).resolve() if resolved else None


def resolve_android_cmake(sdk_dir: Path) -> Path | None:
    explicit = os.environ.get("CMAKE")
    if explicit:
        cmake_path = resolve_executable_path(explicit)
        if cmake_path is None:
            raise ValueError(f"CMAKE points to a non-existent executable: {explicit}")
        version = read_cmake_version(cmake_path)
        if version is not None and version < MIN_ANDROID_CMAKE_VERSION:
            raise ValueError(
                "Android cargo-bloat requires CMake "
                f">= {format_version(MIN_ANDROID_CMAKE_VERSION)}; "
                f"CMAKE={cmake_path} reports {format_version(version)}.",
            )
        return cmake_path

    candidates: list[Path] = []
    path_cmake = shutil.which("cmake")
    if path_cmake:
        candidates.append(Path(path_cmake).resolve())
    candidates.extend(candidate.resolve() for candidate in sorted((sdk_dir / "cmake").glob("*/bin/cmake")))

    seen: set[Path] = set()
    versioned: list[tuple[tuple[int, int, int], Path]] = []
    for candidate in candidates:
        if candidate in seen or not candidate.is_file():
            continue
        seen.add(candidate)
        version = read_cmake_version(candidate)
        if version is not None:
            versioned.append((version, candidate))

    modern = [(version, path) for version, path in versioned if version >= MIN_ANDROID_CMAKE_VERSION]
    if modern:
        return max(modern, key=lambda item: item[0])[1]
    if versioned:
        found = ", ".join(f"{path}={format_version(version)}" for version, path in versioned)
        raise ValueError(
            "Android cargo-bloat requires CMake "
            f">= {format_version(MIN_ANDROID_CMAKE_VERSION)}; found only {found}.",
        )
    return None


def write_android_compiler_filter_wrapper(wrapper_path: Path, real_compiler: Path) -> Path:
    wrapper_path.parent.mkdir(parents=True, exist_ok=True)
    wrapper_path.write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        f"real_compiler={json.dumps(str(real_compiler))}\n"
        "filtered=()\n"
        "skip_next=false\n"
        "for arg in \"$@\"; do\n"
        "  if [[ \"$skip_next\" == \"true\" ]]; then\n"
        "    skip_next=false\n"
        "    continue\n"
        "  fi\n"
        "  case \"$arg\" in\n"
        "    -arch|-isysroot)\n"
        "      skip_next=true\n"
        "      ;;\n"
        "    -arch=*|-isysroot=*|-mmacosx-version-min=*|-miphoneos-version-min=*|-mios-simulator-version-min=*)\n"
        "      ;;\n"
        "    -Wl,-search_paths_first|-Wl,-headerpad_max_install_names|-Wl,-syslibroot,*|-Wl,-macosx_version_min,*|-Wl,-platform_version,*)\n"
        "      ;;\n"
        "    *)\n"
        "      filtered+=(\"$arg\")\n"
        "      ;;\n"
        "  esac\n"
        "done\n"
        "exec \"$real_compiler\" \"${filtered[@]}\"\n",
    )
    wrapper_path.chmod(0o755)
    return wrapper_path


def clean_boring_sys_build_cache(cargo_target_dir: Path, target: str) -> None:
    build_root = cargo_target_dir / target / "debug" / "build"
    if not build_root.is_dir():
        return

    for candidate in build_root.glob("boring-sys-*"):
        if candidate.is_dir():
            shutil.rmtree(candidate, ignore_errors=True)


def cargo_environment(repo_root: Path, target: str) -> dict[str, str]:
    sdk_dir = resolve_sdk_dir(repo_root)
    ndk_dir = resolve_ndk_dir(repo_root, sdk_dir)
    ndk_bin_dir = resolve_ndk_bin_dir(repo_root, sdk_dir)
    min_sdk = resolve_min_sdk(repo_root)
    clang_target = CLANG_TARGETS.get(target)
    if not clang_target:
        raise ValueError(f"Unsupported Android Rust target for cargo-bloat: {target}")

    linker = ndk_bin_dir / f"{clang_target}{min_sdk}-clang"
    cxx_linker = ndk_bin_dir / f"{clang_target}{min_sdk}-clang++"
    ar = ndk_bin_dir / "llvm-ar"
    if not linker.is_file():
        raise ValueError(f"Android linker not found: {linker}")
    if not cxx_linker.is_file():
        raise ValueError(f"Android C++ linker not found: {cxx_linker}")
    if not ar.is_file():
        raise ValueError(f"Android archiver not found: {ar}")

    target_env = target.replace("-", "_")
    env = os.environ.copy()
    for key in APPLE_HOST_ENV_KEYS:
        env.pop(key, None)

    android_cmake = resolve_android_cmake(sdk_dir)
    target_dir = (repo_root / "native/rust/target/cargo-bloat-ci").resolve()
    compiler_wrapper_dir = target_dir / "compiler-wrappers" / target
    linker_wrapper = write_android_compiler_filter_wrapper(compiler_wrapper_dir / linker.name, linker)
    cxx_linker_wrapper = write_android_compiler_filter_wrapper(compiler_wrapper_dir / cxx_linker.name, cxx_linker)
    clean_boring_sys_build_cache(target_dir, target)
    env.update(
        {
            f"CC_{target_env}": str(linker_wrapper),
            f"CXX_{target_env}": str(cxx_linker_wrapper),
            f"AR_{target_env}": str(ar),
            f"CARGO_TARGET_{target_env.upper()}_LINKER": str(linker_wrapper),
            f"CARGO_TARGET_{target_env.upper()}_AR": str(ar),
            "ANDROID_HOME": str(sdk_dir),
            "ANDROID_SDK_ROOT": str(sdk_dir),
            "ANDROID_NDK_HOME": str(ndk_dir),
            "CARGO_TARGET_DIR": str(target_dir),
            "BORING_BSSL_RUST_CPPLIB": env.get("BORING_BSSL_RUST_CPPLIB", "c++_static"),
        },
    )
    if android_cmake is not None:
        env["CMAKE"] = str(android_cmake)
    return env


def build_error_report(target: str, profile: str, error: Exception) -> dict:
    return {
        "target": target,
        "profile": profile,
        "packages": {},
        "failures": [str(error)],
        "analysisError": str(error),
    }


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


def diff_named_items(baseline_items: list[dict], current_items: list[dict]) -> list[dict]:
    baseline_by_name = {item["name"]: item for item in baseline_items}
    current_by_name = {item["name"]: item for item in current_items}
    names = sorted(set(baseline_by_name) | set(current_by_name))
    diff: list[dict] = []
    for name in names:
        baseline_item = baseline_by_name.get(name)
        current_item = current_by_name.get(name)
        baseline_size = int(baseline_item["size"]) if baseline_item else None
        current_size = int(current_item["size"]) if current_item else None
        if baseline_size is None:
            status = "new"
            delta = current_size
        elif current_size is None:
            status = "dropped"
            delta = -baseline_size
        else:
            status = "changed"
            delta = current_size - baseline_size
        diff.append(
            {
                "name": name,
                "crate": (current_item or baseline_item).get("crate"),
                "baselineSize": baseline_size,
                "currentSize": current_size,
                "deltaBytes": delta,
                "status": status,
            },
        )
    diff.sort(key=lambda item: abs(int(item["deltaBytes"] or 0)), reverse=True)
    return diff


def build_bloat_report(repo_root: Path, baseline: dict) -> dict:
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
    packages: dict[str, dict] = {}
    for package, baseline_report in baseline["packages"].items():
        current_report = analyze_package(repo_root, package, target, profile, top_functions, top_crates)
        baseline_text = int(baseline_report["textSectionSize"])
        current_text = int(current_report["textSectionSize"])
        allowed_text = baseline_text + max_text_section_growth_bytes
        text_status = "ok"
        if current_text > allowed_text:
            failures.append(
                f"{package} text section regression: baseline={baseline_text} actual={current_text} allowed={allowed_text}",
            )
            text_status = "regression"

        function_failures = compare_named_items(
            package,
            "functions",
            baseline_report["functions"],
            current_report["functions"],
            max_function_growth_bytes,
            max_new_function_size_bytes,
        )
        crate_failures = compare_named_items(
            package,
            "crates",
            baseline_report["crates"],
            current_report["crates"],
            max_crate_growth_bytes,
            max_new_crate_size_bytes,
        )
        failures.extend(function_failures)
        failures.extend(crate_failures)

        crate_growth = [item for item in diff_named_items(baseline_report["crates"], current_report["crates"]) if item["deltaBytes"]]
        function_growth = [
            item for item in diff_named_items(baseline_report["functions"], current_report["functions"]) if item["deltaBytes"]
        ]
        packages[package] = {
            "fileSize": {
                "baseline": int(baseline_report["fileSize"]),
                "current": int(current_report["fileSize"]),
                "deltaBytes": int(current_report["fileSize"]) - int(baseline_report["fileSize"]),
            },
            "textSection": {
                "baseline": baseline_text,
                "current": current_text,
                "deltaBytes": current_text - baseline_text,
                "allowed": allowed_text,
                "status": text_status,
            },
            "topCrateGrowth": crate_growth[:10],
            "topFunctionGrowth": function_growth[:10],
            "likelyDrivers": [item["name"] for item in crate_growth if int(item["deltaBytes"]) > 0][:5],
        }

    return {
        "target": target,
        "profile": profile,
        "limits": {
            "maxTextSectionGrowthBytes": max_text_section_growth_bytes,
            "maxFunctionGrowthBytes": max_function_growth_bytes,
            "maxCrateGrowthBytes": max_crate_growth_bytes,
            "maxNewFunctionSizeBytes": max_new_function_size_bytes,
            "maxNewCrateSizeBytes": max_new_crate_size_bytes,
        },
        "packages": packages,
        "failures": failures,
    }


def render_bloat_report_markdown(report: dict) -> str:
    lines = [
        "# Native Bloat Attribution Report",
        "",
        f"- target: `{report['target']}`",
        f"- profile: `{report['profile']}`",
    ]
    for package, package_report in report["packages"].items():
        file_size = package_report["fileSize"]
        text_section = package_report["textSection"]
        lines.extend(
            [
                "",
                f"## {package}",
                "",
                f"- file size: `{file_size['baseline']}` -> `{file_size['current']}` (`{file_size['deltaBytes']:+d}`)",
                f"- text section: `{text_section['baseline']}` -> `{text_section['current']}` (`{text_section['deltaBytes']:+d}`), allowed `{text_section['allowed']}`, status `{text_section['status']}`",
            ],
        )
        if package_report["likelyDrivers"]:
            lines.append(f"- likely crate drivers: `{', '.join(package_report['likelyDrivers'])}`")
        if package_report["topCrateGrowth"]:
            lines.extend(["", "### Top Crate Growth", "", "| Crate | Baseline | Current | Delta | Status |", "| --- | ---: | ---: | ---: | --- |"])
            for entry in package_report["topCrateGrowth"]:
                baseline_size = "-" if entry["baselineSize"] is None else str(entry["baselineSize"])
                current_size = "-" if entry["currentSize"] is None else str(entry["currentSize"])
                lines.append(
                    f"| {entry['name']} | {baseline_size} | {current_size} | {entry['deltaBytes']:+d} | {entry['status']} |",
                )
        if package_report["topFunctionGrowth"]:
            lines.extend(
                ["", "### Top Function Growth", "", "| Function | Crate | Baseline | Current | Delta | Status |", "| --- | --- | ---: | ---: | ---: | --- |"],
            )
            for entry in package_report["topFunctionGrowth"][:5]:
                baseline_size = "-" if entry["baselineSize"] is None else str(entry["baselineSize"])
                current_size = "-" if entry["currentSize"] is None else str(entry["currentSize"])
                crate_name = entry["crate"] or "-"
                lines.append(
                    f"| {entry['name']} | {crate_name} | {baseline_size} | {current_size} | {entry['deltaBytes']:+d} | {entry['status']} |",
                )
    if report["failures"]:
        lines.extend(["", "## Failures", ""])
        lines.extend(f"- {failure}" for failure in report["failures"])
    return "\n".join(lines) + "\n"


def verify(repo_root: Path, baseline: dict) -> None:
    failures = build_bloat_report(repo_root, baseline)["failures"]
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
        default=2097152,
        help="Allowed representative text-section growth when dumping a baseline.",
    )
    parser.add_argument(
        "--max-function-growth-bytes",
        type=int,
        default=65536,
        help="Allowed growth for a tracked top function when dumping a baseline.",
    )
    parser.add_argument(
        "--max-crate-growth-bytes",
        type=int,
        default=524288,
        help="Allowed growth for a tracked top crate when dumping a baseline.",
    )
    parser.add_argument(
        "--max-new-function-size-bytes",
        type=int,
        default=131072,
        help="Fail when a new top function exceeds this size.",
    )
    parser.add_argument(
        "--max-new-crate-size-bytes",
        type=int,
        default=524288,
        help="Fail when a new top crate exceeds this size.",
    )
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a current cargo-bloat baseline payload instead of verifying against the checked-in file.",
    )
    parser.add_argument("--report-json", help="Write a JSON bloat attribution report to this path.")
    parser.add_argument("--report-md", help="Write a Markdown bloat attribution report to this path.")
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

    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    try:
        report = build_bloat_report(repo_root, baseline)
    except Exception as exc:
        report = build_error_report(baseline["target"], baseline["profile"], exc)
        if args.report_json:
            Path(args.report_json).parent.mkdir(parents=True, exist_ok=True)
            Path(args.report_json).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if args.report_md:
            Path(args.report_md).parent.mkdir(parents=True, exist_ok=True)
            Path(args.report_md).write_text(render_bloat_report_markdown(report), encoding="utf-8")
        raise
    if args.report_json:
        Path(args.report_json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report_json).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.report_md:
        Path(args.report_md).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report_md).write_text(render_bloat_report_markdown(report), encoding="utf-8")
    if report["failures"]:
        raise ValueError("\n".join(report["failures"]))
    print(f"Verified cargo-bloat output against {baseline_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native bloat verification failed: {exc}", file=sys.stderr)
        raise
