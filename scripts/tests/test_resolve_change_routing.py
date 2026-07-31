from __future__ import annotations

import unittest
from pathlib import Path

from scripts.ci.resolve_change_routing import (
    ROUTE_ANDROID,
    ROUTE_DOCUMENTATION,
    ROUTE_FIXTURES,
    ROUTE_FULL,
    ROUTE_RELEASE_BUILD_LOGIC,
    ROUTE_RUST_NATIVE,
    ROUTE_WORKFLOW,
    classify_change_paths,
    is_documentation_only,
    routing_outputs,
)


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


class ResolveChangeRoutingTest(unittest.TestCase):
    def test_docs_and_readmes_use_fast_route(self) -> None:
        self.assertTrue(
            is_documentation_only(
                ["docs/contributor/build-performance.md", "README-ru.md"]
            )
        )
        self.assertEqual(
            ROUTE_DOCUMENTATION,
            classify_change_paths(
                ["docs/contributor/build-performance.md", "README-ru.md"]
            ),
        )

    def test_any_executable_or_ci_path_uses_full_route(self) -> None:
        self.assertFalse(
            is_documentation_only(["docs/guide.md", ".github/workflows/ci.yml"])
        )

    def test_empty_change_set_uses_full_route(self) -> None:
        self.assertFalse(is_documentation_only([]))
        self.assertEqual(ROUTE_FULL, classify_change_paths([]))

    def test_known_single_domain_changes_use_their_targeted_route(self) -> None:
        cases = {
            ROUTE_ANDROID: [
                "app/src/main/kotlin/com/poyka/ripdpi/MainActivity.kt",
                "core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnService.kt",
            ],
            ROUTE_RUST_NATIVE: ["native/rust/crates/ripdpi-packets/src/lib.rs"],
            ROUTE_RELEASE_BUILD_LOGIC: ["build-logic/convention/build.gradle.kts"],
            ROUTE_FIXTURES: ["contract-fixtures/tunnel_config_fields.json"],
            ROUTE_WORKFLOW: [".github/workflows/codeql.yml"],
        }
        for expected, paths in cases.items():
            with self.subTest(route=expected):
                self.assertEqual(expected, classify_change_paths(paths))

    def test_release_workflows_are_release_build_logic_not_workflow_only(self) -> None:
        for path in (
            ".github/workflows/release.yml",
            ".github/workflows/dns-ipv6-killswitch-evidence.yml",
            "scripts/ci/resolve_change_routing.py",
        ):
            with self.subTest(path=path):
                self.assertEqual(
                    ROUTE_RELEASE_BUILD_LOGIC, classify_change_paths([path])
                )

    def test_fixture_workflows_use_fixture_route_and_full_ci(self) -> None:
        for path in (
            ".github/workflows/fleet-fixtures.yml",
            ".github/workflows/phase16-matrix.yml",
        ):
            with self.subTest(path=path):
                self.assertEqual(ROUTE_FIXTURES, classify_change_paths([path]))
                self.assertEqual("true", routing_outputs([path])["run_full_ci"])

    def test_unclassified_workflows_fail_closed_to_full_ci(self) -> None:
        self.assertEqual(
            ROUTE_FULL,
            classify_change_paths([".github/workflows/fuzz-nightly.yml"]),
        )

    def test_android_build_contract_and_proto_changes_use_full_ci_route(self) -> None:
        for path in (
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "core/data/model/src/main/proto/app_settings.proto",
        ):
            with self.subTest(path=path):
                self.assertEqual(
                    ROUTE_RELEASE_BUILD_LOGIC, classify_change_paths([path])
                )

    def test_unknown_mixed_and_unsafe_paths_fail_closed_to_full_ci(self) -> None:
        cases = (
            ["scripts/ci/unclassified_new_check.py"],
            ["app/src/main/AndroidManifest.xml", "native/rust/Cargo.toml"],
            ["docs/contributor/build-performance.md", ".github/workflows/ci.yml"],
            ["../outside-repository-file"],
            ["/tmp/outside-repository-file"],
        )
        for paths in cases:
            with self.subTest(paths=paths):
                self.assertEqual(ROUTE_FULL, classify_change_paths(paths))

    def test_output_switches_preserve_full_ci_for_release_fixture_and_unknown_routes(
        self,
    ) -> None:
        for paths in (
            ["build.gradle.kts"],
            ["contract-fixtures/tunnel_config_fields.json"],
            ["scripts/ci/unclassified_new_check.py"],
        ):
            with self.subTest(paths=paths):
                self.assertEqual("true", routing_outputs(paths)["run_full_ci"])

    def test_targeted_switches_are_exclusive(self) -> None:
        outputs = routing_outputs(["native/rust/Cargo.toml"])
        self.assertEqual("true", outputs["run_rust_native_ci"])
        self.assertEqual("true", outputs["run_full_ci"])
        self.assertEqual("false", outputs["run_android_ci"])

    def test_pr_labels_do_not_trigger_the_main_ci_workflow(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("types: [opened, synchronize, reopened]", source)
        self.assertNotIn("types: [opened, synchronize, reopened, labeled]", source)
        self.assertNotIn("github.event.action == 'labeled'", source)

    def test_workflow_collects_push_paths_and_wires_targeted_gates(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("PUSH_BEFORE_SHA: ${{ github.event.before }}", source)
        self.assertIn(
            'git diff --name-only "$PUSH_BEFORE_SHA" "$PUSH_HEAD_SHA"', source
        )
        self.assertIn("workflow-only-contracts:", source)
        self.assertIn("fixture-contracts:", source)
        self.assertIn("actionlint .github/workflows/*.yml", source)
        self.assertIn("pinact run --fix=false --no-api", source)
        self.assertIn("run_android_ci: ${{ steps.resolve.outputs.run_android_ci }}", source)
        self.assertIn("run_rust_native_ci: ${{ steps.resolve.outputs.run_rust_native_ci }}", source)


if __name__ == "__main__":
    unittest.main()
