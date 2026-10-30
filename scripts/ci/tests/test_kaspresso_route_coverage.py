#!/usr/bin/env python3
"""Tests for kaspresso_route_coverage.py."""

from __future__ import annotations

import sys
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import kaspresso_route_coverage as coverage  # noqa: E402 (sys.path tweak above)

ROUTES = textwrap.dedent(
    """
    sealed class Route {
        @Serializable
        data object Home : Route() {
            override val stableRoute = "home"
        }

        @Serializable
        data class RuleEditor(
            val ruleId: String? = null,
        ) : Route() {
            override val stableRoute = "rule_editor"
        }

        data class NotARoute(val value: Int)

        @Serializable
        data object HomeDetails : Route() {
            override val stableRoute = "home_details"
        }
    }
    """
)


class RouteCoverageTest(unittest.TestCase):
    def test_finds_leaf_routes_including_wrapped_data_classes(self) -> None:
        self.assertEqual(coverage.leaf_routes(ROUTES), ["Home", "RuleEditor", "HomeDetails"])

    def test_route_name_prefix_does_not_count_as_coverage(self) -> None:
        routes = ["Home", "RuleEditor", "HomeDetails"]
        covered = coverage.covered_routes(["launch(Route.HomeDetails.route)"], routes)
        self.assertEqual(covered, {"HomeDetails"})

    def test_report_lists_uncovered_routes(self) -> None:
        report = coverage.render(["Home", "RuleEditor"], {"Home"})
        self.assertIn("1 of 2 routes", report)
        self.assertIn("Not covered: `RuleEditor`", report)

    def test_repository_routes_parse(self) -> None:
        routes = coverage.leaf_routes(coverage.ROUTE_FILE.read_text(encoding="utf-8"))
        self.assertIn("Home", routes)
        self.assertEqual(len(routes), len(set(routes)))


if __name__ == "__main__":
    unittest.main()
