#!/usr/bin/env python3
"""Adapt the RIPDPI task board for /goal-driven execution.

The /goal evaluator reads only the transcript, never the repo. So a condition
like "all tasks in the epic are done" is not checkable. This script makes every
task's proof surfaceable:

  1. Parses docs/tasks/issues/*.md (frontmatter + body).
  2. Builds a dependency graph from:
       - `blocked_by:` frontmatter
       - `parent:` frontmatter (child ordered before its epic)
       - body wikilinks under dependency-ish headings ("Dependencies",
         "Blocked-by", "Prerequisites") and on "depends on" / "blocked by"
         lines
       - epic `## Child tasks` wikilinks (child ordered before epic)
  3. Topologically sorts it (feeders first), breaking cycles by priority.
  4. Regenerates docs/tasks/GOAL_LEDGER.md — one row per task in topo order,
     with Status + Proof columns the /goal loop fills in.
  5. Injects an idempotent `## Goal contract` block into every task/epic file
     (Verify command, Scope, Blocked-by, completion protocol).
  6. Regenerates docs/tasks/GOAL_LAUNCH.md — pre-flight prompt, permissions
     note, phased /goal commands, compaction guidance.

Dry-run by default. Pass --apply to write task files + ledger + launch doc.
Re-running is safe: the `## Goal contract` block is delimited by HTML markers
and replaced in place, never duplicated.
"""
from __future__ import annotations

import argparse
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ISSUES = ROOT / "docs" / "tasks" / "issues"
LEDGER = ROOT / "docs" / "tasks" / "GOAL_LEDGER.md"
LAUNCH = ROOT / "docs" / "tasks" / "GOAL_LAUNCH.md"

CONTRACT_START = "<!-- goal-contract:auto -->"
CONTRACT_END = "<!-- /goal-contract:auto -->"
PHASE_SIZE = 18

FM_RE = re.compile(r"^---\n(.*?)\n---\n", re.S)
TASK_LINE_RE = re.compile(r"(?m)^- \[[ x]\] #task .+$")
WIKILINK_RE = re.compile(r"\[\[([^\]]+)\]\]")
SECTION_SPLIT_RE = re.compile(r"(?m)^(#{2,3} .+)$")
INLINE_CODE_RE = re.compile(r"`([^`\n]+)`")
FENCE_RE = re.compile(r"```[A-Za-z0-9]*\n(.*?)```", re.S)
# A real verification command, recognised only inside code spans / fences —
# never matched against free prose (which is full of the word "just").
CMD_START_RE = re.compile(
    r"^(?:\./gradlew |gradlew |cargo (?:test|nextest|build|clippy|check)\b|"
    r"just [a-z][a-z0-9_-]*(?:\s|$))"
)
PATH_RE = re.compile(r"(?:^|[\s`(])((?:core|app|native|build-logic|quality|config)/[A-Za-z0-9_./\-]+)")
CONTRACT_BLOCK_RE = re.compile(
    r"\n?## Goal contract\n\n" + re.escape(CONTRACT_START) + r".*?" + re.escape(CONTRACT_END) + r"\n",
    re.S,
)
# Pinned-value readers: once a human/agent replaces a TODO placeholder in an
# existing contract block, that value is authoritative and survives re-runs.
EXISTING_VERIFY_RE = re.compile(r"-\s*\*\*Verify:\*\*\s*`([^`]+)`")
EXISTING_SCOPE_RE = re.compile(r"-\s*\*\*Scope[^:*]*:\*\*\s*(.+)")

PRIORITY_RANK = {"critical": 0, "high": 1, "medium": 2, "low": 3}
STATUS_TO_LEDGER = {
    "done": "DONE",
    "dropped": "DROPPED",
    "blocked": "BLOCKED",
    "review": "TODO",
    "doing": "TODO",
    "todo": "TODO",
    "backlog": "TODO",
}

