#!/usr/bin/env python3
"""One-shot migration of the legacy RIPDPI task board to the taskctl contract."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path

from scripts.tasks import taskctl


CHECKBOX_RE = re.compile(r"^- \[([ xX~])\]\s+(.+?)\s*$")


@dataclass(frozen=True)
class LegacyTask:
    document: taskctl.Document
    slug: str


def classify(task: LegacyTask) -> tuple[str, str, str | None]:
    values = task.document.values
    title = str(values["title"]).casefold()
    if values["type"] == "epic":
        return "epic", "required", None
    if task.slug == "integrate-repository-task-management-and-openspec":
        return "feature", "required", None
    if values["area"] == "testing":
        return "chore", "not-required", "test-only"
    if values["area"] == "ci":
        return "chore", "not-required", "tooling-only"
    if any(word in title for word in ("research", "evaluate", "investigate", "survey")):
        return "research", "not-required", "research-only"
    if title.startswith(("fix ", "harden ", "repair ", "correct ")):
        return "bug", "required", None
    return "feature", "required", None


def criteria(body: str, title: str) -> list[tuple[bool, str]]:
    found: list[tuple[bool, str]] = []
    for line in body.splitlines():
        match = CHECKBOX_RE.match(line)
        if match is None:
            continue
        text = re.sub(r"\s+", " ", match.group(2)).strip()
        if text:
            found.append((match.group(1).casefold() == "x", text))
    return found or [(False, f"Implement {title} and verify its portfolio acceptance criteria.")]


def plain_text(value: str, limit: int = 180) -> str:
    text = re.sub(r"[`*_#]", "", value)
    text = re.sub(r"\[(.*?)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip(" .")
    if len(text) > limit:
        text = text[: limit - 1].rstrip() + "…"
    return text or "Complete the linked portfolio requirement"


def first_context(body: str, title: str) -> str:
    paragraphs = [
        re.sub(r"\s+", " ", paragraph).strip()
        for paragraph in re.split(r"\n\s*\n", body)
        if paragraph.strip() and not paragraph.lstrip().startswith(("#", "- ["))
    ]
    return plain_text(paragraphs[0], 500) if paragraphs else title


def step_priority(priority: str) -> str:
    return {"critical": " !crit", "high": " !high", "low": " !low"}.get(priority, "")


def render_tasks(task_id: str, title: str, kind: str, priority: str, checks: list[tuple[bool, str]], step_ids: list[str]) -> str:
    lines = [f"# {task_id}: {title}", "", "## Objective", "", title, "", "## Ownership", "", "Ownership is declared in the portfolio task and the implementation worktree before execution.", "", "## Execution", ""]
    for (done, text), step_id in zip(checks, step_ids, strict=True):
        mark = "x" if done else " "
        lines.append(
            f"- [{mark}] {step_id} {plain_text(text, 240)} #{kind}{step_priority(priority)} @item:{task_id}"
        )
    lines.extend(("", "## Verification", "", "Use the exact gates and evidence required by the portfolio task and `verification.md` when present.", ""))
    return "\n".join(lines)


def render_proposal(task_id: str, title: str, capability: str, context: str, area: str) -> str:
    return f"""# Change: {title}

Task ID: `{task_id}`

## Why

{context}

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `{capability}`: {title}

### Modified Capabilities

- None.

## Impact

- Portfolio area: `{area}`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
"""


def render_spec(task_id: str, title: str, context: str, checks: list[tuple[bool, str]]) -> str:
    lines = [
        "## Purpose",
        "",
        f"Define the observable completion contract for {title}. {context}",
        "",
        "## ADDED Requirements",
        "",
    ]
    used: set[str] = set()
    for index, (_, raw) in enumerate(checks, 1):
        criterion = plain_text(raw, 260)
        heading = plain_text(raw, 80)
        if heading in used:
            heading = f"{heading} ({index})"
        used.add(heading)
        lines.extend(
            (
                f"### Requirement: REQ-{task_id}-{index:03d} — {heading}",
                "",
                f"The RIPDPI implementation MUST satisfy this portfolio criterion: {criterion}.",
                "",
                f"#### Scenario: Verify criterion {index}",
                "",
                "- **WHEN** the linked change is exercised under the conditions defined by the portfolio task",
                f"- **THEN** the observed result MUST demonstrate that {criterion}",
                "",
            )
        )
    return "\n".join(lines)


def render_design(task_id: str, title: str, context: str, area: str) -> str:
    return f"""## Context

