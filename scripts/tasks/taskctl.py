#!/usr/bin/env python3
"""Fail-closed portfolio, mdtask, and OpenSpec workflow for RIPDPI."""

from __future__ import annotations

import argparse
import ast
import fcntl
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager, nullcontext
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any, Iterable, Sequence


DEFAULT_ROOT = Path(__file__).resolve().parents[2]
STATUS_ORDER = ("doing", "review", "blocked", "todo", "backlog")
STATUSES = frozenset((*STATUS_ORDER, "done", "dropped"))
KINDS = frozenset(("feature", "bug", "chore", "research", "epic"))
PRIORITIES = ("critical", "high", "medium", "low")
PRIORITY_ORDER = {value: index for index, value in enumerate(PRIORITIES)}
MDTASK_PRIORITY_TOKENS = {
    "critical": " !crit",
    "high": " !high",
    "medium": "",
    "low": " !low",
}
AREA_PREFIXES = {
    "engine": "ENG",
    "rust-native": "RST",
    "diagnostics": "DGN",
    "transport": "TRN",
    "outbound": "OUT",
    "dns": "DNS",
    "routing": "RTE",
    "vpn": "VPN",
    "proxy": "PRX",
    "relay": "RLY",
    "android": "AND",
    "ui": "UIX",
    "data": "DAT",
    "service": "SVC",
    "testing": "TST",
    "ci": "CIC",
    "epic": "EPC",
}
PREFIX_AREAS = {prefix: area for area, prefix in AREA_PREFIXES.items()}
SPEC_REASONS = frozenset(
    (
        "regression-tested-single-module",
        "test-only",
        "docs-only",
        "dependency-only",
        "mechanical-refactor",
        "tooling-only",
        "research-only",
    )
)
EVIDENCE_CATEGORIES = ("local", "remote_ci", "device", "artifact", "deployment")
EVIDENCE_STATES = frozenset(("required", "passed", "not_applicable", "blocked"))
SAFE_OPENSPEC_COMMANDS = frozenset(
    ("list", "show", "status", "instructions", "templates", "schemas", "schema", "validate", "doctor", "context", "new", "change", "spec")
)
ID_RE = re.compile(r"^([A-Z][A-Z0-9]*)-(\d{16})$")
STEP_RE = re.compile(r"^- \[([ xX])\] ([A-Z][A-Z0-9]*-\d{16})\s+(.+)$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CHANGE_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")

REQUIRED_FIELDS = (
    "id",
    "title",
    "kind",
    "status",
    "area",
    "priority",
    "owner",
    "parent",
    "blocked_by",
    "spec_mode",
    "openspec_change",
    "created",
    "updated",
)
OPTIONAL_FIELD_ORDER = (
    "spec_reason",
    "source_wiki_pages",
    "linked_task",
    "status_detail",
    "status_note",
    "closed_at",
    "closed_reason",
    "evidence_summary",
)
OPTIONAL_FIELDS = frozenset(OPTIONAL_FIELD_ORDER)
ALLOWED_FIELDS = frozenset((*REQUIRED_FIELDS, *OPTIONAL_FIELDS))


class ContractError(RuntimeError):
    """Raised when repository task state violates the contract."""


@dataclass(frozen=True)
class Document:
    path: Path
    values: dict[str, Any]
    body: str

    @property
    def task_id(self) -> str:
        return str(self.values["id"])


@dataclass(frozen=True)
class Step:
    path: Path
    line: int
    task_id: str
    done: bool
    text: str
    item_id: str | None
    blockers: tuple[str, ...]


def fail(message: str) -> None:
    raise ContractError(message)


def parse_scalar(raw: str) -> Any:
    value = raw.strip()
    if value in {"null", "~"}:
        return None
    if value == "[]":
        return []
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        if not inner:
            return []
        return [parse_scalar(item) for item in inner.split(",")]
    if value.startswith(("'", '"')):
        parsed = ast.literal_eval(value)
        if not isinstance(parsed, str):
            fail(f"expected quoted string, got {type(parsed).__name__}")
        return parsed
    return value


def read_document(path: Path) -> Document:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    if not lines or lines[0].rstrip("\r\n") != "---":
        fail(f"{path}: missing opening frontmatter delimiter")
    end = next(
        (index for index, line in enumerate(lines[1:], 1) if line.rstrip("\r\n") == "---"),
        None,
    )
    if end is None:
        fail(f"{path}: missing closing frontmatter delimiter")

    values: dict[str, Any] = {}
    index = 1
    while index < end:
        line = lines[index].rstrip("\r\n")
        if not line.strip():
            index += 1
            continue
        if line[0].isspace() or ":" not in line:
            fail(f"{path}:{index + 1}: unsupported frontmatter syntax")
        key, raw = line.split(":", 1)
        key = key.strip()
        if key in values:
            fail(f"{path}:{index + 1}: duplicate frontmatter key {key!r}")
        if raw.strip():
            values[key] = parse_scalar(raw)
            index += 1
            continue

        items: list[Any] = []
        index += 1
        while index < end:
            candidate = lines[index].rstrip("\r\n")
            match = re.match(r"^\s+-\s+(.+)$", candidate)
            if match is None:
                break
            items.append(parse_scalar(match.group(1)))
            index += 1
        values[key] = items

    return Document(path=path, values=values, body="".join(lines[end + 1 :]))


def quote_scalar(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, list):
        if not value:
            return "[]"
        return "[" + ", ".join(quote_scalar(item) for item in value) + "]"
    text = str(value)
    if not text or text != text.strip() or any(token in text for token in (":", "#", "[", "]", "{", "}")):
        return json.dumps(text, ensure_ascii=False)
    return text


def render_document(
    values: dict[str, Any],
    body: str,
    *,
    order: Sequence[str] | None = None,
    preserve_body: bool = False,
) -> str:
    ordered = list(order or REQUIRED_FIELDS)
    if order is None:
        ordered.extend(key for key in OPTIONAL_FIELD_ORDER if key in values)
    lines = ["---"]
    for key in ordered:
        value = values.get(key)
        if key == "source_wiki_pages" and isinstance(value, list) and value:
            lines.append(f"{key}:")
            lines.extend(f"  - {quote_scalar(item)}" for item in value)
        else:
            lines.append(f"{key}: {quote_scalar(value)}")
    lines.append("---")
    if preserve_body:
        return "\n".join(lines) + "\n" + body
    normalized_body = body.lstrip("\r\n").rstrip() + "\n"
    return "\n".join(lines) + "\n\n" + normalized_body


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.casefold()).strip("-")
    return slug or "task"


