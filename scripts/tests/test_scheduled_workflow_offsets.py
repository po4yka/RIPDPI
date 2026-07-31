#!/usr/bin/env python3
"""Prevent scheduled workflows from using GitHub Actions' congested :00 slot."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = ROOT / ".github/workflows"
CRON_PATTERN = re.compile(r"(?m)^\s*-\s+cron:\s*['\"]([^'\"]+)['\"]")


class ScheduledWorkflowOffsetsTest(unittest.TestCase):
    def test_scheduled_workflows_avoid_the_start_of_the_hour(self) -> None:
        scheduled_workflows: list[tuple[Path, str]] = []
        for workflow in sorted(WORKFLOWS_DIR.glob("*.yml")):
            for cron in CRON_PATTERN.findall(workflow.read_text(encoding="utf-8")):
                scheduled_workflows.append((workflow, cron))
                minute = cron.split(maxsplit=1)[0]
                self.assertNotEqual(
                    "0",
                    minute,
                    f"{workflow.relative_to(ROOT)} uses congested :00 cron slot: {cron}",
                )

        self.assertTrue(scheduled_workflows, "expected at least one scheduled workflow")


if __name__ == "__main__":
    unittest.main()
