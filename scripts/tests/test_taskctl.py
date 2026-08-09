from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

from scripts.tasks import taskctl
from scripts.tasks import migrate_legacy_tasks


class TaskctlFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="ripdpi-taskctl-test-")
        self.root = Path(self.temp.name)
        for relative in (
            "docs/tasks/issues",
            "docs/tasks/work",
            "openspec/changes/archive",
            "tools/tasking",
        ):
            (self.root / relative).mkdir(parents=True, exist_ok=True)
        asset = self.root / "tools/tasking/generated.txt"
        asset.write_text("fixture\n", encoding="utf-8")
        self.write_project_config()
        self.write_federation_contract(self.root)
        (self.root / "tools/tasking/generated-assets.lock.json").write_text(
            json.dumps(
                {
                    "mdtask": "0.1.17",
                    "openspec": "1.8.0",
                    "files": {
                        "tools/tasking/generated.txt": hashlib.sha256(asset.read_bytes()).hexdigest()
                    },
                }
            )
            + "\n",
            encoding="utf-8",
        )

    def write_project_config(
        self,
        *,
        project: str = "po4yka/RIPDPI",
        peers: list[str] | None = None,
        contract: int = 1,
    ) -> None:
        (self.root / "tools/tasking/project.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "federation_contract": contract,
                    "project": project,
                    "areas": taskctl.AREA_PREFIXES,
                    "evidence_categories": list(taskctl.EVIDENCE_CATEGORIES),
                    "openspec_schema": "ripdpi-change",
                    "allowed_peers": peers if peers is not None else ["po4yka/ripdpi-vpn-deploy"],
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    def write_federation_contract(self, root: Path) -> None:
        (root / taskctl.FEDERATION_CONTRACT_PATH).write_text(
            json.dumps(
                {
                    "contract_version": 1,
                    "identity": "owner/repository#TASK-ID",
                    "relations": ["parent", "blocked_by", "related_tasks"],
                    "required_task_fields": [
                        "id", "task_id", "path", "kind", "risk", "status", "progress", "parent",
                        "blocked_by", "related_tasks", "openspec_change", "historical",
                    ],
                    "terminal_states": ["done", "dropped"],
                },
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def add_simple_task(
        self,
        *,
        task_id: str = "CIC-1786234567890001",
        status: str = "todo",
        kind: str = "chore",
        parent: str | None = None,
        blockers: list[str] | None = None,
        reason: str = "tooling-only",
        risk: str = "standard",
        related: list[str] | None = None,
    ) -> Path:
        values = {
            "id": task_id,
            "title": f"Task {task_id}",
            "kind": kind,
            "status": status,
            "area": taskctl.PREFIX_AREAS[task_id.split("-", 1)[0]],
            "priority": "high",
            "risk": risk,
            "owner": "test",
            "parent": parent,
            "blocked_by": blockers or [],
            "related_tasks": related or [],
            "spec_mode": "not-required",
            "openspec_change": None,
            "created": "2026-08-09",
            "updated": "2026-08-09",
            "spec_reason": reason,
        }
        path = self.root / "docs/tasks/issues" / f"{task_id.casefold()}.md"
        path.write_text(taskctl.render_document(values, "## Goal\n\nFixture.\n"), encoding="utf-8")
        prefix, suffix = task_id.split("-", 1)
        step_id = f"{prefix}-{int(suffix) + 1:016d}"
        (self.root / "docs/tasks/work" / f"{task_id}.md").write_text(
            f"# Work\n\n- [ ] {step_id} Execute fixture !high #chore @item:{task_id}\n",
            encoding="utf-8",
        )
        return path

    def write_board(self) -> None:
        documents, steps = taskctl.load_state(self.root)
        (self.root / "docs/tasks/board.md").write_text(
            taskctl.render_board(self.root, documents, steps), encoding="utf-8"
        )

    def add_archived_spec_task(self, *, receipt: bool) -> Path:
        task_id = "DGN-1786234567890101"
        change = "dgn-1786234567890101-change"
        values = {
            "id": task_id,
            "title": "Change diagnostics behavior",
            "kind": "feature",
            "status": "review",
            "area": "diagnostics",
            "priority": "high",
            "risk": "high",
            "owner": "test",
            "parent": None,
            "blocked_by": [],
            "spec_mode": "required",
            "openspec_change": change,
            "created": "2026-08-09",
            "updated": "2026-08-09",
        }
        issue = self.root / "docs/tasks/issues/change-diagnostics.md"
        issue.write_text(taskctl.render_document(values, "## Goal\n\nFixture.\n"), encoding="utf-8")
        archive = self.root / "openspec/changes/archive" / f"2026-08-09-{change}"
        archive.mkdir(parents=True)
        (archive / ".openspec.yaml").write_text("schema: ripdpi-change\n", encoding="utf-8")
        (archive / "proposal.md").write_text(
            f"Task ID: `{task_id}`\n", encoding="utf-8"
        )
        spec = archive / "specs/change/spec.md"
        spec.parent.mkdir(parents=True)
        spec.write_text(
            f"### Requirement: REQ-{task_id}-001 — Fixture\n",
            encoding="utf-8",
        )
        (archive / "tasks.md").write_text(
            f"- [x] DGN-1786234567890102 Implement fixture @item:{task_id}\n",
            encoding="utf-8",
        )
        verification = {
            "task_id": task_id,
            "change": change,
            "commit_sha": "a" * 40,
        }
        for category in taskctl.EVIDENCE_CATEGORIES:
            verification[category] = "passed" if category == "local" else "not_applicable"
            verification[f"{category}_evidence"] = "Fixture evidence."
        verification_body = f"""# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-{task_id}-001 | DGN-1786234567890102 | Fixture evidence. | passed |
"""
        (archive / "verification.md").write_text(
            taskctl.render_document(
                verification,
                verification_body,
                order=tuple(verification),
            ),
            encoding="utf-8",
        )
        if receipt:
            (archive / ".taskctl-archive.json").write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "task_id": task_id,
                        "change": change,
                        "commit_sha": "a" * 40,
                        "archived_at": "2026-08-09T00:00:00Z",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
        return issue

    def add_active_spec_task(self, *, status: str = "review", done: bool = False) -> Path:
        task_id = "DGN-1786234567890101"
        change = "dgn-1786234567890101-change"
        values = {
            "id": task_id,
            "title": "Change diagnostics behavior",
            "kind": "feature",
            "status": status,
            "area": "diagnostics",
            "priority": "high",
            "risk": "high",
            "owner": "test",
            "parent": None,
            "blocked_by": [],
            "spec_mode": "required",
            "openspec_change": change,
            "created": "2026-08-09",
            "updated": "2026-08-09",
        }
        issue = self.root / "docs/tasks/issues/change-diagnostics.md"
        issue.write_text(taskctl.render_document(values, "## Goal\n\nFixture.\n"), encoding="utf-8")
        change_dir = self.root / "openspec/changes" / change
        spec = change_dir / "specs/change/spec.md"
        spec.parent.mkdir(parents=True)
        (change_dir / ".openspec.yaml").write_text("schema: ripdpi-change\n", encoding="utf-8")
        (change_dir / "proposal.md").write_text(f"Task ID: `{task_id}`\n", encoding="utf-8")
        spec.write_text(f"### Requirement: REQ-{task_id}-001 — Fixture\n", encoding="utf-8")
        mark = "x" if done else " "
        step_id = "DGN-1786234567890102"
        (change_dir / "tasks.md").write_text(
            f"- [{mark}] {step_id} Implement fixture @item:{task_id}\n",
            encoding="utf-8",
        )
        verification = {"task_id": task_id, "change": change, "commit_sha": None}
        for category in taskctl.EVIDENCE_CATEGORIES:
            verification[category] = "required" if category == "local" else "not_applicable"
            verification[f"{category}_evidence"] = (
                None if category == "local" else "Fixture does not require this evidence."
            )
        body = f"""# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-{task_id}-001 | {step_id} | Pending | required |
"""
        (change_dir / "verification.md").write_text(
            taskctl.render_document(verification, body, order=tuple(verification)),
            encoding="utf-8",
        )
        return issue


class TaskctlContractTest(TaskctlFixture):
    def test_partial_legacy_criteria_migrate_as_open_steps(self) -> None:
        checks = migrate_legacy_tasks.criteria(
            "- [x] Verified evidence\n- [~] Partially complete\n- [ ] Not started\n",
            "Fixture",
        )

        self.assertEqual(
            [(True, "Verified evidence"), (False, "Partially complete"), (False, "Not started")],
            checks,
        )

    def test_valid_simple_task_and_generated_board(self) -> None:
        self.add_simple_task()
        self.write_board()

        documents, steps = taskctl.validate_repository(
            self.root, base=None, upstreams=False
        )

        self.assertEqual(1, len(documents))
        self.assertEqual(1, len(steps))
        self.assertIn("| CIC-1786234567890001 |", (self.root / "docs/tasks/board.md").read_text())

    def test_duplicate_numeric_suffix_across_prefixes_is_rejected(self) -> None:
        self.add_simple_task(task_id="CIC-1786234567890001")
        self.add_simple_task(
            task_id="TST-1786234567890001", reason="test-only"
        )

        with self.assertRaisesRegex(taskctl.ContractError, "numeric ID suffix"):
            taskctl.load_state(self.root)

    def test_feature_cannot_waive_openspec(self) -> None:
        self.add_simple_task(kind="feature")

        with self.assertRaisesRegex(taskctl.ContractError, "cannot waive OpenSpec"):
            taskctl.load_state(self.root)

    def test_high_risk_task_cannot_waive_openspec(self) -> None:
        self.add_simple_task(risk="high")

        with self.assertRaisesRegex(taskctl.ContractError, "high-risk task cannot waive OpenSpec"):
            taskctl.load_state(self.root)

    def test_missing_blocker_is_rejected(self) -> None:
        self.add_simple_task(blockers=["CIC-1786234567890999"])

        with self.assertRaisesRegex(taskctl.ContractError, "missing or self blocker"):
            taskctl.load_state(self.root)

    def test_text_ready_reports_unresolved_external_blocker(self) -> None:
        self.add_simple_task(
            blockers=["po4yka/ripdpi-vpn-deploy#CIC-1786234567890999"]
        )
        output = StringIO()

        with redirect_stdout(output):
            taskctl.command_ready(argparse.Namespace(root=self.root, json=False))

        self.assertIn("UNRESOLVED_EXTERNAL\tCIC-1786234567890001", output.getvalue())

    def test_blocker_cycle_is_rejected(self) -> None:
        first = "CIC-1786234567890001"
        second = "CIC-1786234567890003"
        self.add_simple_task(task_id=first, blockers=[second])
        self.add_simple_task(task_id=second, blockers=[first])

        with self.assertRaisesRegex(taskctl.ContractError, "blocker cycle"):
            taskctl.load_state(self.root)

    def test_stale_board_is_rejected(self) -> None:
        self.add_simple_task()
        (self.root / "docs/tasks/board.md").write_text("stale\n", encoding="utf-8")

        with self.assertRaisesRegex(taskctl.ContractError, "board.md is stale"):
            taskctl.validate_repository(self.root, base=None, upstreams=False)

    def test_malformed_mdtask_checkbox_is_rejected(self) -> None:
        task_id = "CIC-1786234567890001"
        self.add_simple_task(task_id=task_id)
        (self.root / "docs/tasks/work" / f"{task_id}.md").write_text(
            "- [ ] Missing identifier\n", encoding="utf-8"
        )

        with self.assertRaisesRegex(taskctl.ContractError, "not a valid mdtask"):
            taskctl.load_state(self.root)

    def test_generated_asset_drift_is_rejected(self) -> None:
        self.add_simple_task()
        self.write_board()
        (self.root / "tools/tasking/generated.txt").write_text("drift\n", encoding="utf-8")

        with self.assertRaisesRegex(taskctl.ContractError, "generated asset drift"):
            taskctl.validate_repository(self.root, base=None, upstreams=False)

    def test_transition_state_machine_rejects_terminal_shortcut(self) -> None:
        self.assertTrue(taskctl.transition_allowed("doing", "review"))
        self.assertFalse(taskctl.transition_allowed("todo", "done"))
        self.assertFalse(taskctl.transition_allowed("done", "doing"))

    def test_terminal_state_requires_taskctl_close_receipt(self) -> None:
        path = self.add_simple_task(status="review")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.write_text(work.read_text(encoding="utf-8").replace("- [ ]", "- [x]"), encoding="utf-8")
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Manual edit.",
                "evidence_summary": "Untrusted manual claim.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")

        with self.assertRaisesRegex(taskctl.ContractError, "lacks taskctl close receipt"):
            taskctl.load_state(self.root)

    def test_done_spec_task_cannot_keep_an_active_change(self) -> None:
        path = self.add_active_spec_task(status="review", done=True)
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Fixture.",
                "evidence_summary": "Fixture.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        terminal = taskctl.read_document(path)
        execution = self.root / "openspec/changes/dgn-1786234567890101-change/tasks.md"
        taskctl.write_lifecycle_receipt(
            self.root,
            terminal,
            execution,
            "close",
            {
                "schema": 1,
                "task_id": terminal.task_id,
                "change": terminal.values["openspec_change"],
                "outcome": "done",
                "issue_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "execution_sha256": hashlib.sha256(execution.read_bytes()).hexdigest(),
            },
        )

        with self.assertRaisesRegex(taskctl.ContractError, "requires an archived OpenSpec change"):
            taskctl.load_state(self.root)

    def test_prepare_dropped_spec_preserves_open_step_as_dropped(self) -> None:
        self.add_active_spec_task(status="review", done=False)
        self.add_simple_task()
        args = argparse.Namespace(
            root=self.root,
            query="DGN-1786234567890101",
            outcome="dropped",
            reason="Owner cancelled the capability.",
            evidence="Cancellation decision recorded.",
        )

        self.assertEqual(0, taskctl.command_close_prepare(args))
        documents, steps = taskctl.load_state(self.root)
        tasks_text = (
            self.root / "openspec/changes/dgn-1786234567890101-change/tasks.md"
        ).read_text(encoding="utf-8")

        self.assertEqual("dropped", documents[0].values["status"])
        self.assertEqual(
            ["CIC-1786234567890002"],
            [step.task_id for step in steps],
        )
        self.assertIn("DROPPED:", tasks_text)
        self.assertNotIn("- [x]", tasks_text)
        receipt = json.loads(
            (
                self.root
                / "openspec/changes/dgn-1786234567890101-change/.taskctl-drop.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(["DGN-1786234567890102"], receipt["dropped_step_ids"])
        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(
                0,
                taskctl.command_list(
                    argparse.Namespace(
                        root=self.root,
                        status=["dropped"],
                        area=None,
                        kind=None,
                        json=True,
                    )
                ),
            )
        listed = json.loads(output.getvalue())
        self.assertEqual({"done": 0, "total": 0}, listed["tasks"][0]["progress"])

    def test_new_simple_task_uses_global_allocator_and_execution_contract(self) -> None:
        self.add_simple_task()
        args = argparse.Namespace(
            root=self.root,
            title="Add another tooling check",
            kind="chore",
            area="ci",
            priority="medium",
            risk="standard",
            owner="test",
            parent=None,
            slug=None,
            spec_mode="not-required",
            spec_reason="tooling-only",
            openspec_change=None,
        )

        self.assertEqual(0, taskctl.command_new(args))
        documents, steps = taskctl.load_state(self.root)

        self.assertEqual(2, len(documents))
        self.assertEqual(2, len(steps))
        suffixes = [taskctl.ID_RE.fullmatch(document.task_id).group(2) for document in documents]
        suffixes.extend(taskctl.ID_RE.fullmatch(step.task_id).group(2) for step in steps)
        self.assertEqual(len(suffixes), len(set(suffixes)))
        created = next(document for document in documents if document.values["title"] == args.title)
        work = self.root / "docs/tasks/work" / f"{created.task_id}.md"
        self.assertNotIn("!medium", work.read_text(encoding="utf-8"))

    def test_new_rejects_high_risk_waiver_before_writing(self) -> None:
        self.add_simple_task()
        before = set(self.root.glob("docs/tasks/issues/*.md"))
        args = argparse.Namespace(
            root=self.root,
            title="Add unsafe waiver",
            kind="chore",
            area="ci",
            priority="high",
            risk="high",
            owner="test",
            parent=None,
            slug=None,
            spec_mode="not-required",
            spec_reason="tooling-only",
            openspec_change=None,
        )

        with self.assertRaisesRegex(taskctl.ContractError, "high-risk task cannot waive OpenSpec"):
            taskctl.command_new(args)

        self.assertEqual(before, set(self.root.glob("docs/tasks/issues/*.md")))

    def test_new_task_uses_supported_mdtask_priority_tokens(self) -> None:
        self.add_simple_task()
        for priority, token in (
            ("critical", "!crit"),
            ("high", "!high"),
            ("medium", None),
            ("low", "!low"),
        ):
            with self.subTest(priority=priority):
                args = argparse.Namespace(
                    root=self.root,
                    title=f"Repair {priority} tooling",
                    kind="bug",
                    area="ci",
                    priority=priority,
                    risk="standard",
                    owner="test",
                    parent=None,
                    slug=None,
                    spec_mode="not-required",
                    spec_reason="tooling-only",
                    openspec_change=None,
                )

                self.assertEqual(0, taskctl.command_new(args))
                documents, _ = taskctl.load_state(self.root)
                created = next(document for document in documents if document.values["title"] == args.title)
                work = self.root / "docs/tasks/work" / f"{created.task_id}.md"
                text = work.read_text(encoding="utf-8")

                if token is None:
                    self.assertNotRegex(text, r" !(?:crit|critical|high|medium|low) ")
                else:
                    self.assertIn(f" {token} ", text)

    def test_archived_change_requires_taskctl_receipt(self) -> None:
        self.add_archived_spec_task(receipt=False)

        with self.assertRaisesRegex(taskctl.ContractError, "was not created by taskctl"):
            taskctl.load_state(self.root)

    def test_archived_change_with_receipt_remains_valid_during_review(self) -> None:
        self.add_archived_spec_task(receipt=True)
        documents, steps = taskctl.load_state(self.root)

        self.assertEqual("review", documents[0].values["status"])
        self.assertTrue(steps[0].done)

    def test_verification_requires_evidence_for_non_pending_states(self) -> None:
        path = self.root / "verification.md"
        path.write_text(
            """---
task_id: CIC-1786234567890001
change: tasking
commit_sha: null
local: passed
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Not a device change.
artifact: not_applicable
artifact_evidence: No shipped artifact.
deployment: not_applicable
deployment_evidence: No deployment.
---

# Verification
""",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(taskctl.ContractError, "local_evidence required"):
            taskctl.evidence_values(path)


class TaskctlHistoryTest(TaskctlFixture):
    def git(self, *args: str) -> str:
        result = subprocess.run(
            ("git", *args),
            cwd=self.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=True,
        )
        return result.stdout.strip()

    def setUp(self) -> None:
        super().setUp()
        self.git("init", "-q")
        self.git("config", "user.name", "Taskctl Test")
        self.git("config", "user.email", "taskctl@example.invalid")

    def commit_all(self, message: str) -> str:
        self.git("add", ".")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def prepare_simple_terminal(self, path: Path) -> None:
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Verified.",
                "evidence_summary": "Unit fixture passed.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.write_text(work.read_text(encoding="utf-8").replace("- [ ]", "- [x]"), encoding="utf-8")
        terminal = taskctl.read_document(path)
        taskctl.write_lifecycle_receipt(
            self.root,
            terminal,
            work,
            "close",
            {
                "schema": 1,
                "task_id": terminal.task_id,
                "change": None,
                "outcome": "done",
                "issue_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
            },
        )

    def purge_simple_task(self, path: Path) -> None:
        path.unlink()
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.unlink()
        work.with_suffix(".close.json").unlink()

    def test_deletion_without_terminal_commit_is_rejected(self) -> None:
        path = self.add_simple_task()
        self.write_board()
        base = self.commit_all("add task")
        path.unlink()
        self.commit_all("delete task")

        with self.assertRaisesRegex(taskctl.ContractError, "without a preceding committed terminal"):
            taskctl.validate_deleted_history(self.root, base)

    def test_forged_todo_to_done_history_with_open_step_is_rejected(self) -> None:
        path = self.add_simple_task(status="todo")
        self.write_board()
        base = self.commit_all("add task")
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Forged.",
                "evidence_summary": "Forged.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        receipt = work.with_suffix(".close.json")
        receipt.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "task_id": "CIC-1786234567890001",
                    "change": None,
                    "outcome": "done",
                    "issue_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
                }
            ),
            encoding="utf-8",
        )
        self.commit_all("forge terminal state")
        path.unlink()
        work.unlink()
        receipt.unlink()
        self.commit_all("delete forged task")

        with self.assertRaisesRegex(taskctl.ContractError, "invalid terminal transition todo -> done"):
            taskctl.validate_deleted_history(self.root, base)

    def test_terminal_commit_then_deletion_is_accepted(self) -> None:
        path = self.add_simple_task(status="review")
        self.write_board()
        base = self.commit_all("add task")
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Verified.",
                "evidence_summary": "Unit fixture passed.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.write_text(work.read_text(encoding="utf-8").replace("- [ ]", "- [x]"), encoding="utf-8")
        terminal = taskctl.read_document(path)
        taskctl.write_lifecycle_receipt(
            self.root,
            terminal,
            work,
            "close",
            {
                "schema": 1,
                "task_id": terminal.task_id,
                "change": None,
                "outcome": "done",
                "issue_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
            },
        )
        self.commit_all("prepare terminal state")
        path.unlink()
        work.unlink()
        work.with_suffix(".close.json").unlink()
        self.commit_all("purge task")

        taskctl.validate_deleted_history(self.root, base)

    def test_legacy_done_can_be_normalized_strictly_before_deletion(self) -> None:
        path = self.root / "docs/tasks/issues/cic-1786234567890001.md"
        path.write_text(
            """# Legacy completed task

- [x] #task Record the completed result #status/done

## Ship definition

- [x] Immutable evidence exists.
""",
            encoding="utf-8",
        )
        base = self.commit_all("record legacy completed task")

        path = self.add_simple_task(status="review")
        self.prepare_simple_terminal(path)
        self.commit_all("normalize legacy terminal task")
        self.purge_simple_task(path)
        self.commit_all("purge normalized task")

        taskctl.validate_deleted_history(self.root, base)

    def test_open_legacy_terminal_cannot_be_normalized_as_done(self) -> None:
        path = self.root / "docs/tasks/issues/cic-1786234567890001.md"
        path.write_text(
            """# Legacy incomplete task

- [ ] #task Record the completed result #status/done
""",
            encoding="utf-8",
        )
        base = self.commit_all("record inconsistent legacy task")

        path = self.add_simple_task(status="review")
        self.prepare_simple_terminal(path)
        self.commit_all("forge terminal normalization")
        self.purge_simple_task(path)
        self.commit_all("purge forged normalization")

        with self.assertRaisesRegex(taskctl.ContractError, "legacy terminal snapshot has open"):
            taskctl.validate_deleted_history(self.root, base)

    def test_purge_rejects_incoming_related_task(self) -> None:
        target = self.add_simple_task(status="review")
        self.add_simple_task(
            task_id="CIC-1786234567890003",
            related=["CIC-1786234567890001"],
        )
        self.write_board()
        self.commit_all("add related tasks")
        self.prepare_simple_terminal(target)
        self.commit_all("prepare terminal target")

        with self.assertRaisesRegex(taskctl.ContractError, "incoming reference"):
            taskctl.command_close_purge(
                argparse.Namespace(root=self.root, query="CIC-1786234567890001")
            )

    def test_latest_reintroduced_incarnation_repairs_published_invalid_transition(self) -> None:
        path = self.add_simple_task(status="doing")
        self.write_board()
        base = self.commit_all("add doing task")
        self.prepare_simple_terminal(path)
        self.commit_all("publish invalid direct terminal state")
        self.purge_simple_task(path)
        self.commit_all("publish invalid purge")

        path = self.add_simple_task(status="review")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.write_text(work.read_text(encoding="utf-8").replace("- [ ]", "- [x]"), encoding="utf-8")
        self.commit_all("reintroduce committed review state")
        self.prepare_simple_terminal(path)
        self.commit_all("record repaired terminal state")
        self.purge_simple_task(path)
        self.commit_all("purge repaired task")

        taskctl.validate_deleted_history(self.root, base)

    def test_reintroduced_task_cannot_skip_committed_review_state(self) -> None:
        path = self.add_simple_task(status="doing")
        self.write_board()
        base = self.commit_all("add doing task")
        self.prepare_simple_terminal(path)
        self.commit_all("publish invalid direct terminal state")
        self.purge_simple_task(path)
        self.commit_all("publish invalid purge")

        path = self.add_simple_task(status="review")
        self.prepare_simple_terminal(path)
        self.commit_all("reintroduce task directly as done")
        self.purge_simple_task(path)
        self.commit_all("purge unrepaired task")

        with self.assertRaisesRegex(taskctl.ContractError, "invalid terminal transition missing -> done"):
            taskctl.validate_deleted_history(self.root, base)

    def test_federation_history_uses_latest_reintroduced_incarnation(self) -> None:
        path = self.add_simple_task(status="review")
        self.write_board()
        self.commit_all("add first incarnation")
        self.prepare_simple_terminal(path)
        self.commit_all("complete first incarnation")
        self.purge_simple_task(path)
        self.commit_all("purge first incarnation")

        path = self.add_simple_task(status="review")
        self.commit_all("add second incarnation")
        document = taskctl.read_document(path)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Forged.",
                "evidence_summary": "Second incarnation stayed open.",
            }
        )
        path.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.root / "docs/tasks/work/CIC-1786234567890001.md"
        work.with_suffix(".close.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "task_id": "CIC-1786234567890001",
                    "change": None,
                    "outcome": "done",
                    "issue_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
                }
            )
            + "\n",
            encoding="utf-8",
        )
        self.commit_all("forge second terminal state")
        self.purge_simple_task(path)
        self.commit_all("purge second incarnation")

        with self.assertRaisesRegex(taskctl.ContractError, "terminal commit contains open execution steps"):
            taskctl.resolve_terminal_task(
                self.root,
                "CIC-1786234567890001",
                taskctl.load_project_config(self.root),
            )

    def test_dropped_openspec_change_archives_without_syncing_normative_specs(self) -> None:
        self.add_active_spec_task(status="review", done=False)
        openspec = self.root / "tools/tasking/node_modules/.bin/openspec"
        openspec.parent.mkdir(parents=True)
        openspec.write_text(
            """#!/usr/bin/env python3
import pathlib
import shutil
import sys

root = pathlib.Path.cwd()
if len(sys.argv) > 2 and sys.argv[1] == "archive":
    if "--skip-specs" not in sys.argv:
        raise SystemExit("dropped archive must use --skip-specs")
    change = sys.argv[2]
    source = root / "openspec/changes" / change
    target = root / "openspec/changes/archive" / f"2026-08-09-{change}"
    shutil.move(source, target)
print("{}")
""",
            encoding="utf-8",
        )
        openspec.chmod(0o755)
        self.write_board()
        base = self.commit_all("add active change")
        taskctl.command_close_prepare(
            argparse.Namespace(
                root=self.root,
                query="DGN-1786234567890101",
                outcome="dropped",
                reason="Owner cancelled the capability.",
                evidence="Cancellation decision recorded.",
            )
        )
        self.commit_all("prepare dropped state")

        result = taskctl.command_openspec_archive(
            argparse.Namespace(
                root=self.root,
                change="dgn-1786234567890101-change",
            )
        )
        documents, steps = taskctl.load_state(self.root)
        archive = self.root / "openspec/changes/archive/2026-08-09-dgn-1786234567890101-change"

        self.assertEqual(0, result)
        self.assertEqual("dropped", documents[0].values["status"])
        self.assertEqual([], steps)
        self.assertTrue((archive / ".taskctl-archive.json").is_file())
        self.assertFalse((self.root / "openspec/specs/change/spec.md").exists())
        (archive / ".taskctl-archive.json").write_text("{}\n", encoding="utf-8")
        (self.root / "docs/tasks/issues/change-diagnostics.md").unlink()
        self.commit_all("forge archive and delete task")

        with self.assertRaisesRegex(taskctl.ContractError, "invalid OpenSpec archive receipt"):
            taskctl.validate_deleted_history(self.root, base)


