#!/usr/bin/env python3
"""Inspect the current Git checkout and agent prerequisites without changing them."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path


RUST_SKILLS = ".agents/vendor/rust-skills"
REPOSITORY_ENVIRONMENT = frozenset({
    "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_PREFIX",
    "GIT_CEILING_DIRECTORIES", "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_IMPLICIT_WORK_TREE",
})


def git(path: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(path), *arguments],
        env={
            **{key: value for key, value in os.environ.items() if key not in REPOSITORY_ENVIRONMENT},
            "GIT_OPTIONAL_LOCKS": "0", "GIT_TERMINAL_PROMPT": "0",
        },
        text=True, capture_output=True, check=False, timeout=10,
    )


def value(path: Path, *arguments: str) -> str | None:
    result = git(path, *arguments)
    return result.stdout.strip() if result.returncode == 0 else None


def worktrees(path: Path, common_git_dir: str) -> list[dict]:
    result = git(path, "worktree", "list", "--porcelain", "-z")
    if result.returncode:
        raise ValueError("Git worktree registrations could not be read.")
    entries = []
    for record in result.stdout.split("\0\0"):
        fields = dict(field.partition(" ")[::2] for field in record.split("\0") if field)
        if "worktree" not in fields:
            continue
        target = Path(fields["worktree"])
        root = value(target, "rev-parse", "--show-toplevel") if target.is_dir() else None
        common = value(target, "rev-parse", "--path-format=absolute", "--git-common-dir") if root else None
        entries.append({
            "path": str(target), "head": fields.get("HEAD"),
            "branch": fields.get("branch", "").removeprefix("refs/heads/") or None,
            "bare": "bare" in fields, "exists": target.is_dir(),
            "usable": bool(root and Path(root).resolve() == target.resolve()
                           and common and Path(common).resolve() == Path(common_git_dir).resolve()),
        })
    return entries


def rust_skills(root: Path) -> dict:
    indexed = value(root, "ls-files", "--stage", "--", RUST_SKILLS)
    if not indexed or not indexed.startswith("160000 "):
        return {"state": "not_applicable"}
    result = git(root, "submodule", "status", "--", RUST_SKILLS)
    if result.returncode or not result.stdout:
        return {"state": "unavailable"}
    marker = result.stdout[0]
    state = {"-": "uninitialized", "+": "different_commit", "U": "conflict", " ": "ready"}.get(marker, "unavailable")
    if state == "ready" and not (root / RUST_SKILLS / "skills").is_dir():
        state = "missing_catalog"
    return {"path": str(root / RUST_SKILLS), "state": state}


def inspect_checkout(path: Path) -> dict:
    path = path.resolve()
    report = {
        "path": str(path), "root": None, "branch": None, "head": None,
        "bare": None, "git_dir": None, "common_git_dir": None,
        "worktrees": [], "rust_skills": {"state": "not_applicable"}, "issues": [],
        "ignored_git_environment": sorted(REPOSITORY_ENVIRONMENT.intersection(os.environ)),
    }
    if not path.is_dir():
        report["issues"].append("The requested directory does not exist.")
        return report
    try:
        report["git_dir"] = value(path, "rev-parse", "--absolute-git-dir")
        if not report["git_dir"]:
            report["issues"].append("Git does not recognize this directory as a repository.")
            return report
        report["common_git_dir"] = value(path, "rev-parse", "--path-format=absolute", "--git-common-dir")
        if not report["common_git_dir"]:
            raise ValueError("The shared Git directory could not be read.")
        report["bare"] = value(path, "rev-parse", "--is-bare-repository") == "true"
        report["root"] = value(path, "rev-parse", "--show-toplevel")
        report["branch"] = value(path, "symbolic-ref", "--quiet", "--short", "HEAD")
        report["head"] = value(path, "rev-parse", "--verify", "HEAD")
        report["worktrees"] = worktrees(path, report["common_git_dir"])
        if not report["root"]:
            report["issues"].append(
                "Git does not recognize the current directory as a worktree. "
                "Use a usable registered worktree below; inspect core.bare if this was a checkout."
            )
        else:
            report["root"] = str(Path(report["root"]).resolve())
            report["rust_skills"] = rust_skills(Path(report["root"]))
            if report["rust_skills"]["state"] not in {"ready", "not_applicable"}:
                report["issues"].append(
                    f"Rust skills: {report['rust_skills']['state']}. "
                    "Inspect the submodule before reading vendored skills."
                )
        if not report["head"]:
            report["issues"].append("HEAD has no resolvable commit.")
    except (OSError, subprocess.TimeoutExpired, ValueError) as error:
        report["issues"].append(f"Checkout inspection failed: {error}")
    return report


def display(report: dict) -> None:
    for key in ("path", "root", "branch", "head", "bare", "git_dir", "common_git_dir"):
        fallback = "detached HEAD" if key == "branch" and report["head"] else "unavailable"
        print(f"{key}: {report[key] if report[key] is not None else fallback}")
    print(f"rust_skills: {report['rust_skills']['state']}")
    if report["ignored_git_environment"]:
        print(f"ignored_git_environment: {', '.join(report['ignored_git_environment'])}")
    print("worktrees:")
    for entry in report["worktrees"]:
        state = "usable" if entry["usable"] else "bare" if entry["bare"] else "unavailable"
        print(f"  [{state}] {entry['path']} ({entry['branch'] or 'detached HEAD'})")
    for issue in report["issues"]:
        print(f"ATTENTION: {issue}")
    print("result: attention required" if report["issues"] else "result: ready")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", type=Path, default=Path.cwd(), help="Directory to inspect (default: current directory).")
    parser.add_argument("--json", action="store_true", help="Print the same report as JSON.")
    arguments = parser.parse_args()
    report = inspect_checkout(arguments.path)
    if arguments.json:
        print(json.dumps(report, indent=2))
    else:
        display(report)
    return int(bool(report["issues"]))


if __name__ == "__main__":
    raise SystemExit(main())
