#!/usr/bin/env python3
"""Validate coding-agent harness manifests, discovery roots, and preloads."""

from __future__ import annotations

import re
import sys
import tomllib
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
CANONICAL_SKILLS = REPO_ROOT / ".agents" / "skills"
SKILL_MIRRORS = (
    REPO_ROOT / ".claude" / "skills",
    REPO_ROOT / ".codex" / "skills",
    REPO_ROOT / ".github" / "skills",
)
ALLOWED_SANDBOX_MODES = {"read-only", "workspace-write", "danger-full-access"}
WRITE_CAPABLE_AGENTS = {"golden-blesser", "native-verifier", "ripdpi-vault-sync"}
CODEX_WORKSPACE_WRITERS = WRITE_CAPABLE_AGENTS | {"ripdpi-doc-exporter"}


def frontmatter(path: Path) -> dict[str, object]:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise ValueError("missing YAML frontmatter")
    parts = text.split("---\n", 2)
    if len(parts) != 3:
        raise ValueError("unterminated YAML frontmatter")
    parsed = yaml.safe_load(parts[1])
    if not isinstance(parsed, dict):
        raise ValueError("frontmatter must be a mapping")
    return parsed


def skill_names() -> set[str]:
    names: set[str] = set()
    for path in sorted(CANONICAL_SKILLS.glob("*/SKILL.md")):
        metadata = frontmatter(path)
        name = path.parent.name
        if metadata.get("name") != name:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: name must be {name!r}")
        description = metadata.get("description")
        if not isinstance(description, str) or not description.strip():
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: non-empty description required")
        names.add(name)
    if not names:
        raise ValueError("no canonical skills found under .agents/skills")
    return names


def validate_mirrors(names: set[str]) -> None:
    for mirror in SKILL_MIRRORS:
        entries = {path.name: path for path in mirror.iterdir() if not path.name.startswith(".")}
        missing = sorted(names - entries.keys())
        extra = sorted(entries.keys() - names)
        if missing or extra:
            raise ValueError(
                f"{mirror.relative_to(REPO_ROOT)}: missing={missing}, extra={extra}"
            )
        for name, path in entries.items():
            if not path.is_symlink():
                raise ValueError(f"{path.relative_to(REPO_ROOT)}: mirror entry must be a symlink")
            expected = (CANONICAL_SKILLS / name).resolve(strict=True)
            actual = path.resolve(strict=True)
            if actual != expected:
                raise ValueError(
                    f"{path.relative_to(REPO_ROOT)}: resolves to {actual}, expected {expected}"
                )


def validate_claude_agents(names: set[str]) -> None:
    for path in sorted((REPO_ROOT / ".claude" / "agents").glob("*.md")):
        metadata = frontmatter(path)
        if metadata.get("name") != path.stem:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: name must match filename")
        if not isinstance(metadata.get("description"), str):
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: description required")
        preloads = metadata.get("skills", [])
        if not isinstance(preloads, list) or not all(isinstance(item, str) for item in preloads):
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: skills must be a string list")
        missing = sorted(set(preloads) - names)
        if missing:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: unknown skill preloads {missing}")
        tools = metadata.get("tools", "")
        tool_names = {item.strip() for item in tools.split(",")} if isinstance(tools, str) else set()
        mutating_tools = not tool_names.isdisjoint({"Write", "Edit"})
        if (path.stem in WRITE_CAPABLE_AGENTS or mutating_tools) and metadata.get("isolation") != "worktree":
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: write-capable agent requires worktree isolation")


def validate_codex_agents() -> None:
    for path in sorted((REPO_ROOT / ".codex" / "agents").glob("*.toml")):
        metadata = tomllib.loads(path.read_text(encoding="utf-8"))
        if metadata.get("name") != path.stem:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: name must match filename")
        for key in ("description", "developer_instructions"):
            if not isinstance(metadata.get(key), str) or not metadata[key].strip():
                raise ValueError(f"{path.relative_to(REPO_ROOT)}: non-empty {key} required")
        sandbox = metadata.get("sandbox_mode")
        if sandbox is not None and sandbox not in ALLOWED_SANDBOX_MODES:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: invalid sandbox_mode {sandbox!r}")
        if path.stem in CODEX_WORKSPACE_WRITERS and sandbox != "workspace-write":
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: write-capable agent requires workspace-write")


def validate_codex_external_writes() -> None:
    for agent in ("ripdpi-vault-sync", "ripdpi-doc-exporter"):
        path = REPO_ROOT / ".codex" / "agents" / f"{agent}.toml"
        content = path.read_text(encoding="utf-8")
        if ".tmp/agent-exports/" not in content:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: missing workspace-local export staging")
        for forbidden in ("--output-dir ~/Desktop", "Run in ~/GitRep/RIPDPI"):
            if forbidden in content:
                raise ValueError(
                    f"{path.relative_to(REPO_ROOT)}: workspace-write profile targets external path {forbidden!r}"
                )


