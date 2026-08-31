#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path

from check_native_hotspot_budgets import production_source


REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = Path("native/rust/crates")
ADAPTER_FILES = (
    Path("native/rust/crates/ripdpi-android-proxy-adapter/src/lib.rs"),
    Path("native/rust/crates/ripdpi-android-diagnostics-adapter/src/lib.rs"),
    Path("native/rust/crates/ripdpi-tunnel-android/src/session.rs"),
)
CONFIG_ROOT = Path("native/rust/crates/ripdpi-config/src")
CONFIG_PARSE_ROOT = CONFIG_ROOT / "parse"
CONFIG_MODEL_PATH = CONFIG_ROOT / "model" / "mod.rs"
RUNTIME_DECISION_PORTS_PATH = Path("native/rust/crates/ripdpi-runtime-decision-ports/src/lib.rs")
RUNTIME_DECISION_PORTS_CARGO_PATH = Path("native/rust/crates/ripdpi-runtime-decision-ports/Cargo.toml")
MONITOR_ENGINE_CARGO_PATH = Path("native/rust/crates/ripdpi-monitor-engine/Cargo.toml")
DIAGNOSTICS_RUNNER_ADAPTER_FILES = (
    Path("native/rust/crates/ripdpi-diagnostics-runner/src/connectivity/adapters.rs"),
    Path("native/rust/crates/ripdpi-diagnostics-runner/src/strategy/adapters.rs"),
)
PARSE_OWNED_FN_PREFIXES = ("parse_", "normalize_")
PARSE_OWNED_FN_NAMES = {"data_from_str", "file_or_inline_bytes"}