Portfolio task `{task_id}` owns this change. {context}

## Goals / Non-Goals

- Goal: deliver `{title}` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `{area}` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
"""


def render_verification(task_id: str, change: str, area: str, step_ids: list[str]) -> str:
    device_required = area in {"android", "ui", "vpn", "proxy", "service"}
    artifact_required = area == "android"
    values = {
        "task_id": task_id,
        "change": change,
        "commit_sha": None,
        "local": "required",
        "local_evidence": None,
        "remote_ci": "required",
        "remote_ci_evidence": None,
        "device": "required" if device_required else "not_applicable",
        "device_evidence": None if device_required else "No Android device behavior is owned by this portfolio area.",
        "artifact": "required" if artifact_required else "not_applicable",
        "artifact_evidence": None if artifact_required else "No distributable artifact is required for this portfolio area.",
        "deployment": "not_applicable",
        "deployment_evidence": "RIPDPI changes are not deployed by the task workflow.",
    }
    rows = [
        f"| REQ-{task_id}-{index:03d} | {step_id} | Pending | required |"
        for index, step_id in enumerate(step_ids, 1)
    ]
    body = "\n".join(
        (
            "# Verification",
            "",
            "## Requirement evidence",
            "",
            "| Requirement | Execution step | Evidence | Result |",
            "|---|---|---|---|",
            *rows,
            "",
        )
    )
    return taskctl.render_document(
        values,
        body,
        order=tuple(values),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=taskctl.DEFAULT_ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    legacy = [taskctl.read_document(path) for path in taskctl.issue_paths(root)]
    tasks = [LegacyTask(document=document, slug=document.path.stem) for document in legacy]
    if not tasks or any("id" in task.document.values for task in tasks):
        raise SystemExit("migration requires a non-empty all-legacy portfolio")

    status_before: dict[str, int] = {}
    for task in tasks:
        status = str(task.document.values["status"])
        status_before[status] = status_before.get(status, 0) + 1
    parent_edges_before = sum(task.document.values["parent"] is not None for task in tasks)
    body_hashes = {task.slug: taskctl.hashlib.sha256(task.document.body.encode()).hexdigest() for task in tasks}

    used_suffixes: set[str] = set()
    ids: dict[str, str] = {}
    for task in sorted(tasks, key=lambda item: item.slug):
        task_id = taskctl.allocate_id(root, str(task.document.values["area"]), used_suffixes)
        ids[task.slug] = task_id
        used_suffixes.add(taskctl.ID_RE.fullmatch(task_id).group(2))  # type: ignore[union-attr]

    step_ids: dict[str, list[str]] = {}
    checks_by_slug: dict[str, list[tuple[bool, str]]] = {}
    for task in sorted(tasks, key=lambda item: item.slug):
        checks = criteria(task.document.body, str(task.document.values["title"]))
        checks_by_slug[task.slug] = checks
        allocated: list[str] = []
        for _ in checks:
            step_id = taskctl.allocate_id(root, str(task.document.values["area"]), used_suffixes)
            used_suffixes.add(taskctl.ID_RE.fullmatch(step_id).group(2))  # type: ignore[union-attr]
            allocated.append(step_id)
        step_ids[task.slug] = allocated

    work_dir = root / "docs/tasks/work"
    work_dir.mkdir(parents=True, exist_ok=True)
    changes_dir = root / "openspec/changes"
    changes_dir.mkdir(parents=True, exist_ok=True)

    for task in tasks:
        old = task.document.values
        kind, spec_mode, spec_reason = classify(task)
        task_id = ids[task.slug]
        change = f"{task_id.casefold()}-{task.slug}" if spec_mode == "required" else None
        values = {
            "id": task_id,
            "title": old["title"],
            "kind": kind,
            "status": old["status"],
            "area": old["area"],
            "priority": old["priority"],
            "owner": old["owner"],
            "parent": ids.get(old["parent"]) if old["parent"] is not None else None,
            "blocked_by": [ids[value] for value in old["blocked_by"]],
            "spec_mode": spec_mode,
            "openspec_change": change,
            "created": old["created"],
            "updated": old["updated"],
        }
        if spec_reason is not None:
            values["spec_reason"] = spec_reason
        for optional in ("source_wiki_pages", "status_detail", "status_note"):
            if optional in old:
                values[optional] = old[optional]
        task.document.path.write_text(
            taskctl.render_document(values, task.document.body, preserve_body=True), encoding="utf-8"
        )
        task_text = render_tasks(
            task_id,
            str(old["title"]),
            kind,
            str(old["priority"]),
            checks_by_slug[task.slug],
            step_ids[task.slug],
        )
        if spec_mode == "not-required":
            (work_dir / f"{task_id}.md").write_text(task_text, encoding="utf-8")
            continue

        assert change is not None
        change_dir = changes_dir / change
        (change_dir / "specs" / task.slug).mkdir(parents=True, exist_ok=True)
        goal = f"{task_id} {old['title']}"
        (change_dir / ".openspec.yaml").write_text(
            "\n".join(
                (
                    "schema: ripdpi-change",
                    f"created: {json.dumps(old['created'], ensure_ascii=False)}",
                    f"goal: {json.dumps(goal, ensure_ascii=False)}",
                    f"affected_areas: [{json.dumps(old['area'], ensure_ascii=False)}]",
                    "skip_specs: false",
                    "retire_capabilities: false",
                    "",
                )
            ),
            encoding="utf-8",
        )
        context = first_context(task.document.body, str(old["title"]))
        (change_dir / "proposal.md").write_text(
            render_proposal(task_id, str(old["title"]), task.slug, context, str(old["area"])),
            encoding="utf-8",
        )
        (change_dir / "specs" / task.slug / "spec.md").write_text(
            render_spec(task_id, str(old["title"]), context, checks_by_slug[task.slug]),
            encoding="utf-8",
        )
        (change_dir / "design.md").write_text(
            render_design(task_id, str(old["title"]), context, str(old["area"])),
            encoding="utf-8",
        )
        (change_dir / "tasks.md").write_text(task_text, encoding="utf-8")
        (change_dir / "verification.md").write_text(
            render_verification(task_id, change, str(old["area"]), step_ids[task.slug]), encoding="utf-8"
        )

    migrated = [taskctl.read_document(path) for path in taskctl.issue_paths(root)]
    status_after: dict[str, int] = {}
    for document in migrated:
        status = str(document.values["status"])
        status_after[status] = status_after.get(status, 0) + 1
    if len(migrated) != len(tasks) or status_after != status_before:
        raise SystemExit("migration parity failed for task count or statuses")
    if sum(document.values["parent"] is not None for document in migrated) != parent_edges_before:
        raise SystemExit("migration parity failed for parent edges")
    for task in tasks:
        migrated_doc = next(item for item in migrated if item.path == task.document.path)
        if taskctl.hashlib.sha256(migrated_doc.body.encode()).hexdigest() != body_hashes[task.slug]:
            raise SystemExit(f"migration changed body bytes for {task.slug}")
    print(
        json.dumps(
            {
                "tasks": len(tasks),
                "statuses": status_after,
                "parents": parent_edges_before,
                "openspec": sum(document.values["spec_mode"] == "required" for document in migrated),
                "simple": sum(document.values["spec_mode"] == "not-required" for document in migrated),
                "steps": sum(len(value) for value in step_ids.values()),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