class TaskctlFederationTest(TaskctlFixture):
    def setUp(self) -> None:
        super().setUp()
        self.peer_temp = tempfile.TemporaryDirectory(prefix="ripdpi-taskctl-peer-")
        self.peer = Path(self.peer_temp.name)
        for relative in ("docs/tasks/issues", "docs/tasks/work", "openspec/changes/archive", "tools/tasking"):
            (self.peer / relative).mkdir(parents=True, exist_ok=True)
        self.write_config(self.root, "po4yka/RIPDPI", ["po4yka/ripdpi-vpn-deploy"])
        self.write_config(self.peer, "po4yka/ripdpi-vpn-deploy", ["po4yka/RIPDPI"])
        self.write_federation_contract(self.peer)
        self.init_git(self.root)
        self.init_git(self.peer)

    def tearDown(self) -> None:
        self.peer_temp.cleanup()
        super().tearDown()

    def write_config(self, root: Path, project: str, peers: list[str]) -> None:
        (root / "tools/tasking/project.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "federation_contract": 1,
                    "project": project,
                    "areas": taskctl.AREA_PREFIXES,
                    "evidence_categories": list(taskctl.EVIDENCE_CATEGORIES),
                    "openspec_schema": "ripdpi-change",
                    "allowed_peers": peers,
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    def init_git(self, root: Path) -> None:
        subprocess.run(("git", "init", "-q"), cwd=root, check=True)
        subprocess.run(("git", "config", "user.name", "Taskctl Test"), cwd=root, check=True)
        subprocess.run(("git", "config", "user.email", "taskctl@example.invalid"), cwd=root, check=True)

    def commit(self, root: Path, message: str) -> str:
        subprocess.run(("git", "add", "."), cwd=root, check=True)
        subprocess.run(("git", "commit", "-q", "-m", message), cwd=root, check=True)
        return subprocess.run(
            ("git", "rev-parse", "HEAD"), cwd=root, check=True, text=True, stdout=subprocess.PIPE
        ).stdout.strip()

    def add_task(
        self,
        root: Path,
        task_id: str,
        *,
        status: str = "todo",
        blockers: list[str] | None = None,
        related: list[str] | None = None,
    ) -> Path:
        values = {
            "id": task_id,
            "title": f"Task {task_id}",
            "kind": "chore",
            "status": status,
            "area": "ci",
            "priority": "high",
            "risk": "standard",
            "owner": "test",
            "parent": None,
            "blocked_by": blockers or [],
            "related_tasks": related or [],
            "spec_mode": "not-required",
            "openspec_change": None,
            "created": "2026-08-09",
            "updated": "2026-08-09",
            "spec_reason": "tooling-only",
        }
        path = root / "docs/tasks/issues" / f"{task_id.casefold()}.md"
        path.write_text(taskctl.render_document(values, "## Goal\n\nFixture.\n"), encoding="utf-8")
        prefix, suffix = task_id.split("-", 1)
        step_id = f"{prefix}-{int(suffix) + 1:016d}"
        (root / "docs/tasks/work" / f"{task_id}.md").write_text(
            f"- [ ] {step_id} Execute fixture !high #chore @item:{task_id}\n",
            encoding="utf-8",
        )
        documents, steps = taskctl.load_state(root)
        (root / "docs/tasks/board.md").write_text(taskctl.render_board(root, documents, steps), encoding="utf-8")
        return path

    def test_export_is_deterministic_and_omits_body_and_evidence(self) -> None:
        self.add_task(self.root, "CIC-1786234567890001")
        self.commit(self.root, "add task")

        first = json.dumps(taskctl.export_payload(self.root), sort_keys=True)
        second = json.dumps(taskctl.export_payload(self.root), sort_keys=True)

        self.assertEqual(first, second)
        self.assertNotIn("Fixture", first)
        self.assertNotIn("evidence", first.casefold())

    def test_export_rejects_dirty_or_untracked_task_state(self) -> None:
        issue = self.add_task(self.root, "CIC-1786234567890001")
        self.commit(self.root, "add task")
        issue.write_text(issue.read_text(encoding="utf-8").replace("status: todo", "status: backlog"), encoding="utf-8")

        with self.assertRaisesRegex(taskctl.ContractError, "requires committed"):
            taskctl.export_payload(self.root)

    def test_equal_local_ids_remain_distinct_and_reverse_block_is_derived(self) -> None:
        task_id = "CIC-1786234567890001"
        self.add_task(self.root, task_id)
        self.add_task(self.peer, task_id, blockers=[f"po4yka/RIPDPI#{task_id}"])
        self.commit(self.root, "add local task")
        self.commit(self.peer, "add peer task")

        payload = taskctl.federation_payload(self.root, self.peer)
        by_id = {node["id"]: node for node in payload["tasks"]}

        self.assertEqual(2, len(by_id))
        self.assertEqual(
            [f"po4yka/ripdpi-vpn-deploy#{task_id}"],
            by_id[f"po4yka/RIPDPI#{task_id}"]["blocks"],
        )
        self.assertNotIn(f"po4yka/ripdpi-vpn-deploy#{task_id}", {node["id"] for node in payload["ready"]})

    def test_cross_repository_blocker_cycle_is_rejected(self) -> None:
        local_id = "CIC-1786234567890001"
        peer_id = "CIC-1786234567890003"
        self.add_task(self.root, local_id, blockers=[f"po4yka/ripdpi-vpn-deploy#{peer_id}"])
        self.add_task(self.peer, peer_id, blockers=[f"po4yka/RIPDPI#{local_id}"])
        self.commit(self.root, "add local cycle edge")
        self.commit(self.peer, "add peer cycle edge")

        with self.assertRaisesRegex(taskctl.ContractError, "federated blocker cycle"):
            taskctl.federation_payload(self.root, self.peer)

    def test_unknown_peer_and_missing_peer_task_fail_closed(self) -> None:
        with self.assertRaisesRegex(taskctl.ContractError, "unapproved project"):
            self.add_task(
                self.root,
                "CIC-1786234567890001",
                blockers=["someone/unknown#CIC-1786234567890003"],
            )
        (self.root / "docs/tasks/issues/cic-1786234567890001.md").unlink()
        (self.root / "docs/tasks/work/CIC-1786234567890001.md").unlink()
        self.add_task(
            self.root,
            "CIC-1786234567890001",
            blockers=["po4yka/ripdpi-vpn-deploy#CIC-1786234567890999"],
        )
        self.add_task(self.peer, "CIC-1786234567890003")
        self.commit(self.root, "add missing peer reference")
        self.commit(self.peer, "add peer anchor")

        with self.assertRaisesRegex(taskctl.ContractError, "missing federated task"):
            taskctl.federation_payload(self.root, self.peer)

    def test_incompatible_contract_is_rejected_before_graph_evaluation(self) -> None:
        self.add_task(self.root, "CIC-1786234567890001")
        self.add_task(self.peer, "CIC-1786234567890003")
        config_path = self.peer / "tools/tasking/project.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        config["federation_contract"] = 2
        config_path.write_text(json.dumps(config) + "\n", encoding="utf-8")
        self.commit(self.root, "add local task")
        self.commit(self.peer, "add incompatible peer")

        with self.assertRaisesRegex(taskctl.ContractError, "unsupported federation contract"):
            taskctl.federation_payload(self.root, self.peer)

    def test_contract_artifact_drift_is_rejected(self) -> None:
        self.add_task(self.root, "CIC-1786234567890001")
        self.add_task(self.peer, "CIC-1786234567890003")
        contract_path = self.peer / taskctl.FEDERATION_CONTRACT_PATH
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        contract["implementation_hint"] = "drift"
        contract_path.write_text(json.dumps(contract, sort_keys=True) + "\n", encoding="utf-8")
        self.commit(self.root, "add local task")
        self.commit(self.peer, "add drifted peer contract")

        with self.assertRaisesRegex(taskctl.ContractError, "contract artifacts differ"):
            taskctl.federation_payload(self.root, self.peer)

    def test_pairwise_federation_allows_additional_approved_peers(self) -> None:
        self.write_config(
            self.root,
            "po4yka/RIPDPI",
            ["po4yka/ripdpi-vpn-deploy", "po4yka/another-repository"],
        )
        self.write_config(
            self.peer,
            "po4yka/ripdpi-vpn-deploy",
            ["po4yka/RIPDPI", "po4yka/another-repository"],
        )
        self.add_task(self.root, "CIC-1786234567890001")
        self.add_task(self.peer, "CIC-1786234567890003")
        self.commit(self.root, "add local task")
        self.commit(self.peer, "add peer task")

        self.assertTrue(taskctl.federation_payload(self.root, self.peer)["valid"])

    def test_active_dropped_blocker_is_rejected(self) -> None:
        blocker_id = "CIC-1786234567890001"
        consumer_id = "CIC-1786234567890005"
        blocker = self.add_task(self.peer, blocker_id, status="review")
        self.add_task(self.root, consumer_id, blockers=[f"po4yka/ripdpi-vpn-deploy#{blocker_id}"])
        document = taskctl.read_document(blocker)
        values = dict(document.values)
        values.update(
            {
                "status": "dropped",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Cancelled.",
                "evidence_summary": "Cancellation recorded.",
            }
        )
        blocker.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.peer / f"docs/tasks/work/{blocker_id}.md"
        step_id = taskctl.read_steps(work)[0].task_id
        work.write_text(
            work.read_text(encoding="utf-8").replace(
                f"- [ ] {step_id}", f"- {step_id} DROPPED:"
            ),
            encoding="utf-8",
        )
        terminal = taskctl.read_document(blocker)
        taskctl.write_lifecycle_receipt(
            self.peer,
            terminal,
            work,
            "drop",
            {
                "schema": 1,
                "task_id": blocker_id,
                "change": None,
                "outcome": "dropped",
                "dropped_step_ids": [step_id],
            },
        )
        taskctl.write_lifecycle_receipt(
            self.peer,
            terminal,
            work,
            "close",
            {
                "schema": 1,
                "task_id": blocker_id,
                "change": None,
                "outcome": "dropped",
                "issue_sha256": hashlib.sha256(blocker.read_bytes()).hexdigest(),
                "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
            },
        )
        peer_documents, peer_steps = taskctl.load_state(self.peer)
        (self.peer / "docs/tasks/board.md").write_text(
            taskctl.render_board(self.peer, peer_documents, peer_steps), encoding="utf-8"
        )
        self.commit(self.root, "add consumer")
        self.commit(self.peer, "record dropped blocker")

        with self.assertRaisesRegex(taskctl.ContractError, "blocker .* was dropped"):
            taskctl.federation_payload(self.root, self.peer)

    def test_purged_done_blocker_resolves_from_terminal_history(self) -> None:
        blocker_id = "CIC-1786234567890001"
        consumer_id = "CIC-1786234567890005"
        self.add_task(self.peer, "CIC-1786234567890003")
        blocker = self.add_task(self.peer, blocker_id, status="review")
        self.commit(self.peer, "add peer tasks")
        document = taskctl.read_document(blocker)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Verified.",
                "evidence_summary": "Federation fixture passed.",
            }
        )
        blocker.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.peer / f"docs/tasks/work/{blocker_id}.md"
        work.write_text(work.read_text(encoding="utf-8").replace("- [ ]", "- [x]"), encoding="utf-8")
        terminal = taskctl.read_document(blocker)
        taskctl.write_lifecycle_receipt(
            self.peer,
            terminal,
            work,
            "close",
            {
                "schema": 1,
                "task_id": blocker_id,
                "change": None,
                "outcome": "done",
                "issue_sha256": hashlib.sha256(blocker.read_bytes()).hexdigest(),
                "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
            },
        )
        self.commit(self.peer, "prepare terminal blocker")
        blocker.unlink()
        work.unlink()
        work.with_suffix(".close.json").unlink()
        anchor_docs, anchor_steps = taskctl.load_state(self.peer)
        (self.peer / "docs/tasks/board.md").write_text(
            taskctl.render_board(self.peer, anchor_docs, anchor_steps), encoding="utf-8"
        )
        self.commit(self.peer, "purge terminal blocker")
        self.add_task(self.root, consumer_id, blockers=[f"po4yka/ripdpi-vpn-deploy#{blocker_id}"])
        self.commit(self.root, "add consumer")

        payload = taskctl.federation_payload(self.root, self.peer)
        ready_ids = {node["id"] for node in payload["ready"]}

        self.assertIn(f"po4yka/RIPDPI#{consumer_id}", ready_ids)
        historical = next(node for node in payload["tasks"] if node["id"].endswith(f"#{blocker_id}"))
        self.assertTrue(historical["historical"])
        self.assertEqual("done", historical["status"])

    def test_forged_empty_terminal_history_cannot_release_consumer(self) -> None:
        blocker_id = "CIC-1786234567890001"
        consumer_id = "CIC-1786234567890005"
        self.add_task(self.peer, "CIC-1786234567890003")
        blocker = self.add_task(self.peer, blocker_id, status="review")
        self.commit(self.peer, "add peer tasks")
        document = taskctl.read_document(blocker)
        values = dict(document.values)
        values.update(
            {
                "status": "done",
                "closed_at": "2026-08-09T00:00:00Z",
                "closed_reason": "Forged.",
                "evidence_summary": "Forged.",
            }
        )
        blocker.write_text(taskctl.render_document(values, document.body), encoding="utf-8")
        work = self.peer / f"docs/tasks/work/{blocker_id}.md"
        work.write_text("# Empty execution\n", encoding="utf-8")
        receipt = {
            "schema": 1,
            "task_id": blocker_id,
            "change": None,
            "outcome": "done",
            "issue_sha256": hashlib.sha256(blocker.read_bytes()).hexdigest(),
            "execution_sha256": hashlib.sha256(work.read_bytes()).hexdigest(),
        }
        work.with_suffix(".close.json").write_text(json.dumps(receipt) + "\n", encoding="utf-8")
        self.commit(self.peer, "forge terminal blocker")
        blocker.unlink()
        work.unlink()
        work.with_suffix(".close.json").unlink()
        anchor_documents, anchor_steps = taskctl.load_state(self.peer)
        (self.peer / "docs/tasks/board.md").write_text(
            taskctl.render_board(self.peer, anchor_documents, anchor_steps), encoding="utf-8"
        )
        self.commit(self.peer, "purge forged blocker")
        self.add_task(self.root, consumer_id, blockers=[f"po4yka/ripdpi-vpn-deploy#{blocker_id}"])
        self.commit(self.root, "add consumer")

        with self.assertRaisesRegex(taskctl.ContractError, "completed task has no execution steps"):
            taskctl.federation_payload(self.root, self.peer)