TOP_LEVEL_FN_RE = re.compile(
    r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\b",
    re.MULTILINE,
)
TOP_LEVEL_FORBIDDEN_ITEM_PATTERNS = {
    "static": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?static\b", re.MULTILINE),
    "const": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?const\b", re.MULTILINE),
    "struct": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\b", re.MULTILINE),
    "enum": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?enum\b", re.MULTILINE),
    "trait": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?trait\b", re.MULTILINE),
    "type": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?type\b", re.MULTILINE),
    "impl": re.compile(r"^\s*impl\b", re.MULTILINE),
    "extern": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:unsafe\s+)?extern\b", re.MULTILINE),
    "macro_rules": re.compile(r"^\s*macro_rules!\b", re.MULTILINE),
}
STARTUP_ENV_STRUCT_RE = re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\s+StartupEnv\b", re.MULTILINE)
STARTUP_ENV_IMPL_RE = re.compile(r"^\s*impl\s+StartupEnv\b", re.MULTILINE)
RUNTIME_DECISION_PORTS_FORBIDDEN_PATTERNS = {
    "broad module `adaptive`": re.compile(r"^\s*pub\s+mod\s+adaptive\b", re.MULTILINE),
    "broad module `direct_path_learning`": re.compile(r"^\s*pub\s+mod\s+direct_path_learning\b", re.MULTILINE),
    "broad module `policy`": re.compile(r"^\s*pub\s+mod\s+policy\b", re.MULTILINE),
    "adaptive morph-policy module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_adaptive::morph_policy(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "adaptive strategy-context module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_adaptive::strategy_context(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "policy engine module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_policy::runtime_policy(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "direct-path learning module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_policy::direct_path_learning(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "runtime policy engine type export": re.compile(r"\bRuntimePolicy\b"),
    "direct-path learning state export": re.compile(r"\bDirectPathLearningState\b"),
    "policy/adaptive helper export": re.compile(
        r"\b(?:"
        r"apply_udp_morph_policy_to_hints|apply_tcp_morph_policy_to_group|"
        r"tcp_morph_hint_family|udp_morph_hint_family|"
        r"direct_path_capability_for_route|merge_udp_hints_with_capability|network_scope_key|"
        r"classify_response_failure|response_requires_dns_tampering_evidence|"
        r"extract_host|extract_host_info|group_requires_payload|"
        r"is_tls_client_hello_payload|route_matches_payload"
        r")\b",
        re.MULTILINE,
    ),
}
RUNTIME_DECISION_PORTS_FORBIDDEN_DEP_RE = re.compile(
    r"^\s*(ripdpi-runtime-(?:adaptive|policy))\s*=",
    re.MULTILINE,
)
DIAGNOSTICS_LANE_BROAD_REEXPORT_RE = re.compile(
    r"^\s*pub\s+use\s+ripdpi_diagnostics_[A-Za-z0-9_]+::[A-Za-z0-9_:]+::\*\s*;",
    re.MULTILINE,
)
DIAGNOSTICS_LANE_BROAD_IMPORT_RE = re.compile(
    r"^\s*use\s+crate::(?:connectivity|strategy)::adapters::[A-Za-z0-9_]+::\*\s*;",
    re.MULTILINE,
)
MONITOR_ENGINE_CONCRETE_LANE_DEP_RE = re.compile(
    r"^\s*ripdpi-diagnostics-(?!contracts\b)[A-Za-z0-9-]+\s*=",
    re.MULTILINE,
)
ENTRYPOINT_CRATES = frozenset(
    {
        "ripdpi-amneziawg-android",
        "ripdpi-android",
        "ripdpi-bench",
        "ripdpi-cli",
        "ripdpi-relay-android",
        "ripdpi-root-helper",
        "ripdpi-tunnel-android",
        "ripdpi-warp-android",
    }
)
RUNTIME_BOUNDARY_CRATES = frozenset({"ripdpi-runtime-api", "ripdpi-runtime-decision-ports"})
RUNTIME_BOUNDARY_FORBIDDEN_DEPENDENCIES = frozenset(
    {
        "ripdpi-amneziawg-android",
        "ripdpi-android",
        "ripdpi-android-bridge-support",
        "ripdpi-android-diagnostics-adapter",
        "ripdpi-android-fetch-adapter",
        "ripdpi-android-platform-adapter",
        "ripdpi-android-proxy-adapter",
        "ripdpi-android-telemetry-adapter",
        "ripdpi-android-vpn-protect-adapter",
        "ripdpi-monitor-engine",
        "ripdpi-monitor-lane-adapter",
        "ripdpi-monitor-proxy-runtime",
        "ripdpi-proxy-runtime",
        "ripdpi-proxy-runtime-adapter",
        "ripdpi-proxy-runtime-desync-adapter",
        "ripdpi-relay-android",
        "ripdpi-runtime-adaptive",
        "ripdpi-runtime-platform",
        "ripdpi-runtime-policy",
        "ripdpi-runtime-services",
        "ripdpi-runtime-strategy",
        "ripdpi-tunnel-android",
        "ripdpi-tunnel-core",
        "ripdpi-tunnel-intercept",
        "ripdpi-warp-android",
    }
)
PROXY_RUNTIME_FORBIDDEN_DEPENDENCIES = frozenset(
    {
        "ripdpi-config",
        "ripdpi-desync",
        "ripdpi-desync-runtime",
        "ripdpi-dns-resolver",
        "ripdpi-failure-classifier",
        "ripdpi-packets",
        "ripdpi-proxy-config",
        "ripdpi-runtime-adaptive",
        "ripdpi-runtime-decision-ports",
        "ripdpi-runtime-platform",
        "ripdpi-runtime-policy",
        "ripdpi-runtime-services",
        "ripdpi-runtime-strategy",
        "ripdpi-session",
        "ripdpi-ws-bootstrap",
        "ripdpi-ws-tunnel",
    }
)
MONITOR_ENGINE_FORBIDDEN_DEPENDENCIES = frozenset(
    {
        "ripdpi-diagnostics-candidates",
        "ripdpi-diagnostics-classification",
        "ripdpi-diagnostics-dns",
        "ripdpi-diagnostics-fat-header",
        "ripdpi-diagnostics-http",
        "ripdpi-diagnostics-net",
        "ripdpi-diagnostics-protocols",
        "ripdpi-diagnostics-runner",
        "ripdpi-diagnostics-telegram",
        "ripdpi-diagnostics-tls",
        "ripdpi-diagnostics-transport",
        "ripdpi-monitor-proxy-runtime",
        "ripdpi-proxy-runtime",
    }
)
DIAGNOSTICS_BOUNDARY_CRATES = frozenset({"ripdpi-diagnostics-contracts", "ripdpi-diagnostics-transport"})
DIAGNOSTICS_UPWARD_DEPENDENCIES = frozenset(
    {
        "ripdpi-android-diagnostics-adapter",
        "ripdpi-diagnostics-runner",
        "ripdpi-monitor-adapter",
        "ripdpi-monitor-engine",
        "ripdpi-monitor-lane-adapter",
        "ripdpi-monitor-proxy-runtime",
    }
)
WS_TRANSPORT_PORT_CRATE = "ripdpi-ws-transport-port"
WS_TRANSPORT_PORT_CONSUMERS = frozenset(
    {
        "ripdpi-diagnostics-telegram",
        "ripdpi-ws-bootstrap",
        "ripdpi-ws-tunnel",
    }
)
RUNTIME_UPWARD_DEPENDENCY_PREFIXES = (
    "ripdpi-android",
    "ripdpi-monitor",
    "ripdpi-proxy-runtime",
    "ripdpi-relay",
    "ripdpi-tunnel",
    "ripdpi-warp",
)


# NATIVE_RUST.md §3 rule 2 ("JNI containment") and §5: only the 13 L8
# Android/JNI crates (enumerated in §6) may pull `jni`, `android-support`,
# `android_logger`, or an `ndk-*` crate. Every other crate must stay JNI-free.
L8_JNI_ALLOWED_CRATES = frozenset(
    {
        "ripdpi-android",
        "ripdpi-tunnel-android",
        "ripdpi-relay-android",
        "ripdpi-warp-android",
        "ripdpi-amneziawg-android",
        "android-support",
        "ripdpi-android-bridge-support",
        "ripdpi-android-proxy-adapter",
        "ripdpi-android-diagnostics-adapter",
        "ripdpi-android-fetch-adapter",
        "ripdpi-android-platform-adapter",
        "ripdpi-android-vpn-protect-adapter",
        "ripdpi-android-telemetry-adapter",
    }
)
ANDROID_JNI_DEPENDENCY_NAMES = frozenset({"jni", "android-support", "android_logger"})


@dataclass(frozen=True)
class Violation:
    path: str
    message: str


def read_production_source(path: Path) -> str:
    return production_source(path.read_text(encoding="utf-8"))


def read_toml(path: Path) -> dict:
    return tomllib.loads(path.read_text(encoding="utf-8"))


def crate_name(manifest: Path) -> str:
    package = read_toml(manifest).get("package", {})
    if isinstance(package, dict) and isinstance(package.get("name"), str):
        return str(package["name"])
    return manifest.parent.name


def dependency_names(table: object, workspace_crates: set[str]) -> set[str]:
    if not isinstance(table, dict):
        return set()
    return {name for name in table if name in workspace_crates}


def manifest_production_dependencies(manifest: Path, workspace_crates: set[str]) -> set[str]:
    data = read_toml(manifest)
    dependencies = dependency_names(data.get("dependencies"), workspace_crates)
    for target in data.get("target", {}).values():
        if isinstance(target, dict):
            dependencies.update(dependency_names(target.get("dependencies"), workspace_crates))
    return dependencies


def production_dependency_graph(repo_root: Path) -> tuple[dict[str, set[str]], dict[str, Path]]:
    manifests = sorted((repo_root / CRATES_ROOT).glob("*/Cargo.toml"))
    manifest_paths = {crate_name(manifest): manifest.relative_to(repo_root) for manifest in manifests}
    workspace_crates = set(manifest_paths)
    graph = {
        crate: manifest_production_dependencies(repo_root / relative_path, workspace_crates)
        for crate, relative_path in manifest_paths.items()
    }
    return graph, manifest_paths


def is_android_surface(crate: str) -> bool:
    return crate == "ripdpi-android" or crate.startswith("ripdpi-android-")


def is_runtime_crate(crate: str) -> bool:
    return crate.startswith("ripdpi-runtime-")


def is_diagnostics_crate(crate: str) -> bool:
    return crate.startswith("ripdpi-diagnostics-")


def is_diagnostics_implementation_dependency(crate: str) -> bool:
    return is_diagnostics_crate(crate) and crate not in DIAGNOSTICS_BOUNDARY_CRATES


def is_upward_runtime_dependency(crate: str) -> bool:
    return crate.startswith(RUNTIME_UPWARD_DEPENDENCY_PREFIXES)


def is_android_jni_dependency(name: str) -> bool:
    return name in ANDROID_JNI_DEPENDENCY_NAMES or name == "ndk" or name.startswith("ndk-")


def manifest_all_production_dependencies(manifest: Path) -> set[str]:
    """Every production dependency name (workspace and external) of one crate."""
    data = read_toml(manifest)
    names: set[str] = set()
    table = data.get("dependencies")
    if isinstance(table, dict):
        names.update(table)
    for target in data.get("target", {}).values():
        if isinstance(target, dict) and isinstance(target.get("dependencies"), dict):
            names.update(target["dependencies"])
    return names


def dependency_violation(
    manifest_paths: dict[str, Path],
    source: str,
    dependency: str,
    message: str,
) -> Violation:
    path = manifest_paths.get(source, CRATES_ROOT / source / "Cargo.toml")
    return Violation(
        path=path.as_posix(),
        message=f"{source} must not depend on {dependency}: {message}",
    )


def dependency_direction_violations(
    graph: dict[str, set[str]],
    manifest_paths: dict[str, Path],
) -> list[Violation]:
    violations: list[Violation] = []
    for source, dependencies in sorted(graph.items()):
        for dependency in sorted(dependencies):
            if dependency in ENTRYPOINT_CRATES:
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "entrypoint crates are dependency graph leaves",
                    )
                )
            if (
                dependency not in ENTRYPOINT_CRATES
                and is_android_surface(dependency)
                and not is_android_surface(source)
            ):
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "Android adapter/support crates must not leak into native core crates",
                    )
                )
            if is_android_surface(source) and dependency == "ripdpi-android":
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "Android adapters must point toward reusable crates, not the JNI root",
                    )
                )
            if source in RUNTIME_BOUNDARY_CRATES and dependency in RUNTIME_BOUNDARY_FORBIDDEN_DEPENDENCIES:
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "runtime boundary crates may expose ports but not depend on runtime implementations",
                    )
                )
            if source == "ripdpi-proxy-runtime" and dependency in PROXY_RUNTIME_FORBIDDEN_DEPENDENCIES:
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "proxy runtime must depend through its adapter and runtime API boundary",
                    )
                )
            if source == "ripdpi-monitor-engine" and dependency in MONITOR_ENGINE_FORBIDDEN_DEPENDENCIES:
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "monitor engine must depend through monitor adapters instead of concrete lanes",
                    )
                )
            if source in DIAGNOSTICS_BOUNDARY_CRATES and is_diagnostics_implementation_dependency(dependency):
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "diagnostics boundary crates must not depend on diagnostics implementation lanes",
                    )
                )
            if is_diagnostics_crate(source) and dependency in DIAGNOSTICS_UPWARD_DEPENDENCIES:
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "diagnostics crates must not point upward into monitor or Android adapters",
                    )
                )
            if is_runtime_crate(source) and is_upward_runtime_dependency(dependency):
                violations.append(
                    dependency_violation(
                        manifest_paths,
                        source,
                        dependency,
                        "runtime crates must not point upward into adapters, entrypoints, or composition hubs",
                    )
                )

    return violations