DEP_HEADING_RE = re.compile(r"(?i)(depend|blocked-by|blocked by|prerequisite|requires)")
# Direction-aware edge keywords, matched per line:
#   DEP  — the linked item must come BEFORE this node ("depends on X", "blocked by X")
#   REV  — this node must come BEFORE the linked item ("unblocks X", "feeds X")
#   SKIP — not an ordering edge at all ("coordinates with X", "relates to X")
# "needs" is deliberately excluded — too weak; it matches incidental prose
# like "(needs scripted exit causes)" inside an otherwise "Unblocks:" item.
DEP_KW_RE = re.compile(r"(?i)\b(depends? on|blocked[ -]by|hard-blocked|prerequisite|requires)\b")
REV_KW_RE = re.compile(r"(?i)\b(unblocks?|feeds?(?: into)?|enables?|precedes?)\b")
SKIP_KW_RE = re.compile(r"(?i)\b(coordinates? with|relates? to|see also|related|informs?)\b")

AREA_DEFAULT_VERIFY = {
    "rust-native": "TODO(verify): cargo test -p <crate>",
    "engine": "TODO(verify): ./gradlew :core:engine:testDebugUnitTest",
    "ui": "TODO(verify): ./gradlew :app:testDebugUnitTest",
    "android": "TODO(verify): ./gradlew :app:testDebugUnitTest",
    "vpn": "TODO(verify): ./gradlew :core:service:testDebugUnitTest",
    "proxy": "TODO(verify): ./gradlew :core:service:testDebugUnitTest",
    "relay": "TODO(verify): ./gradlew :core:data:settings:testDebugUnitTest",
    "service": "TODO(verify): ./gradlew :core:service:testDebugUnitTest",
    "data": "TODO(verify): ./gradlew :core:data:runtime-state:testDebugUnitTest",
    "diagnostics": "TODO(verify): ./gradlew :core:diagnostics:testDebugUnitTest",
    "dns": "TODO(verify): ./gradlew :core:service:testDebugUnitTest",
    "routing": "TODO(verify): ./gradlew :core:service:testDebugUnitTest",
    "transport": "TODO(verify): cargo test -p <transport-crate>",
    "outbound": "TODO(verify): ./gradlew :core:data:runtime-state:testDebugUnitTest",
    "testing": "TODO(verify): ./gradlew test",
    "ci": "TODO(verify): <ci lint/build command>",
    "epic": "all child rows in GOAL_LEDGER.md are DONE or BLOCKED",
}


class Node:
    __slots__ = (
        "slug", "path", "raw", "fm", "body", "title", "type", "status",
        "area", "priority", "parent", "blocked_by", "dep_links", "rev_links",
        "child_links", "verify", "verify_extracted", "scope", "ledger_status",
        "order", "dep_slugs", "rev_slugs",
    )

    def __init__(self, slug: str, path: Path, raw: str):
        self.slug = slug
        self.path = path
        self.raw = raw
        self.fm, body = parse_frontmatter(raw)
        # Strip any previously-injected contract block before analysis: the
        # generated block contains backticked commands/paths and must never
        # feed back into verify/scope/dependency extraction (idempotency).
        self.body = CONTRACT_BLOCK_RE.sub("\n", body)
        self.title = self.fm.get("title", slug)
        self.type = self.fm.get("type", "task")
        self.status = (self.fm.get("status") or "backlog").lower()
        self.area = (self.fm.get("area") or "").lower()
        self.priority = (self.fm.get("priority") or "medium").lower()
        self.parent = self.fm.get("parent")
        if self.parent in ("null", "", None):
            self.parent = None
        bb = self.fm.get("blocked_by") or []
        self.blocked_by = [b for b in bb if b]
        self.dep_links, self.rev_links = extract_dep_links(self.body)
        self.child_links = extract_child_links(self.body)
        self.verify, self.verify_extracted = extract_verify(self.body, self.area, self.type)
        self.scope = extract_scope(self.body, self.type)
        # Pin-awareness: a prior contract block whose Verify/Scope are no longer
        # TODO placeholders has been pinned by a human/agent — keep those values
        # verbatim instead of re-deriving a heuristic guess.
        prior = CONTRACT_BLOCK_RE.search(raw)
        if prior:
            block = prior.group(0)
            vm = EXISTING_VERIFY_RE.search(block)
            if vm and not vm.group(1).strip().startswith("TODO(verify)"):
                self.verify = vm.group(1).strip()
                self.verify_extracted = True
            sm = EXISTING_SCOPE_RE.search(block)
            if sm and not sm.group(1).strip().startswith("TODO(scope)"):
                self.scope = sm.group(1).strip()
        self.ledger_status = STATUS_TO_LEDGER.get(self.status, "TODO")
        self.order = 0
        self.dep_slugs: set[str] = set()
        self.rev_slugs: set[str] = set()

    def sortkey(self):
        return (
            PRIORITY_RANK.get(self.priority, 2),
            str(self.fm.get("created") or "9999-99-99"),
            self.slug,
        )


