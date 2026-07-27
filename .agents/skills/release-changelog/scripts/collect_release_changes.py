#!/usr/bin/env python3
"""Create a read-only Git evidence pack for RIPDPI release notes."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


SEMVER_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
CONVENTIONAL_SUBJECT = re.compile(
    r"^(?P<kind>[a-z][a-z0-9-]*)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s+(?P<title>.+)$"
)
CATEGORY_NAMES = {
    "feat": "Features",
    "fix": "Fixes",
    "perf": "Performance",
    "refactor": "Refactoring",
    "docs": "Documentation",
    "test": "Tests",
    "build": "Build",
    "ci": "CI",
    "chore": "Maintenance",
    "revert": "Reverts",
}
USER_FACING_CATEGORIES = ("Features", "Fixes", "Performance")


@dataclass(frozen=True)
class Commit:
    oid: str
    short_oid: str
    authored_at: str
    author: str
    subject: str
    body: str
    category: str
    scope: str | None
    title: str
    breaking: bool


@dataclass
class ComponentStats:
    files: int = 0
    additions: int = 0
    deletions: int = 0
    binary_files: int = 0


class GitError(RuntimeError):
    pass


def git(root: Path, *args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if check and completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise GitError(f"git {' '.join(args)} failed: {detail}")
    return completed.stdout


def repository_root(start: Path) -> Path:
    completed = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=start,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise GitError("run this script from a Git worktree")
    return Path(completed.stdout.strip()).resolve()


def resolve_commit(root: Path, ref: str) -> str:
    return git(root, "rev-parse", "--verify", f"{ref}^{{commit}}").strip()


def default_base(root: Path, target: str) -> str:
    target_oid = resolve_commit(root, target)
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for tag in git(root, "tag", "--merged", target, "--list", "v*").splitlines():
        match = SEMVER_TAG.fullmatch(tag)
        if match is None or resolve_commit(root, tag) == target_oid:
            continue
        candidates.append((tuple(int(part) for part in match.groups()), tag))
    if not candidates:
        raise GitError("no earlier stable vX.Y.Z tag is reachable from the target")
    return max(candidates)[1]


def require_ancestor(root: Path, base: str, target: str) -> None:
    completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base, target],
        cwd=root,
        check=False,
    )
    if completed.returncode != 0:
        raise GitError(f"{base} is not an ancestor of {target}")


def classify(subject: str, body: str) -> tuple[str, str | None, str, bool]:
    match = CONVENTIONAL_SUBJECT.match(subject)
    if match is None:
        return "Other", None, subject, "BREAKING CHANGE" in body
    kind = match.group("kind")
    return (
        CATEGORY_NAMES.get(kind, kind.title()),
        match.group("scope"),
        match.group("title"),
        bool(match.group("breaking")) or "BREAKING CHANGE" in body,
    )


def collect_commits(root: Path, base: str, target: str) -> list[Commit]:
    raw = git(
        root,
        "log",
        "--reverse",
        "--format=%H%x1f%h%x1f%aI%x1f%an%x1f%s%x1f%b%x1e",
        f"{base}..{target}",
    )
    commits: list[Commit] = []
    for record in raw.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        fields = record.split("\x1f", 5)
        if len(fields) != 6:
            raise GitError("could not parse git log record")
        oid, short_oid, authored_at, author, subject, body = fields
        category, scope, title, breaking = classify(subject, body)
        commits.append(
            Commit(
                oid=oid,
                short_oid=short_oid,
                authored_at=authored_at,
                author=author,
                subject=subject,
                body=body,
                category=category,
                scope=scope,
                title=title,
                breaking=breaking,
            )
        )
    return commits


def component_for(path: str) -> str:
    if "/" not in path:
        return "(root)"
    return path.split("/", 1)[0]


def collect_diff(
    root: Path, base: str, target: str
) -> tuple[dict[str, ComponentStats], int]:
    stats: dict[str, ComponentStats] = defaultdict(ComponentStats)
    changed_files = 0
    for line in git(
        root, "diff", "--numstat", "--no-renames", f"{base}..{target}"
    ).splitlines():
        fields = line.split("\t", 2)
        if len(fields) != 3:
            continue
        added, deleted, path = fields
        component = component_for(path)
        entry = stats[component]
        entry.files += 1
        changed_files += 1
        if added == "-" or deleted == "-":
            entry.binary_files += 1
        else:
            entry.additions += int(added)
            entry.deletions += int(deleted)
    return dict(stats), changed_files


def github_repository(root: Path) -> str | None:
    remote = git(root, "remote", "get-url", "origin", check=False).strip()
    patterns = (
        re.compile(r"^git@github\.com:(?P<repo>[^/]+/[^/]+?)(?:\.git)?$"),
        re.compile(r"^https://github\.com/(?P<repo>[^/]+/[^/]+?)(?:\.git)?/?$"),
        re.compile(r"^ssh://git@github\.com/(?P<repo>[^/]+/[^/]+?)(?:\.git)?/?$"),
    )
    for pattern in patterns:
        match = pattern.match(remote)
        if match is not None:
            return match.group("repo")
    return None


def escape_markdown(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ").strip()


def render(
    root: Path,
    base_ref: str,
    base_oid: str,
    target_ref: str,
    target_oid: str,
    commits: list[Commit],
    components: dict[str, ComponentStats],
    changed_files: int,
) -> str:
    category_counts = Counter(commit.category for commit in commits)
    additions = sum(item.additions for item in components.values())
    deletions = sum(item.deletions for item in components.values())
    binary_files = sum(item.binary_files for item in components.values())
    repository = github_repository(root)
    compare_url = (
        f"https://github.com/{repository}/compare/{base_ref}...{target_oid}"
        if repository is not None
        else "unavailable (origin is not a recognized GitHub URL)"
    )
    lines = [
        "# RIPDPI release-change evidence pack",
        "",
        "> Evidence inventory only. Verify behavior in the target tree before turning a commit into a release claim.",
        "",
        "## Exact range",
        "",
        f"- Base: `{escape_markdown(base_ref)}` → `{base_oid}`",
        f"- Target: `{escape_markdown(target_ref)}` → `{target_oid}`",
        f"- Commits: **{len(commits)}**",
        f"- Changed files: **{changed_files}**",
        f"- Text diff: **+{additions} / -{deletions}**",
        f"- Binary files: **{binary_files}**",
        "- Diff accounting: rename detection disabled; renamed paths count as delete/add pairs.",
        f"- Compare: {compare_url}",
        "",
        "## Commit taxonomy",
        "",
        "| Category | Count |",
        "|---|---:|",
    ]
    for category, count in sorted(
        category_counts.items(), key=lambda item: (-item[1], item[0])
    ):
        lines.append(f"| {escape_markdown(category)} | {count} |")

    lines.extend(
        [
            "",
            "## Changed components",
            "",
            "| Component | Files | Additions | Deletions | Binary |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for component, item in sorted(
        components.items(), key=lambda pair: (-pair[1].files, pair[0])
    ):
        lines.append(
            f"| `{escape_markdown(component)}` | {item.files} | {item.additions} | "
            f"{item.deletions} | {item.binary_files} |"
        )

    lines.extend(["", "## User-facing changelog candidates", ""])
    emitted_candidate = False
    for category in USER_FACING_CATEGORIES:
        matches = [commit for commit in commits if commit.category == category]
        if not matches:
            continue
        emitted_candidate = True
        lines.extend([f"### {category}", ""])
        for commit in matches:
            scope = f"**{escape_markdown(commit.scope)}:** " if commit.scope else ""
            breaking = " **BREAKING**" if commit.breaking else ""
            lines.append(
                f"- {scope}{escape_markdown(commit.title)} (`{commit.short_oid}`){breaking}"
            )
        lines.append("")
    if not emitted_candidate:
        lines.extend(["No `feat`, `fix`, or `perf` commits were detected.", ""])

    breaking = [commit for commit in commits if commit.breaking]
    lines.extend(["## Breaking-change leads", ""])
    if breaking:
        for commit in breaking:
            lines.append(f"- {escape_markdown(commit.subject)} (`{commit.short_oid}`)")
    else:
        lines.append(
            "No conventional breaking-change marker was detected. Verify migrations manually."
        )

    lines.extend(["", "## Complete commit inventory", ""])
    grouped: dict[str, list[Commit]] = defaultdict(list)
    for commit in commits:
        grouped[commit.category].append(commit)
    for category in sorted(grouped, key=lambda name: (-len(grouped[name]), name)):
        lines.extend([f"### {category} ({len(grouped[category])})", ""])
        for commit in grouped[category]:
            lines.append(
                f"- `{commit.short_oid}` {escape_markdown(commit.subject)} "
                f"— {escape_markdown(commit.author)}, {commit.authored_at[:10]}"
            )
        lines.append("")

    lines.extend(
        [
            "## Required manual verification",
            "",
            "- Inspect final source and tests for every proposed highlight.",
            "- Confirm published flavor reachability and default/opt-in state.",
            "- Check migrations, compatibility, security/privacy, root requirements, and distribution changes.",
            "- Match merged PRs by commit OID before citing them.",
            "- Remove sensitive or internal-only details before publication.",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base", help="base stable tag or commit (default: latest earlier vX.Y.Z tag)"
    )
    parser.add_argument(
        "--target", default="HEAD", help="target ref or commit (default: HEAD)"
    )
    parser.add_argument(
        "--output", type=Path, help="write Markdown to this path instead of stdout"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        root = repository_root(Path.cwd())
        target_ref = args.target
        target_oid = resolve_commit(root, target_ref)
        base_ref = args.base or default_base(root, target_ref)
        base_oid = resolve_commit(root, base_ref)
        require_ancestor(root, base_oid, target_oid)
        commits = collect_commits(root, base_oid, target_oid)
        components, changed_files = collect_diff(root, base_oid, target_oid)
        document = render(
            root,
            base_ref,
            base_oid,
            target_ref,
            target_oid,
            commits,
            components,
            changed_files,
        )
        if args.output is None:
            sys.stdout.write(document)
        else:
            output = args.output.expanduser().resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(document, encoding="utf-8")
            print(output)
    except (GitError, OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