class TaskctlConcurrencyTest(TaskctlFixture):
    def git(self, root: Path, *args: str) -> str:
        result = subprocess.run(
            ("git", *args),
            cwd=root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=True,
        )
        return result.stdout.strip()

    def new_args(self, root: Path, title: str) -> argparse.Namespace:
        return argparse.Namespace(
            root=root,
            title=title,
            kind="chore",
            area="ci",
            priority="medium",
            risk="standard",
            owner="test",
            parent=None,
            slug=None,
            spec_mode="not-required",
            spec_reason="tooling-only",
            openspec_change=None,
        )

    def test_independent_worktrees_merge_with_globally_unique_ids(self) -> None:
        self.add_simple_task()
        self.write_board()
        self.git(self.root, "init", "-q", "-b", "main")
        self.git(self.root, "config", "user.name", "Taskctl Test")
        self.git(self.root, "config", "user.email", "taskctl@example.invalid")
        self.git(self.root, "add", ".")
        self.git(self.root, "commit", "-q", "-m", "fixture")
        lanes = self.root / ".test-worktrees"
        lanes.mkdir()
        lane_one = lanes / "lane-one"
        lane_two = lanes / "lane-two"
        self.git(self.root, "worktree", "add", "-q", "-b", "lane-one", str(lane_one))
        self.git(self.root, "worktree", "add", "-q", "-b", "lane-two", str(lane_two))

        with mock.patch.object(taskctl.time, "time", return_value=1786234567.890):
            with mock.patch.object(taskctl.random, "SystemRandom") as random_one:
                random_one.return_value.randrange.side_effect = [101, 102]
                self.assertEqual(0, taskctl.command_new(self.new_args(lane_one, "Lane one task")))
            with mock.patch.object(taskctl.random, "SystemRandom") as random_two:
                random_two.return_value.randrange.side_effect = [101, 201, 102, 202]
                self.assertEqual(0, taskctl.command_new(self.new_args(lane_two, "Lane two task")))

        for lane, branch in ((lane_one, "lane-one"), (lane_two, "lane-two")):
            self.git(lane, "add", ".")
            self.git(lane, "commit", "-q", "-m", f"add {branch} task")
            self.git(self.root, "merge", "-q", "--ff-only" if branch == "lane-one" else "--no-edit", branch)

        self.write_board()
        documents, steps = taskctl.validate_repository(self.root, base=None, upstreams=False)
        suffixes = [taskctl.ID_RE.fullmatch(document.task_id).group(2) for document in documents]
        suffixes.extend(taskctl.ID_RE.fullmatch(step.task_id).group(2) for step in steps)
        self.assertEqual(3, len(documents))
        self.assertEqual(3, len(steps))
        self.assertEqual(len(suffixes), len(set(suffixes)))


if __name__ == "__main__":
    unittest.main()