def parse_frontmatter(text: str):
    m = FM_RE.match(text)
    if not m:
        return {}, text
    fm: dict = {}
    body = text[m.end():]
    for line in m.group(1).splitlines():
        s = line.strip()
        if not s or s.startswith("#") or ":" not in line:
            continue
        if line[0] in " \t":  # nested / multiline list item; ignore (rare)
            continue
        key, _, val = line.partition(":")
        key = key.strip()
        val = val.strip()
        if val.startswith("[") and val.endswith("]"):
            inner = val[1:-1].strip()
            val = [x.strip().strip("\"'") for x in inner.split(",") if x.strip()]
        elif val in ("null", "~", ""):
            val = None
        else:
            val = val.strip("\"'")
        fm[key] = val
    return fm, body


def iter_sections(body: str):
    """Yield (heading, content) pairs for ## / ### sections."""
    parts = SECTION_SPLIT_RE.split(body)
    for i in range(1, len(parts), 2):
        head = parts[i].strip()
        content = parts[i + 1] if i + 1 < len(parts) else ""
        yield head, content


def _list_items(content: str):
    """Group section content into logical list items / paragraphs.

    A new item starts at a bullet/numbered line; continuation lines attach to
    the current item until a blank line or the next bullet. This matters
    because a single dependency item often wraps across several physical
    lines — judging direction per *physical line* misreads the keyword-less
    continuation lines of an "Unblocks: ..." item as bare dependencies.
    """
    items: list[str] = []
    cur: list[str] = []
    bullet = re.compile(r"^([-*+]|\d+\.)\s")
    for line in content.splitlines():
        s = line.strip()
        if not s:
            if cur:
                items.append(" ".join(cur))
                cur = []
            continue
        if bullet.match(s) and cur:
            items.append(" ".join(cur))
            cur = [s]
        else:
            cur.append(s)
    if cur:
        items.append(" ".join(cur))
    return items


def extract_dep_links(body: str):
    """Return (dep_links, rev_links) as sets of wikilink texts.

    dep_links: the linked item must be ordered BEFORE this node.
    rev_links: this node must be ordered BEFORE the linked item.

    Direction is read per *logical list item* (not per physical line), so a
    `## Dependencies` section that mixes "Depends on:", "Unblocks:", "Feeds:"
    and "Coordinates with:" items — each possibly wrapped across several
    lines — no longer collapses every link into a wrong-direction
    dependency. That misreading is what fabricated the epic<->epic cycles.
    """
    deps: set[str] = set()
    revs: set[str] = set()
    for head, content in iter_sections(body):
        under_dep_heading = bool(DEP_HEADING_RE.search(head))
        for item in _list_items(content):
            links = WIKILINK_RE.findall(item)
            if not links:
                continue
            if SKIP_KW_RE.search(item):
                continue
            dm = DEP_KW_RE.search(item)
            rm = REV_KW_RE.search(item)
            if dm and rm:
                # both keywords present — the LEFTMOST one is the item's
                # declared relation ("Unblocks X ... requires Y" => rev).
                (deps if dm.start() < rm.start() else revs).update(links)
            elif dm:
                deps.update(links)
            elif rm:
                revs.update(links)
            elif under_dep_heading:
                # bare item under a Dependencies heading => default "depends on"
                deps.update(links)
            # else: a link in ordinary prose — not an ordering edge
    return deps, revs


def extract_child_links(body: str) -> set[str]:
    children: set[str] = set()
    for head, content in iter_sections(body):
        if re.search(r"(?i)child task", head):
            for lm in WIKILINK_RE.finditer(content):
                children.add(lm.group(1))
    return children


def _candidate_commands(blob: str):
    """Command strings found inside inline code spans or fenced blocks only."""
    out: list[str] = []
    for m in INLINE_CODE_RE.finditer(blob):
        out.append(m.group(1).strip())
    for m in FENCE_RE.finditer(blob):
        for line in m.group(1).splitlines():
            line = line.strip()
            if line and not line.startswith(("#", "//")):
                out.append(line)
    return out