def git_common_dir(root: Path) -> Path | None:
    result = subprocess.run(
        ("git", "rev-parse", "--git-common-dir"),
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return None
    path = Path(result.stdout.strip())
    return path if path.is_absolute() else (root / path).resolve()


def allocate_id(root: Path, area: str, used_suffixes: set[str]) -> str:
    prefix = AREA_PREFIXES[area]
    common_dir = git_common_dir(root)
    reservation_path = common_dir / "taskctl-id-reservations" if common_dir else None

    def generate(reserved: set[str]) -> str:
        for _ in range(10_000):
            suffix = str(int(time.time() * 1000) * 1000 + random.SystemRandom().randrange(1000))
            if len(suffix) != 16:
                continue
            if suffix not in used_suffixes and suffix not in reserved:
                return suffix
        fail("unable to allocate a globally unique task ID")

    if reservation_path is None:
        return f"{prefix}-{generate(set())}"
    with reservation_path.open("a+", encoding="utf-8") as reservation:
        fcntl.flock(reservation.fileno(), fcntl.LOCK_EX)
        reservation.seek(0)
        reserved = {
            line.strip()
            for line in reservation
            if re.fullmatch(r"\d{16}", line.strip())
        }
        suffix = generate(reserved)
        reservation.write(suffix + "\n")
        reservation.flush()
        os.fsync(reservation.fileno())
        return f"{prefix}-{suffix}"


def issue_paths(root: Path) -> list[Path]:
    return sorted((root / "docs/tasks/issues").glob("*.md"))


def execution_paths(root: Path) -> list[Path]:
    return sorted((root / "docs/tasks/work").glob("*.md")) + sorted(
        path
        for path in (root / "openspec/changes").glob("*/tasks.md")
        if path.parent.name != "archive"
    )


def read_steps(path: Path) -> list[Step]:
    steps: list[Step] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = STEP_RE.match(line)
        if match is None:
            if re.match(r"^- \[[ xX]\]", line):
                fail(f"{path}:{number}: checkbox is not a valid mdtask record")
            continue
        text = match.group(3)
        item_match = re.search(r"(?:^|\s)@item:([^\s]+)", text)
        blockers = tuple(re.findall(r"(?:^|\s)@blocked_by:([^\s]+)", text))
        steps.append(
            Step(
                path=path,
                line=number,
                task_id=match.group(2),
                done=match.group(1).lower() == "x",
                text=text,
                item_id=item_match.group(1) if item_match else None,
                blockers=blockers,
            )
        )
    return steps


def lifecycle_receipt_path(root: Path, document: Document, execution: Path, kind: str) -> Path:
    if document.values["spec_mode"] == "required":
        return execution.parent / f".taskctl-{kind}.json"
    return execution.with_suffix(f".{kind}.json")


def read_lifecycle_receipt(
    root: Path,
    document: Document,
    execution: Path,
    kind: str,
) -> dict[str, Any]:
    path = lifecycle_receipt_path(root, document, execution, kind)
    if not path.is_file():
        fail(f"{document.path}: terminal {document.values['status']} state lacks taskctl {kind} receipt")
    receipt = json.loads(path.read_text(encoding="utf-8"))
    if receipt.get("task_id") != document.task_id:
        fail(f"{path}: receipt task backlink mismatch")
    if receipt.get("outcome") != document.values["status"]:
        fail(f"{path}: receipt outcome does not match terminal task")
    if document.values["spec_mode"] == "required" and receipt.get("change") != document.values["openspec_change"]:
        fail(f"{path}: receipt change backlink mismatch")
    if kind == "close":
        issue_hash = hashlib.sha256(document.path.read_bytes()).hexdigest()
        execution_hash = hashlib.sha256(execution.read_bytes()).hexdigest()
        if receipt.get("issue_sha256") != issue_hash or receipt.get("execution_sha256") != execution_hash:
            fail(f"{path}: close receipt content hashes do not match terminal state")
    return receipt


def validate_issue_shape(document: Document) -> None:
    values = document.values
    missing = [field for field in REQUIRED_FIELDS if field not in values]
    unknown = sorted(set(values) - ALLOWED_FIELDS)
    if missing:
        fail(f"{document.path}: missing fields: {', '.join(missing)}")
    if unknown:
        fail(f"{document.path}: unknown fields: {', '.join(unknown)}")
    for field in ("title", "owner", "created", "updated"):
        if not isinstance(values[field], str) or not values[field].strip():
            fail(f"{document.path}: {field} must be a non-empty string")
    if values["kind"] not in KINDS:
        fail(f"{document.path}: invalid kind {values['kind']!r}")
    if values["status"] not in STATUSES:
        fail(f"{document.path}: invalid status {values['status']!r}")
    if values["area"] not in AREA_PREFIXES:
        fail(f"{document.path}: invalid area {values['area']!r}")
    if values["priority"] not in PRIORITIES:
        fail(f"{document.path}: invalid priority {values['priority']!r}")
    if values["kind"] == "epic" and values["area"] != "epic":
        fail(f"{document.path}: epic kind requires epic area")
    if values["kind"] != "epic" and values["area"] == "epic":
        fail(f"{document.path}: epic area requires epic kind")
    match = ID_RE.fullmatch(str(values["id"]))
    if match is None:
        fail(f"{document.path}: invalid task ID {values['id']!r}")
    if PREFIX_AREAS.get(match.group(1)) != values["area"]:
        fail(f"{document.path}: ID prefix does not match area {values['area']!r}")
    for field in ("created", "updated"):
        raw_date = values[field]
        if not DATE_RE.fullmatch(raw_date):
            fail(f"{document.path}: {field} must use YYYY-MM-DD")
        try:
            date.fromisoformat(raw_date)
        except ValueError as error:
            fail(f"{document.path}: invalid {field}: {error}")
    if values["parent"] is not None and not isinstance(values["parent"], str):
        fail(f"{document.path}: parent must be an ID or null")
    if not isinstance(values["blocked_by"], list) or not all(
        isinstance(value, str) for value in values["blocked_by"]
    ):
        fail(f"{document.path}: blocked_by must be a list of IDs")
    if values["status"] == "blocked" and not values["blocked_by"] and not values.get("status_detail"):
        fail(f"{document.path}: blocked task requires blocked_by or status_detail")
    if values["spec_mode"] not in {"required", "not-required"}:
        fail(f"{document.path}: spec_mode must be required or not-required")
    change = values["openspec_change"]
    if values["spec_mode"] == "required":
        if not isinstance(change, str) or not CHANGE_RE.fullmatch(change):
            fail(f"{document.path}: required OpenSpec change must be lowercase kebab-case")
        if values.get("spec_reason") is not None:
            fail(f"{document.path}: required OpenSpec task cannot have spec_reason")
    else:
        if change is not None:
            fail(f"{document.path}: spec-not-required task cannot link an OpenSpec change")
        if values.get("spec_reason") not in SPEC_REASONS:
            fail(f"{document.path}: invalid or missing spec_reason")
        if values["kind"] in {"feature", "epic"}:
            fail(f"{document.path}: {values['kind']} cannot waive OpenSpec")
    for field in ("source_wiki_pages", "blocked_by"):
        value = values.get(field, [])
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            fail(f"{document.path}: {field} must be a string list")
    if values["status"] in {"done", "dropped"}:
        for field in ("closed_at", "closed_reason", "evidence_summary"):
            if not isinstance(values.get(field), str) or not values[field].strip():
                fail(f"{document.path}: terminal task requires {field}")
    elif any(field in values for field in ("closed_at", "closed_reason", "evidence_summary")):
        fail(f"{document.path}: non-terminal task cannot contain closure fields")


def assert_acyclic(edges: dict[str, list[str]], label: str) -> None:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, trail: list[str]) -> None:
        if node in visiting:
            fail(f"{label} cycle: {' -> '.join((*trail, node))}")
        if node in visited:
            return
        visiting.add(node)
        for target in edges.get(node, []):
            visit(target, [*trail, node])
        visiting.remove(node)
        visited.add(node)

    for node in edges:
        visit(node, [])


def evidence_values(path: Path) -> dict[str, Any]:
    document = read_document(path)
    required = {"task_id", "change", "commit_sha"}
    for category in EVIDENCE_CATEGORIES:
        required.update((category, f"{category}_evidence"))
    missing = sorted(required - document.values.keys())
    unknown = sorted(set(document.values) - required)
    if missing or unknown:
        fail(f"{path}: verification fields missing={missing}, unknown={unknown}")
    for category in EVIDENCE_CATEGORIES:
        state = document.values[category]
        if state not in EVIDENCE_STATES:
            fail(f"{path}: invalid {category} evidence state {state!r}")
        evidence = document.values[f"{category}_evidence"]
        if state in {"passed", "not_applicable", "blocked"} and (
            not isinstance(evidence, str) or not evidence.strip()
        ):
            fail(f"{path}: {category}_evidence required for {state}")
    return document.values


def validate_requirement_evidence(
    change_dir: Path,
    steps: Sequence[Step],
    *,
    archived: bool,
    dropped_step_ids: set[str] | None = None,
    allow_incomplete: bool = False,
) -> None:
    requirement_ids: list[str] = []
    for spec in sorted((change_dir / "specs").glob("**/*.md")):
        requirement_ids.extend(
            re.findall(r"(?m)^### Requirement: (REQ-[A-Z0-9-]+)(?:\s|$)", spec.read_text(encoding="utf-8"))
        )
    if not requirement_ids:
        fail(f"{change_dir}: no stable REQ-* requirement IDs")
    if len(requirement_ids) != len(set(requirement_ids)):
        fail(f"{change_dir}: duplicate requirement IDs")
    verification = read_document(change_dir / "verification.md")
    rows: dict[str, tuple[str, str, str]] = {}
    for line in verification.body.splitlines():
        match = re.match(
            r"^\|\s*(REQ-[A-Z0-9-]+)\s*\|\s*([A-Z][A-Z0-9]*-\d{16})\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|$",
            line,
        )
        if allow_incomplete and re.match(r"^\s*\|\s*REQ-", line) and match is None:
            fail(f"{verification.path}: malformed requirement evidence row")
        if match:
            if allow_incomplete and match.group(1) in rows:
                fail(f"{verification.path}: duplicate requirement evidence row")
            rows[match.group(1)] = (match.group(2), match.group(3).strip(), match.group(4).strip())
    if set(rows) - set(requirement_ids) or (not allow_incomplete and set(rows) != set(requirement_ids)):
        missing = sorted(set(requirement_ids) - set(rows))
        extra = sorted(set(rows) - set(requirement_ids))
        fail(f"{verification.path}: requirement evidence mismatch missing={missing}, extra={extra}")
    step_ids = {step.task_id for step in steps} | (dropped_step_ids or set())
    for requirement_id, (step_id, evidence, result) in rows.items():
        if step_id not in step_ids:
            fail(f"{verification.path}: {requirement_id} maps to unknown step {step_id}")
        if archived and (
            evidence.casefold() in {"pending", "required", "blocked"}
            or result.casefold() not in {"passed", "not_applicable"}
        ):
            fail(f"{verification.path}: {requirement_id} lacks resolved archive evidence")