def ws_transport_layer_violations(
    graph: dict[str, set[str]],
    manifest_paths: dict[str, Path],
) -> list[Violation]:
    """Keep the Telegram WS contract below its L4/L6/L7 consumers."""
    violations: list[Violation] = []
    port_dependencies = graph.get(WS_TRANSPORT_PORT_CRATE, set())
    for dependency in sorted(port_dependencies):
        violations.append(
            dependency_violation(
                manifest_paths,
                WS_TRANSPORT_PORT_CRATE,
                dependency,
                "the L2 WS transport port must remain a dependency-free contract boundary",
            )
        )

    for consumer in sorted(WS_TRANSPORT_PORT_CONSUMERS):
        if consumer not in graph:
            continue
        dependencies = graph.get(consumer, set())
        if WS_TRANSPORT_PORT_CRATE not in dependencies:
            violations.append(
                dependency_violation(
                    manifest_paths,
                    consumer,
                    WS_TRANSPORT_PORT_CRATE,
                    "WS bootstrap, diagnostics, and the concrete tunnel must share the L2 port contract",
                )
            )
        if consumer != "ripdpi-ws-tunnel" and "ripdpi-ws-tunnel" in dependencies:
            violations.append(
                dependency_violation(
                    manifest_paths,
                    consumer,
                    "ripdpi-ws-tunnel",
                    "L4/L6 consumers must depend on the L2 WS transport port, not its L7 implementation",
                )
            )
    return violations