def extract_verify(body: str, area: str, ntype: str):
    """Return (command, extracted?). Prefers verification-ish sections.

    Only commands inside backticks / fenced blocks are trusted — prose is
    never scanned (it is full of the English word "just").
    """
    if ntype == "epic":
        return (AREA_DEFAULT_VERIFY["epic"], False)
    pref = ("## Completion criteria", "## Definition of done", "## Test plan",
            "## Verification", "## TDD workflow", "## Acceptance criteria")
    pref_blobs, other_blobs = [], []
    for head, content in iter_sections(body):
        (pref_blobs if head in pref else other_blobs).append(content)
    for blob in pref_blobs + other_blobs:
        for cand in _candidate_commands(blob):
            if CMD_START_RE.match(cand):
                return (cand.strip().strip("`").strip(), True)
    return (AREA_DEFAULT_VERIFY.get(area, "TODO(verify): <command>"), False)


# Sections that cite *other* repos' file paths (reference implementations) —
# never trust them for this repo's scope.
SCOPE_SKIP_SECTIONS = (
    "## Source references", "## Source", "## References", "## Links",
    "## Research citation", "## Research",
)
# Path fragments that mark a foreign reference repo (NekoBox, Amnezia, etc.).
FOREIGN_PATH_MARKERS = (
    "nekohasekai", "sagernet", "matsuri", "/nb4a/", "amnezia-vpn",
    "/java/io/", "amneziawg-go", "amneziawg-android", "boringtun/",
)


def extract_scope(body: str, ntype: str) -> str:
    if ntype == "epic":
        return "_epic — coordination only; child tasks carry the file scope_"
    roots: set[str] = set()
    for head, content in iter_sections(body):
        if head in SCOPE_SKIP_SECTIONS:
            continue
        for m in PATH_RE.finditer(content):
            p = m.group(1).rstrip(".,;:`)")
            if any(marker in p for marker in FOREIGN_PATH_MARKERS):
                continue
            parts = p.split("/")
            if parts[0] == "core" and len(parts) >= 2:
                # core/<group>/<module>/... -> core/<group>/<module> when present
                if len(parts) >= 3 and "." not in parts[2]:
                    roots.add("/".join(parts[:3]))
                else:
                    roots.add("/".join(parts[:2]))
            elif parts[0] == "native" and len(parts) >= 4:
                roots.add("/".join(parts[:4]))  # native/rust/crates/<crate>
            elif parts[0] in ("app", "build-logic", "quality", "config"):
                roots.add(parts[0])
    # Drop file paths (any component with a dot) — module/crate roots have none.
    roots = {r for r in roots if r and "." not in r}
    if not roots:
        return "TODO(scope): <module path(s) this task may modify>"
    picked = sorted(roots)[:5]
    return ", ".join(f"`{r}/**`" for r in picked)


def resolve_links(nodes: dict[str, Node]):
    """Resolve wikilink text -> slug for every node's dep/child links."""
    title_index: dict[str, str] = {}
    for n in nodes.values():
        title_index[n.title.strip().lower()] = n.slug
    slugset = set(nodes)

    def resolve(link: str):
        name = link.split("|")[0].strip()
        low = name.lower()
        if low in title_index:
            return title_index[low]
        # epics frequently linked as "Epic - X" vs title "Epic - X"
        if name in slugset:
            return name
        # try slugified guess
        guess = re.sub(r"[^a-z0-9]+", "-", low).strip("-")
        if guess in slugset:
            return guess
        return None

    for n in nodes.values():
        for link in n.dep_links:
            s = resolve(link)
            if s and s != n.slug:
                n.dep_slugs.add(s)
        for link in n.rev_links:
            s = resolve(link)
            if s and s != n.slug:
                n.rev_slugs.add(s)
        # epic child links: child must precede this epic
        # (handled as edges in build_edges)
    return resolve


