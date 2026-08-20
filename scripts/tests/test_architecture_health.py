from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))

import check_architecture_health as sut


class ArchitectureHealthTest(unittest.TestCase):
    def test_staged_snapshot_reads_index_instead_of_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            source = repo / "native/rust/crates/ripdpi-example/src/lib.rs"
            manifest = repo / "native/rust/crates/ripdpi-example/Cargo.toml"
            self._write(source, "pub fn staged() {}\n")
            self._write(
                manifest,
                '[package]\nname = "ripdpi-example"\nversion = "0.1.0"\n\n[dependencies]\nripdpi-staged = { path = "../ripdpi-staged" }\n',
            )
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            self._write(source, "pub fn unstaged() { panic!(\"working tree\") }\n")
            self._write(
                manifest,
                '[package]\nname = "ripdpi-example"\nversion = "0.1.0"\n\n[dependencies]\nripdpi-unstaged = { path = "../ripdpi-unstaged" }\n',
            )

            tracked = sut.git_staged_paths(repo)
            with sut.materialize_git_index_snapshot(repo, tracked) as snapshot:
                snapshot_source = snapshot / source.relative_to(repo)
                snapshot_manifest = snapshot / manifest.relative_to(repo)
                dependencies = sut.manifest_dependencies(snapshot_manifest)

                self.assertEqual("pub fn staged() {}\n", snapshot_source.read_text())
                self.assertEqual({"ripdpi-staged"}, dependencies)

    def test_staged_snapshot_skips_gitlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            source = repo / "native/rust/crates/ripdpi-example/src/lib.rs"
            self._write(source, "pub fn staged() {}\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(
                [
                    "git",
                    "update-index",
                    "--add",
                    "--cacheinfo",
                    "160000,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,vendor/catalog",
                ],
                cwd=repo,
                check=True,
            )

            tracked = sut.git_staged_paths(repo)
            self.assertIn("vendor/catalog", tracked)
            with sut.materialize_git_index_snapshot(repo, tracked) as snapshot:
                self.assertEqual(
                    "pub fn staged() {}\n",
                    (snapshot / source.relative_to(repo)).read_text(),
                )
                self.assertFalse((snapshot / "vendor/catalog").exists())

    def test_dependency_hub_and_discouraged_edges_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            manifest = repo / "native/rust/crates/ripdpi-proxy-runtime/Cargo.toml"
            self._write(
                manifest,
                """
[package]
name = "ripdpi-proxy-runtime"
version = "0.1.0"

[dependencies]
ripdpi-runtime-adaptive = { path = "../ripdpi-runtime-adaptive" }
ripdpi-runtime-policy = { path = "../ripdpi-runtime-policy" }
ripdpi-runtime-strategy = { path = "../ripdpi-runtime-strategy" }
ripdpi-dns-resolver = { path = "../ripdpi-dns-resolver" }
ripdpi-failure-classifier = { path = "../ripdpi-failure-classifier" }
ripdpi-ws-bootstrap = { path = "../ripdpi-ws-bootstrap" }
ripdpi-ws-tunnel = { path = "../ripdpi-ws-tunnel" }
ripdpi-a = { path = "../ripdpi-a" }
ripdpi-b = { path = "../ripdpi-b" }
ripdpi-c = { path = "../ripdpi-c" }
ripdpi-d = { path = "../ripdpi-d" }
ripdpi-e = { path = "../ripdpi-e" }
ripdpi-f = { path = "../ripdpi-f" }
ripdpi-g = { path = "../ripdpi-g" }
ripdpi-h = { path = "../ripdpi-h" }
ripdpi-i = { path = "../ripdpi-i" }
ripdpi-j = { path = "../ripdpi-j" }
""",
            )

            indicators = sut.collect_dependency_indicators(repo, None)

        rules = {indicator.rule for indicator in indicators}
        self.assertIn("dependency-hub", rules)
        self.assertIn("discouraged-dependency-edge", rules)
        self.assertTrue(any("ripdpi-runtime-adaptive" in indicator.id for indicator in indicators))

    def test_native_composition_caps_count_production_dependencies_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            manifest = repo / "native/rust/crates/ripdpi-runtime-services/Cargo.toml"
            base_manifest = """
[package]
name = "ripdpi-runtime-services"
version = "0.1.0"

[dependencies]
ripdpi-a = { path = "../ripdpi-a" }
ripdpi-b = { path = "../ripdpi-b" }
ripdpi-c = { path = "../ripdpi-c" }
ripdpi-d = { path = "../ripdpi-d" }
ripdpi-e = { path = "../ripdpi-e" }
ripdpi-f = { path = "../ripdpi-f" }
ripdpi-g = { path = "../ripdpi-g" }
ripdpi-h = { path = "../ripdpi-h" }

[dev-dependencies]
ripdpi-test-fixture = { path = "../ripdpi-test-fixture" }
local-network-fixture = { path = "../local-network-fixture" }
"""
            self._write(
                manifest,
                base_manifest,
            )

            at_limit = sut.collect_dependency_indicators(repo, None)

            self._write(
                manifest,
                base_manifest.replace(
                    "\n[dev-dependencies]",
                    '\nripdpi-i = { path = "../ripdpi-i" }\n\n[dev-dependencies]',
                ),
            )
            over_limit = sut.collect_dependency_indicators(repo, None)

        self.assertFalse(any(indicator.rule == "dependency-hub" for indicator in at_limit))
        self.assertTrue(any(indicator.rule == "dependency-hub" for indicator in over_limit))

    def test_tunnel_core_is_guarded_as_dependency_hub(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            manifest = repo / "native/rust/crates/ripdpi-tunnel-core/Cargo.toml"
            base_manifest = """
[package]
name = "ripdpi-tunnel-core"
version = "0.1.0"

[dependencies]
ripdpi-a = { path = "../ripdpi-a" }
ripdpi-b = { path = "../ripdpi-b" }
ripdpi-c = { path = "../ripdpi-c" }
ripdpi-d = { path = "../ripdpi-d" }
ripdpi-e = { path = "../ripdpi-e" }
ripdpi-f = { path = "../ripdpi-f" }
ripdpi-g = { path = "../ripdpi-g" }
ripdpi-h = { path = "../ripdpi-h" }
ripdpi-i = { path = "../ripdpi-i" }
ripdpi-j = { path = "../ripdpi-j" }
ripdpi-k = { path = "../ripdpi-k" }
ripdpi-l = { path = "../ripdpi-l" }
ripdpi-m = { path = "../ripdpi-m" }
"""
            self._write(manifest, base_manifest)

            at_limit = sut.collect_dependency_indicators(repo, None)

            self._write(
                manifest,
                base_manifest + 'ripdpi-n = { path = "../ripdpi-n" }\n',
            )
            over_limit = sut.collect_dependency_indicators(repo, None)

        self.assertFalse(any(indicator.rule == "dependency-hub" for indicator in at_limit))
        self.assertTrue(
            any(
                indicator.rule == "dependency-hub"
                and indicator.path == "native/rust/crates/ripdpi-tunnel-core/Cargo.toml"
                and indicator.metric_value == 14
                and indicator.limit == 13
                for indicator in over_limit
            )
        )

    def test_dependency_metrics_report_outdegree_and_indegree(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            self._write_manifest(repo, "ripdpi-a", {"ripdpi-b", "ripdpi-c"})
            self._write_manifest(repo, "ripdpi-b", set())
            self._write_manifest(repo, "ripdpi-c", {"ripdpi-b"})

            metrics = {metric.crate: metric for metric in sut.collect_dependency_metrics(repo, None)}

        self.assertEqual(("ripdpi-b", "ripdpi-c"), metrics["ripdpi-a"].internal_dependencies)
        self.assertEqual(("ripdpi-a", "ripdpi-c"), metrics["ripdpi-b"].internal_dependents)
        self.assertEqual(0, len(metrics["ripdpi-a"].internal_dependents))

    def test_kotlin_feature_spread_and_suppression_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            source = repo / "core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnCoordinator.kt"
            self._write(
                source,
                """
@file:Suppress("LongMethod")

package com.poyka.ripdpi.services

class VpnCoordinator {
    fun start() {
        val text = "dns resolver tunnel socks warp telemetry relay diagnostics adaptive routing"
        println(text)
    }
}
"""
                + "\n".join(f"fun filler{i}() = Unit" for i in range(310)),
            )

            indicators = sut.collect_source_indicators(
                repo,
                ["core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnCoordinator.kt"],
            )

        rules = {indicator.rule for indicator in indicators}
        self.assertIn("kotlin-feature-spread", rules)
        self.assertIn("complexity-suppression", rules)

    def test_source_metrics_report_loc_root_exports_and_longest_function(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            source = repo / "native/rust/crates/ripdpi-example/src/lib.rs"
            self._write(
                source,
                """
pub mod transport;
pub use transport::Route;

pub fn route_packet() {
    let dns_probe = "dns resolver tunnel";
    println!("{dns_probe}");
}
""",
            )

            metrics = sut.collect_source_metrics(repo, ["native/rust/crates/ripdpi-example/src/lib.rs"])

        self.assertEqual(1, len(metrics))
        self.assertEqual("rust", metrics[0].kind)
        self.assertEqual(2, metrics[0].root_exports)
        self.assertEqual("route_packet", metrics[0].longest_function)
        self.assertIn("dns", metrics[0].feature_families)

    def test_baseline_exempts_current_entries_and_flags_worsening(self) -> None:
        indicator = sut.Indicator(
            id="example",
            rule="oversized-source-file",
            priority=3,
            path="src/lib.rs",
            message="large",
            metric_name="codeLines",
            metric_value=120,
            limit=100,
            suggestion="split",
        )
        baseline = {
            "example": sut.BaselineEntry(
                id="example",
                rule="oversized-source-file",
                priority=3,
                path="src/lib.rs",
                message="large",
                metric_name="codeLines",
                baseline_value=100,
                limit=100,
                suggestion="split",
            )
        }

        results = sut.evaluate_indicators([indicator], baseline, enforce_stale=True)

        self.assertEqual(0, results["counts"]["newIndicators"])
        self.assertEqual(1, results["counts"]["worsenedBaselineEntries"])

    def test_stale_baseline_entries_are_reported_only_for_full_scan(self) -> None:
        baseline = {
            "resolved": sut.BaselineEntry(
                id="resolved",
                rule="broad-root-facade",
                priority=3,
                path="src/lib.rs",
                message="resolved",
                metric_name="rootExports",
                baseline_value=20,
                limit=10,
                suggestion="split",
            )
        }

        full = sut.evaluate_indicators([], baseline, enforce_stale=True)
        staged = sut.evaluate_indicators([], baseline, enforce_stale=False)

        self.assertEqual(1, full["counts"]["staleBaselineEntries"])
        self.assertEqual(0, staged["counts"]["staleBaselineEntries"])

    def test_report_payload_and_markdown_include_regression_sections(self) -> None:
        indicator = sut.Indicator(
            id="new",
            rule="dependency-hub",
            priority=2,
            path="Cargo.toml",
            message="hub",
            metric_name="internalDependencyCount",
            metric_value=20,
            limit=10,
            suggestion="split",
        )
        results = sut.evaluate_indicators([indicator], {}, enforce_stale=True)
        results["metrics"] = {
            "dependencyGraph": {
                "crateCount": 1,
                "totalInternalDependencyEdges": 1,
                "maxInternalDependencyCount": 1,
                "maxInternalDependentCount": 0,
                "crates": [
                    {
                        "crate": "ripdpi-example",
                        "path": "native/rust/crates/ripdpi-example/Cargo.toml",
                        "internalDependencyCount": 1,
                        "internalDependentCount": 0,
                        "internalDependencies": ["ripdpi-core"],
                        "internalDependents": [],
                    }
                ],
            },
            "sources": {
                "fileCount": 1,
                "totalCodeLines": 42,
                "byKind": {"rust": {"fileCount": 1, "codeLines": 42}},
                "files": [
                    {
                        "path": "native/rust/crates/ripdpi-example/src/lib.rs",
                        "kind": "rust",
                        "codeLines": 42,
                        "rootExports": 2,
                        "longestFunction": "build_runtime",
                        "longestFunctionLines": 12,
                        "featureFamilyCount": 1,
                        "featureFamilies": ["tunnel"],
                        "suppressionCount": 0,
                    }
                ],
            },
        }
        markdown = sut.render_markdown(results)
        payload = sut.baseline_payload([indicator])

        self.assertIn("Metrics Summary", markdown)
        self.assertIn("Dependency Metrics", markdown)
        self.assertIn("Source Metrics", markdown)
        self.assertIn("New Indicators", markdown)
        self.assertIn("ripdpi-example", json.dumps(results))
        self.assertIn("dependency-hub", json.dumps(payload))
        self.assertTrue(sut.should_fail(results))

    @staticmethod
    def _write(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def _write_manifest(self, repo: Path, crate: str, dependencies: set[str]) -> None:
        dependency_lines = "\n".join(
            f'{dependency} = {{ path = "../{dependency}" }}'
            for dependency in sorted(dependencies)
        )
        self._write(
            repo / f"native/rust/crates/{crate}/Cargo.toml",
            f"""
[package]
name = "{crate}"
version = "0.1.0"

[dependencies]
{dependency_lines}
""",
        )


if __name__ == "__main__":
    unittest.main()
