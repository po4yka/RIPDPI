#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import check_rust_api_snapshots as sut


def package(
    name: str,
    manifest_path: Path,
    dependencies: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "id": f"path+file:///{name}#0.1.0",
        "name": name,
        "manifest_path": str(manifest_path),
        "dependencies": dependencies or [],
    }


def dependency(name: str, kind: str | None = None) -> dict[str, object]:
    return {"name": name, "kind": kind}


class RustApiSnapshotTests(unittest.TestCase):
    def test_runtime_platform_snapshot_is_checked_only_on_linux(self) -> None:
        target = sut.SnapshotTarget(
            crate=sut.WorkspaceCrate("ripdpi-runtime-platform", Path("crates/runtime/Cargo.toml")),
            indegree=11,
            reason="high indegree",
        )

        self.assertFalse(sut.should_check_snapshot_on_host(target, platform="darwin"))
        self.assertTrue(sut.should_check_snapshot_on_host(target, platform="linux"))

    def test_workspace_crates_from_metadata_keeps_only_crates_root_members(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            crates_root = root / "crates"
            main_crate = package("ripdpi-main", crates_root / "ripdpi-main" / "Cargo.toml")
            tool_crate = package("ripdpi-tool", root / "tools" / "ripdpi-tool" / "Cargo.toml")
            metadata = {
                "workspace_members": [main_crate["id"], tool_crate["id"]],
                "packages": [main_crate, tool_crate],
            }

            crates = sut.workspace_crates_from_metadata(metadata, crates_root=crates_root)

            self.assertEqual(set(crates), {"ripdpi-main"})

    def test_reverse_dependencies_count_internal_production_dependencies_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            crates_root = root / "crates"
            shared = package("ripdpi-shared", crates_root / "ripdpi-shared" / "Cargo.toml")
            consumer = package(
                "ripdpi-consumer",
                crates_root / "ripdpi-consumer" / "Cargo.toml",
                dependencies=[dependency("ripdpi-shared"), dependency("ripdpi-test-support", "dev")],
            )
            test_support = package(
                "ripdpi-test-support",
                crates_root / "ripdpi-test-support" / "Cargo.toml",
                dependencies=[dependency("ripdpi-shared", "dev")],
            )
            metadata = {
                "workspace_members": [shared["id"], consumer["id"], test_support["id"]],
                "packages": [shared, consumer, test_support],
            }
            crates = sut.workspace_crates_from_metadata(metadata, crates_root=crates_root)

            reverse_dependencies = sut.internal_production_reverse_dependencies(metadata, crates)

            self.assertEqual(reverse_dependencies["ripdpi-shared"], {"ripdpi-consumer"})
            self.assertEqual(reverse_dependencies["ripdpi-test-support"], set())

    def test_snapshot_targets_include_high_indegree_and_explicit_boundary_crates(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            crates_root = root / "crates"
            shared = package("ripdpi-shared", crates_root / "ripdpi-shared" / "Cargo.toml")
            runtime_api = package("ripdpi-runtime-api", crates_root / "ripdpi-runtime-api" / "Cargo.toml")
            consumers = [
                package(
                    f"ripdpi-consumer-{index}",
                    crates_root / f"ripdpi-consumer-{index}" / "Cargo.toml",
                    dependencies=[dependency("ripdpi-shared")],
                )
                for index in range(3)
            ]
            metadata = {
                "workspace_members": [shared["id"], runtime_api["id"], *[consumer["id"] for consumer in consumers]],
                "packages": [shared, runtime_api, *consumers],
            }

            targets = sut.snapshot_targets(
                metadata,
                threshold=3,
                explicit_snapshot_crates=frozenset({"ripdpi-runtime-api"}),
                crates_root=crates_root,
            )

            reasons = {target.crate.name: target.reason for target in targets}
            self.assertEqual(set(reasons), {"ripdpi-shared", "ripdpi-runtime-api"})
            self.assertIn("indegree 3 >= 3", reasons["ripdpi-shared"])
            self.assertEqual(reasons["ripdpi-runtime-api"], "explicit API boundary")

    def test_cargo_public_api_omits_generated_impl_noise(self) -> None:
        crate = sut.WorkspaceCrate(
            name="ripdpi-example",
            manifest_path=sut.RUST_ROOT / "crates" / "ripdpi-example" / "Cargo.toml",
        )
        with patch.object(sut.subprocess, "run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout="pub struct ripdpi_example::Example\n", stderr="")

            output = sut.cargo_public_api(crate)

            command = run.call_args.args[0]
            self.assertEqual(output, "pub struct ripdpi_example::Example\n")
            self.assertIn("-sss", command)

    def test_normalize_public_api_canonicalizes_rustdoc_path_aliases(self) -> None:
        output = sut.normalize_public_api(
            "pub fn ripdpi_example::f(kind: core::io::error::ErrorKind)\n"
        )

        self.assertEqual(output, "pub fn ripdpi_example::f(kind: std::io::error::ErrorKind)\n")


if __name__ == "__main__":
    unittest.main()