def build_edges(nodes: dict[str, Node], resolve):
    """Edges as (dep_slug, node_slug): dep must be ordered before node."""
    edges: set[tuple[str, str]] = set()
    for n in nodes.values():
        for dep in n.dep_slugs:
            if dep == n.parent:
                # A child task cannot "depend on" its own containing epic in
                # an ordering sense — the epic completes *because* its children
                # do. Such a declaration is redundant and purely circular.
                continue
            edges.add((dep, n.slug))
        # rev_slugs ("feeds"/"unblocks") are intentionally NOT emitted as edges:
        # the win is no longer *misreading* them as backward dependencies (that
        # fabricated the original cycles). Treating them as hard forward edges
        # is lower-confidence soft language and was observed to create fresh
        # cycles, so they are parsed-and-dropped rather than ordered on.
        for bb in n.blocked_by:
            if bb in nodes:
                edges.add((bb, n.slug))
        if n.parent and n.parent in nodes:
            edges.add((n.slug, n.parent))  # child before its epic
        for link in n.child_links:
            s = resolve(link)
            if s and s in nodes and s != n.slug:
                edges.add((s, n.slug))  # listed child before this (epic) node
    return edges


def toposort(nodes: dict[str, Node], edges: set[tuple[str, str]]):
    adj: dict[str, set[str]] = defaultdict(set)
    indeg: dict[str, int] = {s: 0 for s in nodes}
    for dep, node in edges:
        if dep in nodes and node in nodes and node not in adj[dep]:
            adj[dep].add(node)
            indeg[node] += 1
    order: list[str] = []
    remaining = set(nodes)
    cycles_broken = 0
    cycle_slugs: list[str] = []
    while remaining:
        cand = [s for s in remaining if indeg[s] == 0]
        if not cand:
            # cycle: emit the highest-priority remaining node, sever its in-edges
            chosen = sorted(remaining, key=lambda s: nodes[s].sortkey())[0]
            cycles_broken += 1
            cycle_slugs.append(chosen)
            for dep in remaining:
                if chosen in adj[dep]:
                    adj[dep].discard(chosen)
            indeg[chosen] = 0
            continue
        chosen = sorted(cand, key=lambda s: nodes[s].sortkey())[0]
        order.append(chosen)
        remaining.discard(chosen)
        for nxt in sorted(adj[chosen]):
            indeg[nxt] -= 1
    for i, s in enumerate(order, 1):
        nodes[s].order = i
    return order, cycles_broken, cycle_slugs


def build_contract(n: Node, nodes: dict[str, Node]) -> str:
    # Exclude the node's own parent epic — a child is part of its epic, not
    # ordered after it. Mirrors the edge rule in build_edges so the contract's
    # Blocked-by line matches the actual ordering graph (otherwise /goal would
    # wrongly mark such a task BLOCKED on an epic that sorts much later).
    dep_rows = sorted((n.dep_slugs | {b for b in n.blocked_by if b in nodes})
                      - ({n.parent} if n.parent else set()))
    if dep_rows:
        deps_str = ", ".join(f"`{d}`" for d in dep_rows)
    else:
        deps_str = "_none_"
    verify = n.verify
    return (
        "## Goal contract\n\n"
        f"{CONTRACT_START}\n"
        f"- **Ledger key:** `{n.slug}`\n"
        f"- **Verify:** `{verify}`\n"
        f"- **Scope (only modify these + this file + the ledger):** {n.scope}\n"
        f"- **Blocked-by (must be DONE in the ledger first):** {deps_str}\n"
        "- **On completion:** run **Verify**; paste its full output + exit code "
        "into the transcript; set this file's canonical `- [ ] #task` line to "
        "`[x]` and `#status/done` on pass (or `#status/blocked` + a one-line "
        "reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` "
        "(Status = DONE/BLOCKED, Proof = the Verify command + exit code); then "
        "`cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.\n"
        f"{CONTRACT_END}\n"
    )


def inject_contract(n: Node, nodes: dict[str, Node]) -> str:
    block = build_contract(n, nodes)
    raw = n.raw
    if CONTRACT_BLOCK_RE.search(raw):
        return CONTRACT_BLOCK_RE.sub("\n" + block, raw, count=1)
    m = TASK_LINE_RE.search(raw)
    if not m:
        # malformed: append after frontmatter
        fmm = FM_RE.match(raw)
        at = fmm.end() if fmm else 0
        return raw[:at] + "\n" + block + "\n" + raw[at:]
    end = m.end()
    rest = raw[end:]
    # insert after the task line's following blank line, before next content
    lead = ""
    while rest[:1] == "\n":
        lead += "\n"
        rest = rest[1:]
    if lead == "":
        lead = "\n\n"
    elif lead == "\n":
        lead = "\n\n"
    return raw[:end] + lead + block + "\n" + rest