def validate_rules() -> None:
    claude_rules = REPO_ROOT / ".claude" / "rules"
    codex_rules = REPO_ROOT / ".codex" / "rules"
    names = {path.name for path in claude_rules.glob("*.md")}
    if names != {path.name for path in codex_rules.glob("*.md")}:
        raise ValueError(".claude/rules and .codex/rules names differ")
    for path in sorted(claude_rules.glob("*.md")):
        metadata = frontmatter(path)
        paths = metadata.get("paths")
        if not isinstance(paths, list) or not paths or not all(isinstance(item, str) for item in paths):
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: non-empty paths list required")


def validate_instruction_entrypoints() -> None:
    agents = REPO_ROOT / "AGENTS.md"
    claude = REPO_ROOT / "CLAUDE.md"
    if len(agents.read_bytes()) >= 32 * 1024:
        raise ValueError("AGENTS.md exceeds the default 32 KiB Codex instruction limit")
    if not claude.read_text(encoding="utf-8").startswith("@AGENTS.md\n"):
        raise ValueError("CLAUDE.md must import AGENTS.md with @AGENTS.md")


def validate_worktree_integration_contract() -> None:
    instructions = (REPO_ROOT / "AGENTS.md").read_text(encoding="utf-8")
    required = (
        "In the job worktree, run `git fetch origin` and `git rebase origin/main`.",
        "In the main checkout, run `git merge --ff-only <job-branch>`.",
    )
    for statement in required:
        if statement not in instructions:
            raise ValueError(f"AGENTS.md missing worktree integration contract: {statement}")
    if re.search(r"git rebase\s+origin/main\s+\S+", instructions):
        raise ValueError("AGENTS.md must not rebase a branch that is checked out in another worktree")


def validate_specialist_ground_truth() -> None:
    agent_pairs = {
        "jni-bridge-verifier": (
            "ripdpi-amneziawg-android",
            "core/engine/src/main/kotlin",
        ),
        "android-test-runner": (
            "connectedGithubFullDebugAndroidTest",
            "ripdpi.localNativeAbisDefault=host",
            "app-github-universal-debug.apk",
        ),
        "native-verifier": (
            "libripdpi-relay.so",
            "libripdpi-warp.so",
            "libripdpi-amneziawg.so",
        ),
        "rust-api-auditor": (
            "cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps",
        ),
        "perf-profiler": ("ripdpi-proxy-runtime",),
    }
    forbidden = (
        "across 3 adapter crates",
        "connectedDebugAndroidTest",
        "createDebugAndroidTestCoverageReport",
        "ripdpi.localNativeAbisDefault=arm64-v8a",
        "40-crate",
        "--package ripdpi-runtime ",
    )
    for agent, required in agent_pairs.items():
        paths = (
            REPO_ROOT / ".claude" / "agents" / f"{agent}.md",
            REPO_ROOT / ".codex" / "agents" / f"{agent}.toml",
        )
        for path in paths:
            content = path.read_text(encoding="utf-8")
            for value in required:
                if value not in content:
                    raise ValueError(
                        f"{path.relative_to(REPO_ROOT)}: missing current specialist surface {value!r}"
                    )
            for value in forbidden:
                if value in content:
                    raise ValueError(
                        f"{path.relative_to(REPO_ROOT)}: stale specialist surface {value!r}"
                    )
        if agent == "jni-bridge-verifier":
            for path in paths:
                if "app/src/main/kotlin" in path.read_text(encoding="utf-8"):
                    raise ValueError(
                        f"{path.relative_to(REPO_ROOT)}: JNI declarations live under core/engine"
                    )


def main() -> int:
    try:
        names = skill_names()
        validate_mirrors(names)
        validate_claude_agents(names)
        validate_codex_agents()
        validate_codex_external_writes()
        validate_rules()
        validate_instruction_entrypoints()
        validate_worktree_integration_contract()
        validate_specialist_ground_truth()
    except (OSError, ValueError, tomllib.TOMLDecodeError, yaml.YAMLError) as exc:
        print(f"HARNESS MANIFEST ERROR: {exc}", file=sys.stderr)
        return 1
    print(
        "HARNESS MANIFESTS CLEAN -- "
        f"{len(names)} skills, "
        f"{len(list((REPO_ROOT / '.claude/agents').glob('*.md')))} Claude agents, "
        f"{len(list((REPO_ROOT / '.codex/agents').glob('*.toml')))} Codex agents"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