def expected_execution_path(root: Path, document: Document) -> Path:
    if document.values["spec_mode"] == "required":
        change = document.values["openspec_change"]
        active = root / "openspec/changes" / change / "tasks.md"
        if active.is_file():
            return active
        archives = sorted((root / "openspec/changes/archive").glob(f"*-{change}"))
        if len(archives) > 1:
            fail(f"{document.path}: multiple archives found for {change}")
        if archives:
            if document.values["status"] not in {"review", "done", "dropped"}:
                fail(f"{document.path}: archived change requires review or terminal status")
            return archives[0] / "tasks.md"
        return active
    return root / "docs/tasks/work" / f"{document.task_id}.md"


def load_state(root: Path) -> tuple[list[Document], list[Step]]:
    return _load_state(root)


def _load_state(
    root: Path, *, authoring_task_id: str | None = None
) -> tuple[list[Document], list[Step]]:
    documents = [read_document(path) for path in issue_paths(root)]
    if not documents:
        fail("portfolio is empty")
    for document in documents:
        validate_issue_shape(document)

    ids: dict[str, Document] = {}
    suffixes: dict[str, str] = {}
    changes: dict[str, str] = {}
    for document in documents:
        if document.task_id in ids:
            fail(f"duplicate portfolio ID {document.task_id}")
        ids[document.task_id] = document
        suffix = ID_RE.fullmatch(document.task_id).group(2)  # type: ignore[union-attr]
        if suffix in suffixes:
            fail(f"numeric ID suffix {suffix} reused by {suffixes[suffix]} and {document.task_id}")
        suffixes[suffix] = document.task_id
        change = document.values["openspec_change"]
        if change is not None:
            if change in changes:
                fail(f"OpenSpec change {change!r} linked by {changes[change]} and {document.task_id}")
            changes[change] = document.task_id

    parent_edges: dict[str, list[str]] = {}
    blocker_edges: dict[str, list[str]] = {}
    for document in documents:
        parent = document.values["parent"]
        if parent is not None:
            if parent == document.task_id or parent not in ids:
                fail(f"{document.path}: missing or self parent {parent!r}")
            if ids[parent].values["kind"] != "epic":
                fail(f"{document.path}: parent {parent} is not an epic")
            parent_edges[document.task_id] = [parent]
        blockers = document.values["blocked_by"]
        for blocker in blockers:
            if blocker == document.task_id or blocker not in ids:
                fail(f"{document.path}: missing or self blocker {blocker!r}")
        blocker_edges[document.task_id] = list(blockers)
        linked = document.values.get("linked_task")
        if isinstance(linked, str) and ID_RE.fullmatch(linked) and linked not in ids:
            fail(f"{document.path}: linked_task {linked!r} does not exist")
    assert_acyclic(parent_edges, "parent")
    assert_acyclic(blocker_edges, "blocker")

    discovered_paths = set(execution_paths(root))
    expected_paths: set[Path] = set()
    all_steps: list[Step] = []
    all_dropped_step_ids: dict[str, str] = {}
    for document in documents:
        path = expected_execution_path(root, document)
        authoring = document.task_id == authoring_task_id
        expected_paths.add(path)
        if not path.is_file() and not authoring:
            fail(f"{document.path}: missing execution file {path.relative_to(root)}")
        steps = read_steps(path) if path.is_file() else []
        drop_receipt: dict[str, Any] | None = None
        dropped_step_ids: set[str] = set()
        if document.values["status"] in {"done", "dropped"}:
            read_lifecycle_receipt(root, document, path, "close")
        if document.values["status"] == "dropped":
            drop_receipt = read_lifecycle_receipt(root, document, path, "drop")
            raw_dropped_ids = drop_receipt.get("dropped_step_ids")
            if not isinstance(raw_dropped_ids, list) or not all(
                isinstance(item, str) and ID_RE.fullmatch(item) for item in raw_dropped_ids
            ):
                fail(f"{lifecycle_receipt_path(root, document, path, 'drop')}: invalid dropped_step_ids")
            dropped_step_ids = set(raw_dropped_ids)
            for dropped_step_id in dropped_step_ids:
                prefix = ID_RE.fullmatch(dropped_step_id).group(1)  # type: ignore[union-attr]
                if PREFIX_AREAS.get(prefix) != document.values["area"]:
                    fail(f"{document.path}: dropped step prefix does not match task area")
                if dropped_step_id in all_dropped_step_ids:
                    fail(f"duplicate dropped execution step ID {dropped_step_id}")
                all_dropped_step_ids[dropped_step_id] = document.task_id
        if not steps and document.values["status"] != "dropped" and not authoring:
            fail(f"{path}: no mdtask execution steps")
        for step in steps:
            if step.item_id != document.task_id:
                fail(f"{path}:{step.line}: @item must be {document.task_id}")
        if document.values["status"] in {"done", "dropped"} and any(not step.done for step in steps):
            fail(f"{document.path}: terminal task contains open execution steps")
        all_steps.extend(steps)
        if document.values["spec_mode"] == "required":
            change_dir = path.parent
            proposal = change_dir / "proposal.md"
            verification = change_dir / "verification.md"
            metadata = change_dir / ".openspec.yaml"
            for required_path in (proposal, verification, metadata):
                if authoring and required_path == verification and not verification.exists():
                    continue
                if not required_path.is_file():
                    fail(f"{document.path}: missing {required_path.relative_to(root)}")
            if f"Task ID: `{document.task_id}`" not in proposal.read_text(encoding="utf-8"):
                fail(f"{proposal}: missing exact portfolio backlink")
            if authoring and not verification.exists():
                continue
            evidence = evidence_values(verification)
            if evidence["task_id"] != document.task_id or evidence["change"] != document.values["openspec_change"]:
                fail(f"{verification}: backlink does not match portfolio task")
            archived = change_dir.parent.name == "archive"
            validate_requirement_evidence(
                change_dir,
                steps,
                archived=archived,
                dropped_step_ids=dropped_step_ids,
                allow_incomplete=authoring,
            )
            if document.values["status"] == "done" and not archived:
                fail(f"{document.path}: done task requires an archived OpenSpec change")
            if archived:
                if any(not step.done for step in steps):
                    fail(f"{change_dir}: archived change contains open execution steps")
                if any(evidence[category] in {"required", "blocked"} for category in EVIDENCE_CATEGORIES):
                    fail(f"{verification}: archived change contains incomplete evidence")
                if not isinstance(evidence["commit_sha"], str) or not SHA_RE.fullmatch(evidence["commit_sha"]):
                    fail(f"{verification}: archived change lacks an exact commit SHA")
                receipt_path = change_dir / ".taskctl-archive.json"
                if not receipt_path.is_file():
                    fail(f"{change_dir}: archive was not created by taskctl")
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                if receipt.get("task_id") != document.task_id or receipt.get("change") != document.values["openspec_change"]:
                    fail(f"{receipt_path}: archive receipt backlink mismatch")
    extras = discovered_paths - expected_paths
    if extras:
        fail("orphan execution files: " + ", ".join(str(path.relative_to(root)) for path in sorted(extras)))

    step_ids: dict[str, Step] = {}
    for step in all_steps:
        match = ID_RE.fullmatch(step.task_id)
        if match is None:
            fail(f"{step.path}:{step.line}: invalid step ID {step.task_id}")
        suffix = match.group(2)
        if suffix in suffixes:
            fail(f"numeric ID suffix {suffix} reused by {suffixes[suffix]} and {step.task_id}")
        suffixes[suffix] = step.task_id
        if step.task_id in step_ids:
            fail(f"duplicate execution step ID {step.task_id}")
        step_ids[step.task_id] = step
    for dropped_step_id, item_id in all_dropped_step_ids.items():
        suffix = ID_RE.fullmatch(dropped_step_id).group(2)  # type: ignore[union-attr]
        if suffix in suffixes:
            fail(f"numeric ID suffix {suffix} reused by {suffixes[suffix]} and {dropped_step_id}")
        suffixes[suffix] = dropped_step_id
        if dropped_step_id in step_ids:
            fail(f"dropped execution step ID {dropped_step_id} is still active for {item_id}")
    for step in all_steps:
        for blocker in step.blockers:
            if blocker not in step_ids:
                fail(f"{step.path}:{step.line}: missing step blocker {blocker}")
    assert_acyclic(
        {step.task_id: list(step.blockers) for step in all_steps},
        "execution blocker",
    )
    return documents, all_steps