def render_ledger(order: list[str], nodes: dict[str, Node], cycles_broken: int,
                  cycle_slugs: list[str], todo_verify: int, todo_scope: int) -> str:
    n_tasks = sum(1 for s in order if nodes[s].type != "epic")
    n_epics = sum(1 for s in order if nodes[s].type == "epic")
    lines = []
    lines.append("# Goal Ledger — RIPDPI task board")
    lines.append("")
    lines.append("Generated by `scripts/goal-adapt.py`. Re-run that script after")
    lines.append("adding, removing, or re-linking task files; do not hand-edit the")
    lines.append("table structure. The `/goal` loop hand-edits only the **Status**")
    lines.append("and **Proof** cells as it completes each row.")
    lines.append("")
    lines.append("Rows are in **topological order — feeders before dependents**.")
    lines.append("Working strictly top-to-bottom guarantees every task's")
    lines.append("`Blocked-by` set is already DONE when the loop reaches it.")
    lines.append("")
    lines.append(f"- Tasks: **{n_tasks}**  ·  Epics: **{n_epics}**  ·  Total rows: **{len(order)}**")
    lines.append(f"- Dependency cycles broken during sort: **{cycles_broken}**"
                 + ((" — " + ", ".join(f"`{c}`" for c in cycle_slugs)) if cycle_slugs else ""))
    lines.append(f"- Rows needing a human-pinned verify command (`TODO(verify)`): **{todo_verify}**")
    lines.append(f"- Rows needing a human-pinned scope (`TODO(scope)`): **{todo_scope}**")
    lines.append(f"- Suggested phase size for `/goal`: **{PHASE_SIZE}** rows "
                 f"→ **{math.ceil(len(order) / PHASE_SIZE)}** phases "
                 "(see `GOAL_LAUNCH.md`).")
    lines.append("")
    lines.append("Status legend: `TODO` (not started) · `DONE` (verified, proof in")
    lines.append("transcript) · `BLOCKED` (verify failed twice or unmet dependency)")
    lines.append("· `DROPPED` (task abandoned upstream).")
    lines.append("")
    lines.append("| # | Phase | Task (ledger key = slug) | Type | Epic | Area | Prio | Status | Proof |")
    lines.append("|---|-------|--------------------------|------|------|------|------|--------|-------|")
    for s in order:
        n = nodes[s]
        phase = (n.order - 1) // PHASE_SIZE + 1
        epic = n.parent or ("—" if n.type == "epic" else "(no epic)")
        prio = {"critical": "🔺", "high": "⏫", "medium": "🔼", "low": "🔽"}.get(n.priority, "🔼")
        title = n.title.replace("|", "\\|")
        lines.append(
            f"| {n.order} | P{phase} | `{s}`<br>{title} | {n.type} | "
            f"`{epic}` | {n.area or '—'} | {prio} | {n.ledger_status} | |"
        )
    lines.append("")
    return "\n".join(lines)