def jni_containment_violations(
    repo_root: Path,
    manifest_paths: dict[str, Path],
) -> list[Violation]:
    """NATIVE_RUST.md §3 rule 2 / §5: keep `jni` and friends out of non-L8 crates.

    Only the 13 L8 Android/JNI crates may carry a production dependency on
    `jni`, `android-support`, `android_logger`, or an `ndk-*` crate. The
    existing dependency-direction check only inspects workspace-crate edges, so
    this guards the *external* Android/JNI crates a lower crate could pull in
    directly.
    """
    violations: list[Violation] = []
    for crate, relative_path in sorted(manifest_paths.items()):
        if crate in L8_JNI_ALLOWED_CRATES:
            continue
        for dependency in sorted(manifest_all_production_dependencies(repo_root / relative_path)):
            if is_android_jni_dependency(dependency):
                violations.append(
                    Violation(
                        path=relative_path.as_posix(),
                        message=(
                            f"{crate} must not depend on `{dependency}`: only the 13 L8 "
                            "Android/JNI crates may pull jni / android-support / android_logger / "
                            "ndk-* (NATIVE_RUST.md §3 rule 2, §5)"
                        ),
                    )
                )
    return violations


def adapter_contract_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for item_name, pattern in TOP_LEVEL_FORBIDDEN_ITEM_PATTERNS.items():
        if pattern.search(source_text):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"adapter file defines forbidden top-level {item_name} item",
                )
            )

    for fn_name in TOP_LEVEL_FN_RE.findall(source_text):
        if not fn_name.endswith("_entry"):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"adapter file defines non-entry function `{fn_name}`",
                )
            )

    return violations