def progress_by_item(steps: Iterable[Step]) -> dict[str, tuple[int, int]]:
    totals: dict[str, list[int]] = {}
    for step in steps:
        if step.item_id is None:
            continue
        progress = totals.setdefault(step.item_id, [0, 0])
        progress[1] += 1
        if step.done:
            progress[0] += 1
    return {task_id: (values[0], values[1]) for task_id, values in totals.items()}


def escape_cell(value: Any) -> str:
    return str(value).replace("|", "\\|")


def render_board(root: Path, documents: list[Document], steps: list[Step]) -> str:
    progress = progress_by_item(steps)
    reverse: dict[str, list[str]] = {document.task_id: [] for document in documents}
    for document in documents:
        for blocker in document.values["blocked_by"]:
            reverse[blocker].append(document.task_id)
    lines = [
        "# Task Board",
        "",
        "_Generated by `taskctl`. Do not edit by hand._",
        "",
    ]
    for status in STATUS_ORDER:
        rows = [document for document in documents if document.values["status"] == status]
        rows.sort(
            key=lambda document: (
                PRIORITY_ORDER[document.values["priority"]],
                document.values["area"],
                document.values["title"].casefold(),
            )
        )
        lines.extend(
            (
                f"## {status.title()} ({len(rows)})",
                "",
                "| ID | Priority | Area | Kind | Issue | Owner | Progress | OpenSpec | Blocked by | Blocks | Updated |",
                "|---|---|---|---|---|---|---|---|---|---|---|",
            )
        )
        for document in rows:
            values = document.values
            relative = document.path.relative_to(root / "docs/tasks").as_posix()
            done, total = progress.get(document.task_id, (0, 0))
            change = values["openspec_change"] or "—"
            blockers = ", ".join(values["blocked_by"]) or "—"
            blocks = ", ".join(sorted(reverse[document.task_id])) or "—"
            cells = (
                document.task_id,
                values["priority"],
                values["area"],
                values["kind"],
                f"[{values['title']}]({relative})",
                values["owner"],
                f"{done}/{total}",
                change,
                blockers,
                blocks,
                values["updated"],
            )
            lines.append("| " + " | ".join(escape_cell(cell) for cell in cells) + " |")
        lines.append("")
    return "\n".join(lines)


def run_command(command: Sequence[str], *, root: Path, capture: bool = True) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["OPENSPEC_TELEMETRY"] = "0"
    return subprocess.run(
        command,
        cwd=root,
        env=env,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
    )


def tool_binary(root: Path, name: str) -> Path:
    binary = root / "tools/tasking/node_modules/.bin" / name
    if not binary.exists():
        fail(f"missing pinned {name}; run npm ci --prefix tools/tasking --ignore-scripts")
    return binary


def validate_upstreams(root: Path, documents: list[Document]) -> None:
    mdtask = tool_binary(root, "mdtask")
    for args in (("validate",), ("list", "--all")):
        result = run_command((str(mdtask), *args), root=root)
        output = result.stdout or ""
        if result.returncode != 0 or "warning:" in output.casefold() or "without ids" in output.casefold():
            fail(f"mdtask {' '.join(args)} failed:\n{output.rstrip()}")
    if any(document.values["spec_mode"] == "required" for document in documents):
        openspec = tool_binary(root, "openspec")
        commands = (
            ("schema", "validate", "ripdpi-change", "--json"),
            ("validate", "--all", "--strict", "--no-interactive"),
        )
        for args in commands:
            result = run_command((str(openspec), *args), root=root)
            if result.returncode != 0:
                fail(f"openspec {' '.join(args)} failed:\n{(result.stdout or '').rstrip()}")