def render_launch(order: list[str], nodes: dict[str, Node]) -> str:
    n_phases = math.ceil(len(order) / PHASE_SIZE)
    L = []
    L.append("# Goal Launch — running the RIPDPI task board with `/goal`")
    L.append("")
    L.append("Generated by `scripts/goal-adapt.py`. This is the operator runbook for")
    L.append("driving `docs/tasks/issues/*.md` to completion with `/goal`. The core")
    L.append("constraint it is built around: **the `/goal` evaluator reads only the")
    L.append("transcript, not the repo** — so every claim it must judge is forced")
    L.append("into the transcript by the goal condition itself (the ledger `cat`,")
    L.append("per-task command output, exit codes).")
    L.append("")
    L.append("## 0. One-time pre-flight (do this *before* the first `/goal`)")
    L.append("")
    L.append("Run this as a normal prompt, not as a goal — so the loop's first turn")
    L.append("is a work turn, not a parse turn:")
    L.append("")
    L.append("```")
    L.append("Read docs/tasks/GOAL_LEDGER.md and docs/tasks/issues/<first 5 slugs>.md.")
    L.append("Confirm: (a) the ledger row count matches `ls docs/tasks/issues/*.md`,")
    L.append("(b) each task file has a `## Goal contract` block with a Verify command,")
    L.append("(c) no row you are about to start is still `TODO(verify)` or")
    L.append("`TODO(scope)` — if any are, pin a real command/scope in the issue file")
    L.append("and re-run `python3 scripts/goal-adapt.py --apply`. Do not start")
    L.append("implementation.")
    L.append("```")
    L.append("")
    L.append("Then **review the `TODO(verify)` / `TODO(scope)` counts** in the ledger")
    L.append("header. Those rows have a heuristic placeholder, not a real verification")
    L.append("command — pin them before they enter a phase, or the loop will mark")
    L.append("them BLOCKED.")
    L.append("")
    L.append("## 1. Permissions + auto mode")
    L.append("")
    L.append("`/goal` removes the per-*turn* prompt; auto mode removes the per-*tool*")
    L.append("prompt. You need both or the loop stalls on the first edit:")
    L.append("")
    L.append("1. `/permissions` → allow at least: `Edit`, `Write`, `Bash(./gradlew*)`,")
    L.append("   `Bash(cargo*)`, `Bash(just*)`, `Bash(git*)`, `Bash(cat*)`.")
    L.append("2. Turn **auto mode on**.")
    L.append("")
    L.append("## 2. Phased goals (context will not hold 200+ tasks in one run)")
    L.append("")
    L.append(f"The ledger is split into **{n_phases} phases of ~{PHASE_SIZE} rows**")
    L.append("(`Phase` column). Phases respect topological order, so running them in")
    L.append("sequence keeps every `Blocked-by` satisfied. Run one phase per `/goal`;")
    L.append("the goal clears on success so you can chain them. Between phases run:")
    L.append("")
    L.append("```")
    L.append("/compact focus on GOAL_LEDGER.md state, the last phase's BLOCKED rows and why")
    L.append("```")
    L.append("")
    L.append("### Phase table")
    L.append("")
    L.append("| Phase | Ledger rows | Count | First task | Last task |")
    L.append("|-------|-------------|-------|------------|-----------|")
    for p in range(n_phases):
        chunk = order[p * PHASE_SIZE:(p + 1) * PHASE_SIZE]
        if not chunk:
            continue
        a = nodes[chunk[0]].order
        b = nodes[chunk[-1]].order
        L.append(f"| P{p + 1} | {a}–{b} | {len(chunk)} | `{chunk[0]}` | `{chunk[-1]}` |")
    L.append("")
    L.append("## 3. The `/goal` command (one per phase)")
    L.append("")
    L.append("Substitute `{A}` and `{B}` with the phase's first/last ledger row")
    L.append("numbers from the table above.")
    L.append("")
    L.append("```")
    L.append("/goal Work through docs/tasks/GOAL_LEDGER.md rows {A}-{B} in listed order. "
             "For each row: open docs/tasks/issues/<slug>.md and read its `## Goal "
             "contract`. If any Blocked-by slug is not already DONE in the ledger, set "
             "the row to BLOCKED (unmet dependency) and continue. Otherwise implement "
             "the task per its file using TDD — write the failing tests named in its "
             "`## TDD workflow` / `## Test plan` first, confirm they fail, then the "
             "minimal code to pass. Then: (1) run the contract's Verify command and "
             "paste its full output + exit code into the transcript; (2) set the issue "
             "file's canonical line to [x] + #status/done on pass, or #status/blocked + "
             "a one-line reason on fail; (3) update the <slug> row in "
             "docs/tasks/GOAL_LEDGER.md — Status = DONE or BLOCKED, Proof = the Verify "
             "command + exit code; (4) cat docs/tasks/GOAL_LEDGER.md so its state is in "
             "the transcript. Constraints: only modify files inside each task's Scope "
             "plus its own issue file and the ledger; do not skip rows; if a Verify "
             "fails twice in a row mark the row BLOCKED with the failure reason and "
             "continue. Goal achieved when every row {A}-{B} is DONE or BLOCKED in "
             "GOAL_LEDGER.md, `./gradlew :app:assembleDebug` exits 0, and `./gradlew "
             "test` exits 0 — with the ledger cat, the assembleDebug output, and the "
             "test output all visible in the transcript. Stop after 60 turns.")
    L.append("```")
    L.append("")
    L.append("## 4. Why this satisfies the transcript-only evaluator")
    L.append("")
    L.append("- **Ledger forced into the transcript** — step (4) `cat`s it every task.")
    L.append("- **Verification run, not claimed** — step (1) pastes real output + exit code.")
    L.append("- **Hard turn cap** — `Stop after 60 turns` guarantees termination even")
    L.append("  if the condition never flips.")
    L.append("- **Auto mode on** — no per-tool prompt stalls the unattended loop.")
    L.append("- **Scope fenced per task** — the contract's Scope line makes \"do not")
    L.append("  modify files outside scope\" enforceable.")
    L.append("")
    L.append("## 5. When `/goal` is the wrong tool here")
    L.append("")
    L.append("- **Mostly-independent tasks** → `/batch` instead: one background")
    L.append("  subagent per task in an isolated worktree, one PR each. Better for the")
    L.append("  many leaf tasks with no dependents than a serial loop.")
    L.append("- **Hard human gates between phases** → `/plan` per phase, skip `/goal`.")
    L.append("- **Scheduled re-runs rather than until-done** → `/loop`.")
    L.append("")
    L.append("Docs: https://code.claude.com/docs/en/goal")
    L.append("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apply", action="store_true",
                    help="write task files + ledger + launch doc (default: dry run)")
    ap.add_argument("--samples", type=int, default=2,
                    help="how many injected-file previews to print in dry run")
    args = ap.parse_args()

    files = sorted(ISSUES.glob("*.md"))
    if not files:
        sys.exit(f"no task files under {ISSUES}")

    nodes: dict[str, Node] = {}
    for f in files:
        nodes[f.stem] = Node(f.stem, f, f.read_text(encoding="utf-8"))

    resolve = resolve_links(nodes)
    edges = build_edges(nodes, resolve)
    order, cycles_broken, cycle_slugs = toposort(nodes, edges)

    todo_verify = sum(1 for n in nodes.values() if n.type != "epic"
                      and str(n.verify).startswith("TODO(verify)"))
    todo_scope = sum(1 for n in nodes.values()
                     if str(n.scope).startswith("TODO(scope)"))

    ledger = render_ledger(order, nodes, cycles_broken, cycle_slugs,
                           todo_verify, todo_scope)
    launch = render_launch(order, nodes)

    print(f"task files            : {len(files)}")
    print(f"  tasks               : {sum(1 for n in nodes.values() if n.type != 'epic')}")
    print(f"  epics               : {sum(1 for n in nodes.values() if n.type == 'epic')}")
    print(f"dependency edges      : {len(edges)}")
    print(f"cycles broken         : {cycles_broken}"
          + (f"  ({', '.join(cycle_slugs)})" if cycle_slugs else ""))
    print(f"verify extracted      : {sum(1 for n in nodes.values() if n.verify_extracted)}")
    print(f"verify TODO(placeholder): {todo_verify}")
    print(f"scope  TODO(placeholder): {todo_scope}")
    print(f"phases (@{PHASE_SIZE})        : {math.ceil(len(order) / PHASE_SIZE)}")
    print(f"first 5 in topo order : {', '.join(order[:5])}")
    print(f"last 5 in topo order  : {', '.join(order[-5:])}")
    print()

    if not args.apply:
        print("--- DRY RUN (no files written). Pass --apply to write. ---\n")
        for s in order[:args.samples]:
            n = nodes[s]
            print(f"=== would inject into {n.path.name} ===")
            print(build_contract(n, nodes))
        print(f"=== would write {LEDGER.relative_to(ROOT)} (first 18 lines) ===")
        print("\n".join(ledger.splitlines()[:18]))
        print(f"\n=== would write {LAUNCH.relative_to(ROOT)} ({len(launch.splitlines())} lines) ===")
        return

    written = 0
    for s in order:
        n = nodes[s]
        new = inject_contract(n, nodes)
        if new != n.raw:
            n.path.write_text(new, encoding="utf-8")
            written += 1
    LEDGER.write_text(ledger + "\n", encoding="utf-8")
    LAUNCH.write_text(launch + "\n", encoding="utf-8")
    print(f"injected/updated goal contract in : {written} task files")
    print(f"wrote ledger                      : {LEDGER.relative_to(ROOT)}")
    print(f"wrote launch runbook              : {LAUNCH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