def is_parse_owned_symbol(fn_name: str) -> bool:
    return fn_name.startswith(PARSE_OWNED_FN_PREFIXES) or fn_name in PARSE_OWNED_FN_NAMES


def config_ownership_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []
    in_parse = relative_path.is_relative_to(CONFIG_PARSE_ROOT)

    for fn_name in TOP_LEVEL_FN_RE.findall(source_text):
        if is_parse_owned_symbol(fn_name) and not in_parse:
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"parse-owned function `{fn_name}` must live under {CONFIG_PARSE_ROOT.as_posix()}",
                )
            )

    if (STARTUP_ENV_STRUCT_RE.search(source_text) or STARTUP_ENV_IMPL_RE.search(source_text)) and not in_parse:
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"`StartupEnv` must live under {CONFIG_PARSE_ROOT.as_posix()}",
            )
        )

    return violations


def runtime_decision_ports_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for description, pattern in RUNTIME_DECISION_PORTS_FORBIDDEN_PATTERNS.items():
        if pattern.search(source_text):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"runtime decision ports must not expose {description}",
                )
            )

    return violations


def runtime_decision_ports_dependency_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for match in RUNTIME_DECISION_PORTS_FORBIDDEN_DEP_RE.finditer(source_text):
        dependency = match.group(1)
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"runtime decision ports must not depend on concrete engine crate `{dependency}`",
            )
        )

    return violations


