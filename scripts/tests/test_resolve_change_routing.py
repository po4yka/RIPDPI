from __future__ import annotations

import re
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


def ci_job_source(name: str) -> str:
    source = CI_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing {name} CI job")
    return match.group(0)


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
            ROUTE_FIXTURES: ["contract-fixtures/phase16_lab_matrix.json"],
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

    def test_fixture_workflows_use_fixture_contract_route(self) -> None:
        for path in (
            ".github/workflows/fleet-fixtures.yml",
            ".github/workflows/phase16-matrix.yml",
        ):
            with self.subTest(path=path):
                self.assertEqual(ROUTE_FIXTURES, classify_change_paths([path]))
                outputs = routing_outputs([path])
                self.assertEqual("false", outputs["run_full_ci"])
                self.assertEqual("true", outputs["run_fixtures_ci"])

    def test_unclassified_workflows_fail_closed_to_full_ci(self) -> None:
        for path in (
            ".github/workflows/fuzz-nightly.yml",
            ".github/dependabot.yml",
            ".github/labeler.yml",
        ):
            with self.subTest(path=path):
                outputs = routing_outputs([path])
                self.assertEqual("true", outputs["run_full_ci"])
                self.assertEqual("false", outputs["run_fixtures_ci"])

    def test_unowned_fixture_and_golden_trees_fail_closed_to_full_ci(self) -> None:
        for path in (
            "contract-fixtures/tunnel_config_fields.json",
            "contract-fixtures/hysteria2/v2/handshake.bin",
            "contract-fixtures/vless/v1.260206.0/mux/yamux/frame.bin",
            "core/diagnostics/src/test/resources/golden/report.json",
            "native/rust/crates/ripdpi-desync/tests/goldens/packet.bin",
            "app/src/androidTest/assets/fixtures/device.json",
        ):
            with self.subTest(path=path):
                outputs = routing_outputs([path])
                self.assertEqual("true", outputs["run_full_ci"])
                self.assertEqual("false", outputs["run_fixtures_ci"])

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

    def test_output_switches_preserve_full_ci_for_release_and_unknown_routes(
        self,
    ) -> None:
        for paths in (
            ["build.gradle.kts"],
            ["scripts/ci/unclassified_new_check.py"],
        ):
            with self.subTest(paths=paths):
                self.assertEqual("true", routing_outputs(paths)["run_full_ci"])

    def test_workflow_and_fixture_routes_skip_full_ci_for_dedicated_gates(self) -> None:
        cases = (
            (".github/workflows/codeql.yml", "run_workflow_ci"),
            ("contract-fixtures/phase16_lab_matrix.json", "run_fixtures_ci"),
        )
        for path, gate in cases:
            with self.subTest(path=path):
                outputs = routing_outputs([path])
                self.assertEqual("false", outputs["run_full_ci"])
                self.assertEqual("true", outputs[gate])

    def test_targeted_switches_are_exclusive(self) -> None:
        outputs = routing_outputs(["native/rust/Cargo.toml"])
        self.assertEqual("true", outputs["run_rust_native_ci"])
        self.assertEqual("true", outputs["run_full_ci"])
        self.assertEqual("false", outputs["run_android_ci"])

    def test_coverage_switches_follow_source_ownership(self) -> None:
        cases = {
            "app/src/main/kotlin/com/poyka/ripdpi/MainActivity.kt": ("true", "false"),
            "native/rust/crates/ripdpi-packets/src/lib.rs": ("false", "true"),
            "build-logic/convention/build.gradle.kts": ("true", "true"),
            "scripts/ci/unclassified_new_check.py": ("true", "true"),
            "contract-fixtures/tunnel_config_fields.json": ("true", "true"),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                outputs = routing_outputs([path])
                self.assertEqual(expected[0], outputs["run_kotlin_coverage"])
                self.assertEqual(expected[1], outputs["run_rust_coverage"])

    def test_pr_labels_do_not_trigger_the_main_ci_workflow(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("types: [opened, synchronize, reopened]", source)
        self.assertNotIn("types: [opened, synchronize, reopened, labeled]", source)
        self.assertNotIn("github.event.action == 'labeled'", source)

    def test_manual_dispatch_stays_within_github_input_limit(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        dispatch = re.search(
            r"(?ms)^  workflow_dispatch:\n    inputs:\n(.*?)(?=^  schedule:)", source
        )
        self.assertIsNotNone(dispatch)
        assert dispatch is not None
        input_names = re.findall(r"(?m)^      ([a-z0-9_]+):$", dispatch.group(1))

        self.assertLessEqual(len(input_names), 10)
        self.assertIn("specialized_lanes", input_names)
        for lane in (
            "criterion_baseline",
            "phase0_baseline",
            "android_journeys",
            "android_relay_smoke",
        ):
            self.assertIn(
                f"contains(github.event.inputs.specialized_lanes, '{lane}')",
                source,
            )

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
        self.assertIn("run_kotlin_coverage: ${{ steps.resolve.outputs.run_kotlin_coverage }}", source)
        self.assertIn("run_rust_coverage: ${{ steps.resolve.outputs.run_rust_coverage }}", source)

    def test_ci_required_aggregates_every_other_job_and_handles_skips(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        aggregate = ci_job_source("ci-required")
        all_jobs = set(
            re.findall(r"(?m)^  ([\w-]+):\n", source.split("\njobs:\n", 1)[1])
        )
        aggregate_needs = set(re.findall(r"(?m)^      - ([\w-]+)$", aggregate))

        self.assertEqual(all_jobs - {"ci-required"}, aggregate_needs)
        self.assertIn("if: always()", aggregate)
        self.assertIn('allowed_results = {"success", "skipped"}', aggregate)
        self.assertIn("Accepted routed/skipped jobs", aggregate)
        self.assertIn("required_success", aggregate)
        self.assertIn("RUN_FULL_CI", aggregate)
        self.assertIn('required_success.add("runtime-api-snapshot")', aggregate)
        self.assertIn("RUN_KOTLIN_COVERAGE", aggregate)
        self.assertIn("RUN_RUST_COVERAGE", aggregate)
        self.assertIn("RUN_NIGHTLY_COVERAGE", aggregate)
        self.assertIn(
            'elif event_name != "schedule" and os.environ["RUN_FULL_CI"] == "true":',
            aggregate,
        )

    def test_preflight_barrier_blocks_compile_heavy_roots(self) -> None:
        preflight = ci_job_source("ci-preflight")
        self.assertIn("if: always()", preflight)
        for required_gate in (
            "change-routing",
            "docs-only-gate",
            "workflow-only-contracts",
            "fixture-contracts",
            "android-e2e-result-contracts",
            "design-md-lint",
            "architecture-health",
            "release-gates",
            "cargo-deny",
            "gradle-static-analysis",
        ):
            self.assertIn(f"      - {required_gate}\n", preflight)
        self.assertIn("python3 scripts/ci/check_ci_preflight.py", preflight)

        for job in (
            "rust-native-packaging",
            "rust-native-x86_64",
            "pluggable-transport-assets",
            "build-android-tests",
            "verify-roborazzi",
            "owned-stack-tls-fingerprint",
            "native-bloat",
            "rust-miri",
            "diagnostics-probes-facade",
            "rust-lint",
            "rust-cross-check",
            "rust-workspace-tests",
            "rust-fuzz-smoke",
            "runtime-api-snapshot",
            "rust-network-e2e",
            "relay-interoperability",
            "cli-packet-smoke",
            "rust-turmoil",
            "kotlin-coverage",
            "rust-coverage",
            "rust-criterion-bench",
            "rust-loom",
            "rust-native-soak",
            "rust-native-load",
            "nightly-kotlin-coverage",
            "nightly-rust-coverage",
            "linux-tun-e2e",
            "so-bindtodevice-e2e",
            "linux-tun-soak",
        ):
            with self.subTest(job=job):
                source = ci_job_source(job)
                self.assertIn("needs: [change-routing, ci-preflight]", source)
                self.assertRegex(
                    source,
                    r"(?ms)^    if: >-\n      !cancelled\(\) &&",
                )
                self.assertIn(
                    "needs.ci-preflight.result == 'success'",
                    source,
                )

        aggregate = ci_job_source("ci-required")
        self.assertIn("      - ci-preflight\n", aggregate)
        self.assertIn('"ci-preflight",', aggregate)
        self.assertIn('required_success.add("kotlin-coverage")', aggregate)
        self.assertIn('required_success.add("rust-coverage")', aggregate)
        self.assertIn('"nightly-kotlin-coverage", "nightly-rust-coverage"', aggregate)


if __name__ == "__main__":
    unittest.main()