def validate_generated_assets(root: Path) -> None:
    lock_path = root / "tools/tasking/generated-assets.lock.json"
    if not lock_path.is_file():
        fail("missing tools/tasking/generated-assets.lock.json")
    payload = json.loads(lock_path.read_text(encoding="utf-8"))
    if payload.get("mdtask") != "0.1.17" or payload.get("openspec") != "1.9.0":
        fail("generated asset lock does not match pinned tool versions")
    files = payload.get("files")
    if not isinstance(files, dict) or not files:
        fail("generated asset lock contains no files")
    for relative, expected in files.items():
        path = root / relative
        if not path.is_file():
            fail(f"generated asset missing: {relative}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            fail(f"generated asset drift: {relative}")


def validate_deleted_history(root: Path, base: str) -> None:
    result = run_command(
        ("git", "diff", "--diff-filter=D", "--name-only", f"{base}..HEAD", "--", "docs/tasks/issues/*.md"),
        root=root,
    )
    if result.returncode != 0:
        fail(f"cannot inspect deleted task history: {(result.stdout or '').rstrip()}")
    for relative in filter(None, (result.stdout or "").splitlines()):
        deletion = run_command(
            ("git", "log", "-1", "--diff-filter=D", "--format=%H", f"{base}..HEAD", "--", relative),
            root=root,
        )
        deletion_sha = (deletion.stdout or "").strip()
        if not SHA_RE.fullmatch(deletion_sha):
            fail(f"{relative}: cannot resolve deletion commit")
        previous_ref = f"{deletion_sha}^"
        previous = run_command(("git", "show", f"{previous_ref}:{relative}"), root=root)
        if previous.returncode != 0:
            fail(f"{relative}: no committed state before deletion")

        with tempfile.TemporaryDirectory(prefix="ripdpi-task-history-") as directory:
            scratch = Path(directory)

            def document_at(ref: str) -> tuple[Document, str] | None:
                shown = run_command(("git", "show", f"{ref}:{relative}"), root=root)
                if shown.returncode != 0:
                    return None
                issue = scratch / f"issue-{len(list(scratch.glob('issue-*')))}.md"
                issue.write_text(shown.stdout or "", encoding="utf-8")
                return read_document(issue), shown.stdout or ""

            final_snapshot = document_at(previous_ref)
            assert final_snapshot is not None
            final_document, _ = final_snapshot
            outcome = final_document.values.get("status")
            if outcome not in {"done", "dropped"}:
                fail(f"{relative}: deleted without a preceding committed terminal state")
            for field in ("closed_at", "closed_reason", "evidence_summary"):
                if not final_document.values.get(field):
                    fail(f"{relative}: terminal state before deletion lacks {field}")

            base_snapshot = document_at(base)
            terminal_ref: str | None = None
            prior_status: str | None = None
            history = run_command(
                ("git", "log", "--reverse", "--format=%H", f"{base}..{previous_ref}", "--", relative),
                root=root,
            )
            snapshots = [(base, base_snapshot)]
            snapshots.extend(
                (commit, document_at(commit))
                for commit in filter(None, (history.stdout or "").splitlines())
            )
            last_absent = max(
                (index for index, (_, snapshot) in enumerate(snapshots) if snapshot is None),
                default=-1,
            )
            if (
                last_absent == -1
                and base_snapshot
                and base_snapshot[0].values.get("status") in {"done", "dropped"}
            ):
                terminal_ref = base
            else:
                for commit, snapshot in snapshots[last_absent + 1 :]:
                    assert snapshot is not None
                    status = snapshot[0].values.get("status")
                    if status in {"done", "dropped"}:
                        terminal_ref = commit
                        break
                    prior_status = status
            if terminal_ref is None:
                fail(f"{relative}: terminal transition commit is missing")
            if outcome == "done" and prior_status != "review" and terminal_ref != base:
                fail(f"{relative}: invalid terminal transition {prior_status or 'missing'} -> done")
            if outcome == "dropped" and terminal_ref != base and (
                prior_status not in STATUSES or not transition_allowed(prior_status, "dropped")
            ):
                fail(f"{relative}: invalid terminal transition {prior_status or 'missing'} -> dropped")

            terminal_snapshot = document_at(terminal_ref)
            assert terminal_snapshot is not None
            terminal_document, terminal_text = terminal_snapshot
            if terminal_document.values.get("status") != outcome:
                fail(f"{relative}: terminal transition does not match deleted outcome")

            if terminal_document.values.get("spec_mode") == "required":
                change = terminal_document.values.get("openspec_change")
                tree = run_command(
                    ("git", "ls-tree", "-r", "--name-only", terminal_ref, "--", "openspec/changes"),
                    root=root,
                )
                execution_candidates = [
                    item
                    for item in (tree.stdout or "").splitlines()
                    if item == f"openspec/changes/{change}/tasks.md"
                    or (item.endswith(f"-{change}/tasks.md") and "/archive/" in item)
                ]
                if len(execution_candidates) != 1:
                    fail(f"{relative}: terminal commit has no unique OpenSpec execution snapshot")
                execution_path = execution_candidates[0]
                receipt_root = execution_path.rsplit("/", 1)[0]
                close_receipt_path = f"{receipt_root}/.taskctl-close.json"
            else:
                execution_path = f"docs/tasks/work/{terminal_document.task_id}.md"
                receipt_root = f"docs/tasks/work/{terminal_document.task_id}"
                close_receipt_path = f"{receipt_root}.close.json"

            execution_result = run_command(("git", "show", f"{terminal_ref}:{execution_path}"), root=root)
            if execution_result.returncode != 0:
                fail(f"{relative}: terminal execution snapshot is missing")
            execution_file = scratch / "execution.md"
            execution_file.write_text(execution_result.stdout or "", encoding="utf-8")
            terminal_steps = read_steps(execution_file)
            if outcome == "done" and not terminal_steps:
                fail(f"{relative}: completed task has no execution steps")
            if any(not step.done for step in terminal_steps):
                fail(f"{relative}: terminal commit contains open execution steps")

            close_result = run_command(("git", "show", f"{terminal_ref}:{close_receipt_path}"), root=root)
            if close_result.returncode != 0:
                fail(f"{relative}: close receipt is missing from terminal commit")
            close_receipt = json.loads(close_result.stdout or "{}")
            if (
                close_receipt.get("schema") != 1
                or close_receipt.get("task_id") != terminal_document.task_id
                or close_receipt.get("outcome") != outcome
                or close_receipt.get("issue_sha256")
                != hashlib.sha256(terminal_text.encode()).hexdigest()
                or close_receipt.get("execution_sha256")
                != hashlib.sha256((execution_result.stdout or "").encode()).hexdigest()
            ):
                fail(f"{relative}: close receipt does not match the terminal snapshot")
            if outcome == "dropped":
                drop_receipt_path = (
                    f"{receipt_root}/.taskctl-drop.json"
                    if terminal_document.values.get("spec_mode") == "required"
                    else f"{receipt_root}.drop.json"
                )
                drop_result = run_command(("git", "show", f"{terminal_ref}:{drop_receipt_path}"), root=root)
                if drop_result.returncode != 0:
                    fail(f"{relative}: drop receipt is missing from terminal commit")
                drop_receipt = json.loads(drop_result.stdout or "{}")
                if drop_receipt.get("task_id") != terminal_document.task_id or drop_receipt.get("outcome") != "dropped":
                    fail(f"{relative}: invalid drop receipt in terminal commit")

            if terminal_document.values.get("spec_mode") == "required":
                change = terminal_document.values.get("openspec_change")
                tree = run_command(
                    ("git", "ls-tree", "-r", "--name-only", deletion_sha, "--", "openspec/changes/archive"),
                    root=root,
                )
                archive_roots = {
                    item.rsplit("/", 1)[0]
                    for item in (tree.stdout or "").splitlines()
                    if item.endswith(f"-{change}/.taskctl-archive.json")
                }
                if len(archive_roots) != 1:
                    fail(f"{relative}: terminal OpenSpec change was not archived before deletion")
                archive_root = next(iter(archive_roots))
                archived_close_result = run_command(
                    ("git", "show", f"{deletion_sha}:{archive_root}/.taskctl-close.json"), root=root
                )
                if archived_close_result.returncode != 0 or json.loads(
                    archived_close_result.stdout or "{}"
                ) != close_receipt:
                    fail(f"{relative}: archived close receipt differs from terminal commit")
                archive_result = run_command(
                    ("git", "show", f"{deletion_sha}:{archive_root}/.taskctl-archive.json"), root=root
                )
                if archive_result.returncode != 0:
                    fail(f"{relative}: archived lifecycle receipt missing: .taskctl-archive.json")
                archive_receipt = json.loads(archive_result.stdout or "{}")
                expected_archive_outcome = "dropped" if outcome == "dropped" else "review"
                if (
                    archive_receipt.get("schema") != 1
                    or archive_receipt.get("task_id") != terminal_document.task_id
                    or archive_receipt.get("change") != change
                    or archive_receipt.get("outcome") != expected_archive_outcome
                    or not SHA_RE.fullmatch(str(archive_receipt.get("commit_sha", "")))
                ):
                    fail(f"{relative}: invalid OpenSpec archive receipt")
                verification_result = run_command(
                    ("git", "show", f"{deletion_sha}:{archive_root}/verification.md"), root=root
                )
                if verification_result.returncode != 0:
                    fail(f"{relative}: archived verification record is missing")
                verification_file = scratch / "archived-verification.md"
                verification_file.write_text(verification_result.stdout or "", encoding="utf-8")
                verification = read_document(verification_file)
                if (
                    verification.values.get("task_id") != terminal_document.task_id
                    or verification.values.get("change") != change
                    or verification.values.get("commit_sha") != archive_receipt.get("commit_sha")
                ):
                    fail(f"{relative}: archive receipt does not match verification record")
                if outcome == "dropped":
                    archived_drop_result = run_command(
                        ("git", "show", f"{deletion_sha}:{archive_root}/.taskctl-drop.json"), root=root
                    )
                    if archived_drop_result.returncode != 0 or json.loads(
                        archived_drop_result.stdout or "{}"
                    ) != drop_receipt:
                        fail(f"{relative}: archived drop receipt differs from terminal commit")


def validate_repository(root: Path, *, base: str | None, upstreams: bool = True) -> tuple[list[Document], list[Step]]:
    documents, steps = load_state(root)
    expected = render_board(root, documents, steps)
    board = root / "docs/tasks/board.md"
    if not board.is_file() or board.read_text(encoding="utf-8") != expected:
        fail("docs/tasks/board.md is stale; run ./taskctl generate-board")
    validate_generated_assets(root)
    if upstreams:
        validate_upstreams(root, documents)
    if base:
        validate_deleted_history(root, base)
    return documents, steps


def find_document(documents: Sequence[Document], query: str) -> Document:
    exact = [document for document in documents if document.task_id == query]
    if exact:
        return exact[0]
    suffix = [document for document in documents if document.task_id.endswith(f"-{query}")]
    if len(suffix) == 1:
        return suffix[0]
    slug = [document for document in documents if document.path.stem == query]
    if len(slug) == 1:
        return slug[0]
    fail(f"task query {query!r} is missing or ambiguous")


def write_document(document: Document, values: dict[str, Any]) -> None:
    document.path.write_text(render_document(values, document.body), encoding="utf-8")


def transition_allowed(current: str, target: str) -> bool:
    return target in {
        "backlog": {"todo", "dropped"},
        "todo": {"backlog", "doing", "blocked", "dropped"},
        "doing": {"review", "blocked", "todo", "dropped"},
        "review": {"doing", "blocked", "done", "dropped"},
        "blocked": {"todo", "doing", "dropped"},
        "done": set(),
        "dropped": set(),
    }[current]


def command_generate_board(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    board = args.root / "docs/tasks/board.md"
    board.write_text(render_board(args.root, documents, steps), encoding="utf-8")
    print(f"Generated {board.relative_to(args.root)} from {len(documents)} tasks")
    return 0


def command_validate(args: argparse.Namespace) -> int:
    documents, steps = validate_repository(args.root, base=args.base, upstreams=not args.skip_upstreams)
    payload = {"schema": 1, "tasks": len(documents), "steps": len(steps), "valid": True}
    print(json.dumps(payload, sort_keys=True) if args.json else f"Task contracts valid ({len(documents)} tasks, {len(steps)} steps)")
    return 0


def command_list(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    progress = progress_by_item(steps)
    selected = [
        document
        for document in documents
        if (not args.status or document.values["status"] in args.status)
        and (not args.area or document.values["area"] in args.area)
        and (not args.kind or document.values["kind"] in args.kind)
    ]
    rows = [
        {
            **document.values,
            "path": str(document.path.relative_to(args.root)),
            "progress": {"done": progress[document.task_id][0], "total": progress[document.task_id][1]},
        }
        for document in selected
    ]
    if args.json:
        print(json.dumps({"schema": 1, "tasks": rows}, ensure_ascii=False, sort_keys=True))
    else:
        for row in rows:
            print(f"{row['id']}\t{row['status']}\t{row['priority']}\t{row['title']}\t{row['progress']['done']}/{row['progress']['total']}")
    return 0


def command_show(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    document = find_document(documents, args.query)
    item_steps = [step for step in steps if step.item_id == document.task_id]
    if args.json:
        print(
            json.dumps(
                {
                    "schema": 1,
                    "task": document.values,
                    "path": str(document.path.relative_to(args.root)),
                    "execution": str(expected_execution_path(args.root, document).relative_to(args.root)),
                    "steps": [step.__dict__ | {"path": str(step.path.relative_to(args.root))} for step in item_steps],
                },
                ensure_ascii=False,
                default=str,
                sort_keys=True,
            )
        )
    else:
        print(document.path.read_text(encoding="utf-8"), end="")
        print(f"\nExecution: {expected_execution_path(args.root, document).relative_to(args.root)}")
    return 0


def command_ready(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    by_id = {document.task_id: document for document in documents}
    ready = [
        document
        for document in documents
        if document.values["status"] in {"backlog", "todo"}
        and all(by_id[blocker].values["status"] == "done" for blocker in document.values["blocked_by"])
    ]
    if args.json:
        print(json.dumps({"schema": 1, "ready": [document.values for document in ready]}, ensure_ascii=False, sort_keys=True))
    else:
        for document in ready:
            print(f"{document.task_id}\t{document.values['priority']}\t{document.values['title']}")
    return 0


def command_graph(args: argparse.Namespace) -> int:
    documents, _ = load_state(args.root)
    reverse: dict[str, list[str]] = {document.task_id: [] for document in documents}
    for document in documents:
        for blocker in document.values["blocked_by"]:
            reverse[blocker].append(document.task_id)
    graph = [
        {
            "id": document.task_id,
            "parent": document.values["parent"],
            "blocked_by": document.values["blocked_by"],
            "blocks": sorted(reverse[document.task_id]),
        }
        for document in documents
    ]
    if args.json:
        print(json.dumps({"schema": 1, "graph": graph}, sort_keys=True))
    else:
        for node in graph:
            print(f"{node['id']}: blocked_by={','.join(node['blocked_by']) or '-'} blocks={','.join(node['blocks']) or '-'}")
    return 0


def command_new(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    used = {
        ID_RE.fullmatch(document.values["id"]).group(2)  # type: ignore[union-attr]
        for document in documents
        if ID_RE.fullmatch(str(document.values.get("id", "")))
    }
    used.update(
        ID_RE.fullmatch(step.task_id).group(2)  # type: ignore[union-attr]
        for step in steps
    )
    if args.spec_mode == "not-required" and not args.spec_reason:
        fail("spec-not-required task requires --spec-reason")
    if args.spec_mode == "not-required" and args.kind in {"feature", "epic"}:
        fail(f"{args.kind} cannot waive OpenSpec")
    task_id = allocate_id(args.root, args.area, used)
    slug = args.slug or slugify(args.title)
    path = args.root / "docs/tasks/issues" / f"{slug}.md"
    if path.exists():
        fail(f"task file already exists: {path.relative_to(args.root)}")
    if args.spec_mode == "required":
        change = args.openspec_change or f"{task_id.casefold()}-{slug}"
        spec_reason = None
    else:
        change = None
        spec_reason = args.spec_reason
    today = date.today().isoformat()
    values = {
        "id": task_id,
        "title": args.title,
        "kind": args.kind,
        "status": "backlog",
        "area": args.area,
        "priority": args.priority,
        "owner": args.owner,
        "parent": args.parent,
        "blocked_by": [],
        "spec_mode": args.spec_mode,
        "openspec_change": change,
        "created": today,
        "updated": today,
    }
    if spec_reason is not None:
        values["spec_reason"] = spec_reason
    document = Document(path=path, values=values, body="## Goal\n\nDescribe the observable outcome.\n\n## Acceptance criteria\n\nDefine verifiable completion criteria.\n")
    path.write_text(render_document(values, document.body), encoding="utf-8")
    if args.spec_mode == "not-required":
        work = args.root / "docs/tasks/work" / f"{task_id}.md"
        work.parent.mkdir(parents=True, exist_ok=True)
        step_id = allocate_id(args.root, args.area, used | {ID_RE.fullmatch(task_id).group(2)})  # type: ignore[union-attr]
        work.write_text(
            f"# {task_id}: {args.title}\n\n## Objective\n\n{args.title}\n\n## Ownership\n\nDeclare owned paths before implementation.\n\n## Execution\n\n- [ ] {step_id} Define implementation and verification #{args.kind}{MDTASK_PRIORITY_TOKENS[args.priority]} @item:{task_id}\n",
            encoding="utf-8",
        )
    else:
        openspec = tool_binary(args.root, "openspec")
        result = run_command(
            (
                str(openspec),
                "new",
                "change",
                change,
                "--schema",
                "ripdpi-change",
                "--goal",
                f"{task_id} {args.title}",
                "--json",
            ),
            root=args.root,
        )
        if result.returncode != 0:
            path.unlink(missing_ok=True)
            fail(f"OpenSpec scaffold failed:\n{(result.stdout or '').rstrip()}")
    print(f"Created {task_id} at {path.relative_to(args.root)}")
    return 0


def command_start(args: argparse.Namespace) -> int:
    documents, _ = load_state(args.root)
    document = find_document(documents, args.query)
    current = document.values["status"]
    if current not in {"backlog", "todo", "blocked"}:
        fail(f"cannot start task from {current}")
    values = dict(document.values)
    values["status"] = "doing"
    values["owner"] = args.owner or values["owner"]
    values["updated"] = date.today().isoformat()
    write_document(document, values)
    print(f"Started {document.task_id}")
    return 0


def command_transition(args: argparse.Namespace) -> int:
    documents, _ = load_state(args.root)
    document = find_document(documents, args.query)
    current = document.values["status"]
    if args.status in {"done", "dropped"}:
        fail("use taskctl close prepare for terminal transitions")
    if not transition_allowed(current, args.status):
        fail(f"invalid transition {current} -> {args.status}")
    values = dict(document.values)
    values["status"] = args.status
    values["updated"] = date.today().isoformat()
    if args.detail is not None:
        values["status_detail"] = args.detail
    write_document(document, values)
    print(f"Transitioned {document.task_id}: {current} -> {args.status}")
    return 0


@contextmanager
def steps_write_lock(root: Path):
    # Linked worktrees share this inode. Never unlink it: another writer may
    # already be blocked on the same lock.
    lock = (git_common_dir(root) or root) / "taskctl-steps.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "a") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield


def require_owned_execution_path(root: Path, path: Path) -> None:
    for candidate in (path, *path.parents):
        if candidate == root:
            break
        if candidate.is_symlink():
            fail(f"execution authoring refuses symlink: {candidate}")
    if path.exists() and not path.is_file():
        fail(f"execution authoring requires a regular file: {path}")


def validate_step_planning(root: Path, document: Document) -> None:
    change = root / "openspec/changes" / document.values["openspec_change"]
    for path in (change / "proposal.md", change / "design.md", change / ".openspec.yaml"):
        require_owned_execution_path(root, path)
        if not path.is_file() or not path.read_text(encoding="utf-8").strip():
            fail(f"step authoring requires nonempty {path.relative_to(root)}")
    require_owned_execution_path(root, change / "verification.md")
    proposal = (change / "proposal.md").read_text(encoding="utf-8")
    if f"Task ID: `{document.task_id}`" not in proposal:
        fail("step authoring proposal lacks exact portfolio backlink")
    specs = sorted((change / "specs").glob("**/*.md"))
    if not specs:
        fail("step authoring requires delta specs")
    for path in specs:
        require_owned_execution_path(root, path)
    requirements = [
        requirement
        for path in specs
        for requirement in re.findall(
            r"(?m)^### Requirement: (REQ-[A-Z0-9-]+)(?:\s|$)",
            path.read_text(encoding="utf-8"),
        )
    ]
    if not requirements or len(requirements) != len(set(requirements)):
        fail("step authoring requires unique stable REQ-* requirements")
    openspec = str(tool_binary(root, "openspec"))
    status = run_command((openspec, "status", "--change", change.name, "--json"), root=root)
    if status.returncode != 0:
        fail(f"OpenSpec planning status failed:\n{status.stdout}")
    planning = json.loads(status.stdout)
    if planning.get("schemaName") != "ripdpi-change":
        fail("step authoring requires the repository OpenSpec schema")
    completed = {
        item["id"] for item in planning["artifacts"] if item["status"] == "done"
    }
    if not {"proposal", "specs", "design"} <= completed:
        fail("step authoring requires complete proposal, specs and design")
    result = run_command((openspec, "validate", change.name, "--strict", "--json"), root=root)
    if result.returncode != 0:
        fail(f"OpenSpec planning validation failed:\n{result.stdout}")


def command_steps_add(args: argparse.Namespace) -> int:
    parser = argparse.ArgumentParser(prog="taskctl steps <TASK-ID> add")
    parser.add_argument("title")
    parser.add_argument("--kind", choices=sorted(KINDS))
    parser.add_argument("--priority", choices=PRIORITIES)
    addition = parser.parse_args(args.mdtask_args[1:])
    title = addition.title
    if (
        not title.strip()
        or title != title.strip()
        or len(title.splitlines()) != 1
        or any(ord(char) < 32 or char == "\x7f" for char in title)
        or re.search(r"(?:^|\s)[@#!]|\b[A-Z][A-Z0-9]*-\d{16}\b", title)
    ):
        fail("step title must be plain single-line text without IDs or mdtask metadata")
    document = find_document(
        [read_document(path) for path in issue_paths(args.root)], args.query
    )
    validate_issue_shape(document)
    path = expected_execution_path(args.root, document)
    require_owned_execution_path(args.root, path)
    if document.values["status"] in {"done", "dropped"} or path.parent.parent.name == "archive":
        fail("cannot add steps to terminal or archived work")
    authoring = document.values["spec_mode"] == "required"
    if authoring:
        validate_step_planning(args.root, document)
    documents, steps = _load_state(
        args.root, authoring_task_id=document.task_id if authoring else None
    )
    used = {item.task_id.split("-", 1)[1] for item in (*documents, *steps)}
    for item in documents:
        if item.values["status"] == "dropped":
            receipt = read_lifecycle_receipt(
                args.root, item, expected_execution_path(args.root, item), "drop"
            )
            used.update(
                step_id.split("-", 1)[1]
                for step_id in receipt["dropped_step_ids"]
            )
    step_id = allocate_id(args.root, document.values["area"], used)
    kind = addition.kind or document.values["kind"]
    priority = addition.priority or document.values["priority"]
    record = (
        f"- [ ] {step_id} {title} #{kind}"
        f"{MDTASK_PRIORITY_TOKENS[priority]} @item:{document.task_id}\n"
    )
    if path.exists():
        previous = path.read_bytes()
        content = ("" if not previous or previous.endswith(b"\n") else "\n") + record
        flags = os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW
    else:
        content = f"# {document.task_id}\n\n## Execution\n\n{record}"
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    with os.fdopen(os.open(path, flags, 0o644), "a", encoding="utf-8") as handle:
        handle.write(content)
        handle.flush()
        os.fsync(handle.fileno())
    print(f"Added {step_id} to {path.relative_to(args.root)}")
    if authoring:
        print("Planning validation remains pending: complete verification mappings.")
    print("Run taskctl generate-board, then taskctl validate.")
    return 0


def command_steps(args: argparse.Namespace) -> int:
    allowed = {"add", "list", "view", "done", "set", "validate"}
    if not args.mdtask_args or args.mdtask_args[0] not in allowed:
        fail(f"steps exposes only: {', '.join(sorted(allowed))}")
    action = args.mdtask_args[0]
    with steps_write_lock(args.root) if action in {"add", "done", "set"} else nullcontext():
        if action == "add":
            return command_steps_add(args)
        documents, _ = load_state(args.root)
        document = find_document(documents, args.query)
        path = expected_execution_path(args.root, document)
        mdtask = tool_binary(args.root, "mdtask")
        result = run_command(
            (str(mdtask), *args.mdtask_args, "--path", str(path)),
            root=args.root,
            capture=False,
        )
        return result.returncode


def verify_task(root: Path, document: Document, steps: list[Step], *, archive_ready: bool) -> None:
    item_steps = [step for step in steps if step.item_id == document.task_id]
    open_steps = [step.task_id for step in item_steps if not step.done]
    if open_steps:
        fail(f"{document.task_id}: open execution steps: {', '.join(open_steps)}")
    if document.values["spec_mode"] == "required":
        change = document.values["openspec_change"]
        execution = expected_execution_path(root, document)
        if execution.parent.parent.name != "archive":
            openspec = tool_binary(root, "openspec")
            result = run_command((str(openspec), "validate", change, "--strict", "--json"), root=root)
            if result.returncode != 0:
                fail(f"OpenSpec change {change} is invalid:\n{(result.stdout or '').rstrip()}")
        evidence = evidence_values(execution.parent / "verification.md")
        if archive_ready:
            if any(evidence[category] in {"required", "blocked"} for category in EVIDENCE_CATEGORIES):
                fail(f"{change}: verification evidence is incomplete")
            if not isinstance(evidence["commit_sha"], str) or not SHA_RE.fullmatch(evidence["commit_sha"]):
                fail(f"{change}: verification commit_sha must be an exact 40-character SHA")


def command_verify(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    document = find_document(documents, args.query)
    verify_task(args.root, document, steps, archive_ready=args.archive_ready)
    print(f"Verified {document.task_id}")
    return 0


def command_openspec_archive(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    document = next(
        (item for item in documents if item.values["openspec_change"] == args.change),
        None,
    )
    if document is None:
        fail(f"OpenSpec change {args.change!r} has no portfolio task")
    if document.values["status"] not in {"review", "dropped"}:
        fail(f"{document.task_id}: archive requires review or a prepared dropped state")
    archive_args = ["archive", args.change, "--yes", "--json"]
    if document.values["status"] == "dropped":
        relative = document.path.relative_to(args.root).as_posix()
        committed = run_command(("git", "show", f"HEAD:{relative}"), root=args.root)
        if committed.returncode != 0:
            fail("dropped task record must be committed before archival")
        with tempfile.TemporaryDirectory(prefix="ripdpi-task-archive-") as directory:
            snapshot = Path(directory) / "issue.md"
            snapshot.write_text(committed.stdout or "", encoding="utf-8")
            if read_document(snapshot).values.get("status") != "dropped":
                fail("dropped terminal state must be committed before archival")
        execution = expected_execution_path(args.root, document)
        for kind in ("drop", "close"):
            receipt = lifecycle_receipt_path(args.root, document, execution, kind)
            receipt_relative = receipt.relative_to(args.root).as_posix()
            present = run_command(("git", "cat-file", "-e", f"HEAD:{receipt_relative}"), root=args.root)
            if present.returncode != 0:
                fail(f"commit the taskctl {kind} receipt before archival")
        verification_path = execution.parent / "verification.md"
        verification = read_document(verification_path)
        values = dict(verification.values)
        values["commit_sha"] = run_command(("git", "rev-parse", "HEAD"), root=args.root).stdout.strip()
        verification_path.write_text(
            render_document(values, verification.body, order=tuple(values)),
            encoding="utf-8",
        )
        archive_args.append("--skip-specs")
    verify_task(args.root, document, steps, archive_ready=True)
    openspec = tool_binary(args.root, "openspec")
    result = run_command((str(openspec), *archive_args), root=args.root, capture=False)
    if result.returncode != 0:
        return result.returncode
    archives = sorted((args.root / "openspec/changes/archive").glob(f"*-{args.change}"))
    if len(archives) != 1:
        fail(f"archive completed but exactly one archive for {args.change} was not found")
    evidence = evidence_values(archives[0] / "verification.md")
    receipt = {
        "schema": 1,
        "task_id": document.task_id,
        "change": args.change,
        "outcome": document.values["status"],
        "commit_sha": evidence["commit_sha"],
        "archived_at": datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    (archives[0] / ".taskctl-archive.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return 0


def command_openspec_cli(args: argparse.Namespace) -> int:
    if not args.openspec_args or args.openspec_args[0] not in SAFE_OPENSPEC_COMMANDS:
        fail(
            "OpenSpec passthrough exposes only project-scoped planning commands; "
            "use taskctl openspec archive for archival"
        )
    openspec = tool_binary(args.root, "openspec")
    result = run_command(
        (str(openspec), *args.openspec_args), root=args.root, capture=False
    )
    return result.returncode


def write_lifecycle_receipt(
    root: Path,
    document: Document,
    execution: Path,
    kind: str,
    payload: dict[str, Any],
) -> None:
    path = lifecycle_receipt_path(root, document, execution, kind)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare_dropped_execution(
    root: Path,
    document: Document,
    steps: Sequence[Step],
    reason: str,
) -> Path:
    execution = expected_execution_path(root, document)
    dropped_step_ids = {step.task_id for step in steps if not step.done}
    rewritten: list[str] = []
    for line in execution.read_text(encoding="utf-8").splitlines():
        match = STEP_RE.match(line)
        if match and match.group(2) in dropped_step_ids:
            rewritten.append(f"- {match.group(2)} DROPPED: {match.group(3)}")
        else:
            rewritten.append(line)
    execution.write_text("\n".join(rewritten).rstrip() + "\n", encoding="utf-8")

    safe_reason = re.sub(r"\s+", " ", reason.replace("|", "/")).strip()
    if document.values["spec_mode"] == "required":
        verification_path = execution.parent / "verification.md"
        verification = read_document(verification_path)
        values = dict(verification.values)
        values["commit_sha"] = None
        for category in EVIDENCE_CATEGORIES:
            values[category] = "not_applicable"
            values[f"{category}_evidence"] = f"Task dropped: {safe_reason}"
        body_lines: list[str] = []
        for line in verification.body.splitlines():
            match = re.match(
                r"^\|\s*(REQ-[A-Z0-9-]+)\s*\|\s*([A-Z][A-Z0-9]*-\d{16})\s*\|[^|]*\|[^|]*\|$",
                line,
            )
            if match:
                body_lines.append(
                    f"| {match.group(1)} | {match.group(2)} | Dropped: {safe_reason} | not_applicable |"
                )
            else:
                body_lines.append(line)
        verification_path.write_text(
            render_document(values, "\n".join(body_lines) + "\n", order=tuple(values)),
            encoding="utf-8",
        )

    write_lifecycle_receipt(
        root,
        document,
        execution,
        "drop",
        {
            "schema": 1,
            "task_id": document.task_id,
            "change": document.values["openspec_change"],
            "outcome": "dropped",
            "reason": safe_reason,
            "dropped_step_ids": sorted(dropped_step_ids),
            "prepared_at": datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        },
    )
    return execution


def command_close_prepare(args: argparse.Namespace) -> int:
    documents, steps = load_state(args.root)
    document = find_document(documents, args.query)
    if args.outcome == "done":
        if document.values["status"] != "review":
            fail("done closure requires review status")
        verify_task(args.root, document, steps, archive_ready=document.values["spec_mode"] == "required")
        if document.values["spec_mode"] == "required":
            change = document.values["openspec_change"]
            active = args.root / "openspec/changes" / change
            if active.exists():
                fail(f"archive {change} with taskctl openspec archive before preparing done closure")
    else:
        if not args.reason:
            fail("dropped closure requires --reason")
        if not transition_allowed(document.values["status"], "dropped"):
            fail(f"cannot drop task from {document.values['status']}")
        execution = prepare_dropped_execution(args.root, document, steps, args.reason)
    values = dict(document.values)
    values["status"] = args.outcome
    values["updated"] = date.today().isoformat()
    values["closed_at"] = datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    values["closed_reason"] = args.reason or "All acceptance criteria and required evidence passed."
    values["evidence_summary"] = args.evidence
    write_document(document, values)
    execution = expected_execution_path(args.root, Document(document.path, values, document.body))
    write_lifecycle_receipt(
        args.root,
        Document(document.path, values, document.body),
        execution,
        "close",
        {
            "schema": 1,
            "task_id": document.task_id,
            "change": document.values["openspec_change"],
            "outcome": args.outcome,
            "reason": values["closed_reason"],
            "evidence_summary": args.evidence,
            "prepared_at": values["closed_at"],
            "issue_sha256": hashlib.sha256(document.path.read_bytes()).hexdigest(),
            "execution_sha256": hashlib.sha256(execution.read_bytes()).hexdigest(),
        },
    )
    print(f"Prepared terminal state for {document.task_id}; commit it before purge")
    return 0


def command_close_purge(args: argparse.Namespace) -> int:
    documents, _ = load_state(args.root)
    document = find_document(documents, args.query)
    if document.values["status"] not in {"done", "dropped"}:
        fail("purge requires a prepared terminal state")
    relative = document.path.relative_to(args.root).as_posix()
    committed = run_command(("git", "show", f"HEAD:{relative}"), root=args.root)
    if committed.returncode != 0:
        fail("terminal task record must be committed before purge")
    with tempfile.TemporaryDirectory(prefix="ripdpi-task-purge-") as directory:
        snapshot = Path(directory) / "issue.md"
        snapshot.write_text(committed.stdout or "", encoding="utf-8")
        committed_document = read_document(snapshot)
        if committed_document.values.get("status") != document.values["status"]:
            fail("working terminal state differs from HEAD; commit it before purge")
    for candidate in documents:
        if candidate.task_id == document.task_id:
            continue
        if candidate.values["parent"] == document.task_id or document.task_id in candidate.values["blocked_by"]:
            fail(f"{document.task_id}: incoming reference from {candidate.task_id}")
    execution = expected_execution_path(args.root, document)
    receipt_kinds = ("close", "drop") if document.values["status"] == "dropped" else ("close",)
    for kind in receipt_kinds:
        receipt = lifecycle_receipt_path(args.root, document, execution, kind)
        receipt_relative = receipt.relative_to(args.root).as_posix()
        committed_receipt = run_command(("git", "cat-file", "-e", f"HEAD:{receipt_relative}"), root=args.root)
        if committed_receipt.returncode != 0 and document.values["spec_mode"] == "required":
            change = document.values["openspec_change"]
            committed_receipt = run_command(
                ("git", "cat-file", "-e", f"HEAD:openspec/changes/{change}/.taskctl-{kind}.json"),
                root=args.root,
            )
        if committed_receipt.returncode != 0:
            fail(f"taskctl {kind} receipt must be committed before purge")
    if document.values["spec_mode"] == "required":
        change = document.values["openspec_change"]
        if (args.root / "openspec/changes" / change).exists():
            fail(f"OpenSpec change {change} is still active")
        archives = list((args.root / "openspec/changes/archive").glob(f"*-{change}"))
        if not archives:
            fail(f"OpenSpec change {change} has not been archived")
    else:
        if execution.exists():
            execution.unlink()
        for kind in receipt_kinds:
            lifecycle_receipt_path(args.root, document, execution, kind).unlink(missing_ok=True)
    document.path.unlink()
    print(f"Purged {document.task_id}; commit this deletion separately")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help=argparse.SUPPRESS)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--base")
    validate.add_argument("--json", action="store_true")
    validate.add_argument("--skip-upstreams", action="store_true", help=argparse.SUPPRESS)
    validate.set_defaults(handler=command_validate)

    board = subparsers.add_parser("generate-board")
    board.set_defaults(handler=command_generate_board)

    listing = subparsers.add_parser("list")
    listing.add_argument("--status", action="append")
    listing.add_argument("--area", action="append")
    listing.add_argument("--kind", action="append")
    listing.add_argument("--json", action="store_true")
    listing.set_defaults(handler=command_list)

    show = subparsers.add_parser("show")
    show.add_argument("query")
    show.add_argument("--json", action="store_true")
    show.set_defaults(handler=command_show)

    ready = subparsers.add_parser("ready")
    ready.add_argument("--json", action="store_true")
    ready.set_defaults(handler=command_ready)

    graph = subparsers.add_parser("graph")
    graph.add_argument("--json", action="store_true")
    graph.set_defaults(handler=command_graph)

    new = subparsers.add_parser("new")
    new.add_argument("--title", required=True)
    new.add_argument("--kind", required=True, choices=sorted(KINDS))
    new.add_argument("--area", required=True, choices=sorted(AREA_PREFIXES))
    new.add_argument("--priority", required=True, choices=PRIORITIES)
    new.add_argument("--owner", default="unassigned")
    new.add_argument("--parent")
    new.add_argument("--slug")
    new.add_argument("--spec-mode", required=True, choices=("required", "not-required"))
    new.add_argument("--spec-reason", choices=sorted(SPEC_REASONS))
    new.add_argument("--openspec-change")
    new.set_defaults(handler=command_new)

    start = subparsers.add_parser("start")
    start.add_argument("query")
    start.add_argument("--owner")
    start.set_defaults(handler=command_start)

    transition = subparsers.add_parser("transition")
    transition.add_argument("query")
    transition.add_argument("status", choices=sorted(STATUSES - {"done", "dropped"}))
    transition.add_argument("--detail")
    transition.set_defaults(handler=command_transition)

    steps = subparsers.add_parser("steps")
    steps.add_argument("query")
    steps.add_argument("mdtask_args", nargs=argparse.REMAINDER)
    steps.set_defaults(handler=command_steps)

    verify = subparsers.add_parser("verify")
    verify.add_argument("query")
    verify.add_argument("--archive-ready", action="store_true")
    verify.set_defaults(handler=command_verify)

    openspec = subparsers.add_parser("openspec")
    openspec_sub = openspec.add_subparsers(dest="openspec_command", required=True)
    openspec_cli = openspec_sub.add_parser("cli")
    openspec_cli.add_argument("openspec_args", nargs=argparse.REMAINDER)
    openspec_cli.set_defaults(handler=command_openspec_cli)
    archive = openspec_sub.add_parser("archive")
    archive.add_argument("change")
    archive.set_defaults(handler=command_openspec_archive)

    close = subparsers.add_parser("close")
    close_sub = close.add_subparsers(dest="close_command", required=True)
    prepare = close_sub.add_parser("prepare")
    prepare.add_argument("query")
    prepare.add_argument("--outcome", required=True, choices=("done", "dropped"))
    prepare.add_argument("--reason")
    prepare.add_argument("--evidence", required=True)
    prepare.set_defaults(handler=command_close_prepare)
    purge = close_sub.add_parser("purge")
    purge.add_argument("query")
    purge.set_defaults(handler=command_close_purge)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    args.root = args.root.resolve()
    try:
        return int(args.handler(args))
    except (ContractError, OSError, ValueError, SyntaxError, json.JSONDecodeError) as error:
        print(f"taskctl: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
