from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "ci/check_runtime_crate_boundaries.py"
SPEC = importlib.util.spec_from_file_location("check_runtime_crate_boundaries", SCRIPT_PATH)
assert SPEC is not None
check_runtime_crate_boundaries = importlib.util.module_from_spec(SPEC)
sys.modules["check_runtime_crate_boundaries"] = check_runtime_crate_boundaries
assert SPEC.loader is not None
SPEC.loader.exec_module(check_runtime_crate_boundaries)


def metadata_for(deps: dict[str, list[tuple[str, str | None]]]) -> dict[str, Any]:
    packages = []
    workspace_members = []
    for package_name, package_deps in deps.items():
        package_id = f"path+file:///repo/native/rust/crates/{package_name}#{package_name}@0.1.0"
        workspace_members.append(package_id)
        packages.append(
            {
                "id": package_id,
                "name": package_name,
                "dependencies": [
                    {"name": dependency_name, "kind": dependency_kind}
                    for dependency_name, dependency_kind in package_deps
                ],
            }
        )
    return {"packages": packages, "workspace_members": workspace_members}


def base_metadata() -> dict[str, Any]:
    return metadata_for(
        {
            "ripdpi-runtime-api": [
                ("ripdpi-failure-classifier", None),
                ("ripdpi-proxy-config", None),
            ],
            "ripdpi-runtime-platform": [
                ("ripdpi-capabilities", None),
                ("ripdpi-config", None),
                ("ripdpi-desync", None),
                ("ripdpi-ipfrag", None),
                ("ripdpi-native-protect", None),
                ("ripdpi-privileged-ops", None),
                ("ripdpi-root-helper-protocol", None),
            ],
            "ripdpi-proxy-runtime": [
                ("ripdpi-runtime-api", None),
                ("ripdpi-proxy-runtime-adapter", None),
            ],
            "ripdpi-proxy-runtime-adapter": [
                ("ripdpi-runtime-platform", None),
            ],
            "ripdpi-android": [
                ("ripdpi-proxy-runtime", None),
                ("ripdpi-runtime-api", None),
                ("ripdpi-runtime-platform", None),
            ],
            "ripdpi-cli": [
                ("ripdpi-proxy-runtime", None),
                ("ripdpi-runtime-api", None),
            ],
            "ripdpi-monitor-engine": [
                ("ripdpi-proxy-runtime", None),
                ("ripdpi-runtime-platform", None),
            ],
            "ripdpi-config": [],
            "ripdpi-desync": [],
            "ripdpi-failure-classifier": [],
            "ripdpi-packets": [],
            "ripdpi-proxy-config": [],
            "ripdpi-capabilities": [],
            "ripdpi-ipfrag": [],
            "ripdpi-native-protect": [],
            "ripdpi-privileged-ops": [],
            "ripdpi-root-helper-protocol": [],
        }
    )


def violation_pairs(metadata: dict[str, Any]) -> set[tuple[str, str]]:
    return {
        (violation.package, violation.dependency)
        for violation in check_runtime_crate_boundaries.collect_violations(metadata)
    }


def test_base_runtime_split_dependencies_pass() -> None:
    assert violation_pairs(base_metadata()) == set()


def test_runtime_api_rejects_platform_dependency() -> None:
    metadata = base_metadata()
    for package in metadata["packages"]:
        if package["name"] == "ripdpi-runtime-api":
            package["dependencies"].append({"name": "ripdpi-runtime-platform", "kind": None})

    assert ("ripdpi-runtime-api", "ripdpi-runtime-platform") in violation_pairs(metadata)


def test_proxy_runtime_requires_split_runtime_crates() -> None:
    metadata = base_metadata()
    for package in metadata["packages"]:
        if package["name"] == "ripdpi-proxy-runtime":
            package["dependencies"] = [
                dep for dep in package["dependencies"] if dep["name"] != "ripdpi-proxy-runtime-adapter"
            ]

    assert ("ripdpi-proxy-runtime", "ripdpi-proxy-runtime-adapter") in violation_pairs(metadata)


def test_proxy_runtime_rejects_direct_platform_dependency() -> None:
    metadata = base_metadata()
    for package in metadata["packages"]:
        if package["name"] == "ripdpi-proxy-runtime":
            package["dependencies"].append({"name": "ripdpi-runtime-platform", "kind": None})

    assert ("ripdpi-proxy-runtime", "ripdpi-runtime-platform") in violation_pairs(metadata)