def diagnostics_lane_adapter_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    if DIAGNOSTICS_LANE_BROAD_REEXPORT_RE.search(source_text):
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message="diagnostics lane adapters must re-export explicit symbols, not concrete crate globs",
            )
        )
    if DIAGNOSTICS_LANE_BROAD_IMPORT_RE.search(source_text):
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message="diagnostics runner callers must not depend on broad lane adapter globs",
            )
        )

    return violations


def monitor_engine_dependency_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []
    for match in MONITOR_ENGINE_CONCRETE_LANE_DEP_RE.finditer(source_text):
        dependency = match.group(0).split("=", maxsplit=1)[0].strip()
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"monitor-engine must not depend on concrete diagnostics lane crate `{dependency}`",
            )
        )
    return violations


def collect_violations(repo_root: Path) -> list[Violation]:
    violations: list[Violation] = []

    graph, manifest_paths = production_dependency_graph(repo_root)
    violations.extend(dependency_direction_violations(graph, manifest_paths))
    violations.extend(ws_transport_layer_violations(graph, manifest_paths))
    violations.extend(jni_containment_violations(repo_root, manifest_paths))

    for relative_path in ADAPTER_FILES:
        source_path = repo_root / relative_path
        violations.extend(adapter_contract_violations(relative_path, read_production_source(source_path)))

    for source_path in sorted((repo_root / CONFIG_ROOT).rglob("*.rs")):
        relative_path = source_path.relative_to(repo_root)
        violations.extend(config_ownership_violations(relative_path, read_production_source(source_path)))

    runtime_ports_path = repo_root / RUNTIME_DECISION_PORTS_PATH
    violations.extend(
        runtime_decision_ports_violations(RUNTIME_DECISION_PORTS_PATH, read_production_source(runtime_ports_path))
    )
    runtime_ports_cargo = repo_root / RUNTIME_DECISION_PORTS_CARGO_PATH
    violations.extend(
        runtime_decision_ports_dependency_violations(
            RUNTIME_DECISION_PORTS_CARGO_PATH,
            runtime_ports_cargo.read_text(encoding="utf-8"),
        )
    )

    for relative_path in DIAGNOSTICS_RUNNER_ADAPTER_FILES:
        source_path = repo_root / relative_path
        violations.extend(diagnostics_lane_adapter_violations(relative_path, read_production_source(source_path)))

    monitor_engine_cargo = repo_root / MONITOR_ENGINE_CARGO_PATH
    violations.extend(
        monitor_engine_dependency_violations(
            MONITOR_ENGINE_CARGO_PATH,
            monitor_engine_cargo.read_text(encoding="utf-8"),
        )
    )

    return violations


def format_summary(violations: list[Violation]) -> str:
    lines = ["Native architecture contracts", f"Violations: {len(violations)}"]
    for violation in violations:
        lines.append(f"  - {violation.path}: {violation.message}")
    return "\n".join(lines)


def main() -> int:
    violations = collect_violations(REPO_ROOT)
    print(format_summary(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native architecture contract verification failed: {exc}", file=sys.stderr)
        raise
