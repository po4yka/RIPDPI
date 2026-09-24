#!/usr/bin/env python3
"""Validate coding-agent harness manifests, discovery roots, skill routing metadata, and agent parity."""

from __future__ import annotations

import configparser
import json
import re
import sys
import tomllib
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
CANONICAL_SKILLS = REPO_ROOT / ".agents" / "skills"
CENTRAL_RUST_SKILLS = REPO_ROOT / ".agents" / "vendor" / "rust-skills" / "skills"
# Codex reads .agents/skills directly; Claude Code reads .claude/skills; GitHub Copilot reads .github/skills.
SKILL_MIRRORS = (
    REPO_ROOT / ".claude" / "skills",
    REPO_ROOT / ".github" / "skills",
)
ALLOWED_SANDBOX_MODES = {"read-only", "workspace-write", "danger-full-access"}
WRITE_CAPABLE_AGENTS = {"golden-blesser", "native-verifier", "ripdpi-vault-sync"}
CODEX_WORKSPACE_WRITERS = WRITE_CAPABLE_AGENTS | {"ripdpi-doc-exporter"}
# Auditors and reviewers never modify files: read-only sandbox in Codex, no Write/Edit tools in Claude Code.
READ_ONLY_AGENTS = {
    "arch-layer-auditor",
    "async-cancel-safety",
    "jni-bridge-verifier",
    "kotlin-design-auditor",
    "pr-reviewer",
    "rust-api-auditor",
    "unsafe-code-auditor",
}
# Claude Code subagent `model:` values. Omitting the key inherits the session model (preferred).
CLAUDE_MODEL_ALIASES = {"inherit", "opus", "sonnet", "haiku", "fable"}
# Claude Code SKILL.md frontmatter keys; unknown keys are silently ignored by Claude Code, so a typo disables intent.
SKILL_FRONTMATTER_KEYS = {
    "name",
    "description",
    "when_to_use",
    "argument-hint",
    "arguments",
    "disable-model-invocation",
    "user-invocable",
    "allowed-tools",
    "disallowed-tools",
    "model",
    "effort",
    "context",
    "agent",
    "background",
    "hooks",
    "paths",
    "shell",
    "metadata",
    "license",
    "compatibility",
}
# Codex shortens skill descriptions to fit a small listing budget, so project skills keep them short.
MAX_LOCAL_SKILL_DESCRIPTION = 250
GENERATED_ASSET_LOCK = REPO_ROOT / "tools" / "tasking" / "generated-assets.lock.json"
# Centralized Rust skills deliberately not exposed in this repository, with the missing surface that justifies it.
# When the submodule adds a skill, expose it (symlink) or list it here; re-expose one when the surface appears.
EXCLUDED_VENDOR_SKILLS = {
    "rust-cli": "ripdpi-cli is a local debug binary without clap or a published CLI contract",
    "rust-crate-release": "no crate is published to a registry",
    "rust-database": "no SQL or embedded database in native/rust",
    "rust-embedded-no-std": "no no_std crates",
    "rust-ios-build": "Android-only application",
    "rust-native-linking": "no build.rs or native C library linking in the workspace",
    "rust-swift-ffi": "no Swift consumer",
    "rust-wasm": "no WebAssembly target",
    "uniffi-boundary": "the JNI boundary is hand-written with the jni crate, not UniFFI",
    "uniffi-packaging-versioning": "the JNI boundary is hand-written with the jni crate, not UniFFI",
}
# Former local skills replaced by the centralized catalog; must not be reintroduced as local copies.
REMOVED_LOCAL_RUST_SKILLS = {
    "mutation-testing",
    "native-jni-development",
    "native-profiling",
    "rust-android-jni",
    "rust-android-ndk",
    "rust-android-telemetry",
    "rust-io-loop",
    "rust-jni-bridge",
    "rust-lint-config",
}


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


def generated_skill_files() -> set[str]:
    payload = json.loads(GENERATED_ASSET_LOCK.read_text(encoding="utf-8"))
    return set(payload.get("files", {}))


def skill_names() -> set[str]:
    names: set[str] = set()
    generated = generated_skill_files()
    for path in sorted(CANONICAL_SKILLS.glob("*/SKILL.md")):
        metadata = frontmatter(path)
        relative = path.relative_to(REPO_ROOT)
        name = path.parent.name
        if metadata.get("name") != name:
            raise ValueError(f"{relative}: name must be {name!r}")
        description = metadata.get("description")
        if not isinstance(description, str) or not description.strip():
            raise ValueError(f"{relative}: non-empty description required")
        names.add(name)
        if path.parent.is_symlink():
            continue  # centralized skill; content is owned upstream
        unknown = sorted(set(metadata) - SKILL_FRONTMATTER_KEYS)
        if unknown:
            raise ValueError(f"{relative}: unknown frontmatter keys {unknown}")
        if relative.as_posix() not in generated and len(description) > MAX_LOCAL_SKILL_DESCRIPTION:
            raise ValueError(
                f"{relative}: description is {len(description)} chars; keep it under "
                f"{MAX_LOCAL_SKILL_DESCRIPTION} and move detail into the body"
            )
        validate_invocation_policy(path, metadata)
    if not names:
        raise ValueError("no canonical skills found under .agents/skills")
    return names


def validate_invocation_policy(path: Path, metadata: dict[str, object]) -> None:
    """Manual-only skills must be manual-only in both Claude Code and Codex."""
    manual_claude = metadata.get("disable-model-invocation") is True
    policy_path = path.parent / "agents" / "openai.yaml"
    manual_codex = False
    if policy_path.is_file():
        policy = yaml.safe_load(policy_path.read_text(encoding="utf-8")) or {}
        manual_codex = (policy.get("policy") or {}).get("allow_implicit_invocation") is False
    if manual_claude != manual_codex:
        raise ValueError(
            f"{path.parent.relative_to(REPO_ROOT)}: disable-model-invocation ({manual_claude}) must match "
            f"agents/openai.yaml policy.allow_implicit_invocation: false ({manual_codex})"
        )


def validate_central_rust_skills(names: set[str]) -> None:
    gitmodules_path = REPO_ROOT / ".gitmodules"
    gitmodules = configparser.ConfigParser()
    gitmodules.read(gitmodules_path, encoding="utf-8")
    section = 'submodule ".agents/vendor/rust-skills"'
    if not gitmodules.has_section(section):
        raise ValueError("central Rust skill submodule is not registered")
    if gitmodules.get(section, "path", fallback="") != ".agents/vendor/rust-skills":
        raise ValueError("central Rust skill submodule path is incorrect")
    if gitmodules.get(section, "url", fallback="") != "https://github.com/po4yka/rust-skills.git":
        raise ValueError("central Rust skill submodule URL is incorrect")

    if not CENTRAL_RUST_SKILLS.is_dir():
        raise ValueError("central Rust skill submodule is not initialized")
    source_names = {path.parent.name for path in CENTRAL_RUST_SKILLS.glob("*/SKILL.md")}
    if not source_names:
        raise ValueError("central Rust skill catalog is empty")

    excluded = set(EXCLUDED_VENDOR_SKILLS)
    exposed = source_names & names
    undecided = sorted(source_names - exposed - excluded)
    stale_exclusions = sorted(excluded - source_names)
    excluded_but_exposed = sorted(excluded & names)
    local_rust = {name for name in names if name.startswith("rust-")}
    extra = sorted((local_rust - source_names) | (names & REMOVED_LOCAL_RUST_SKILLS))
    if undecided or stale_exclusions or excluded_but_exposed or extra:
        raise ValueError(
            "central Rust skill exposure mismatch: "
            f"expose or exclude={undecided}, excluded but not upstream={stale_exclusions}, "
            f"excluded but exposed={excluded_but_exposed}, local copies={extra}"
        )

    for name in sorted(exposed):
        path = CANONICAL_SKILLS / name
        if not path.is_symlink():
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: central Rust skill must be a symlink")
        expected = (CENTRAL_RUST_SKILLS / name).resolve(strict=True)
        actual = path.resolve(strict=True)
        if actual != expected:
            raise ValueError(
                f"{path.relative_to(REPO_ROOT)}: resolves to {actual}, expected {expected}"
            )


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
        model = metadata.get("model")
        if model is not None and not (
            isinstance(model, str) and (model in CLAUDE_MODEL_ALIASES or model.startswith("claude-"))
        ):
            raise ValueError(
                f"{path.relative_to(REPO_ROOT)}: model {model!r} is not a Claude Code model value; "
                "omit it to inherit the session model"
            )
        preloads = metadata.get("skills", [])
        if not isinstance(preloads, list) or not all(isinstance(item, str) for item in preloads):
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: skills must be a string list")
        missing = sorted(set(preloads) - names)
        if missing:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: unknown skill preloads {missing}")
        tools = metadata.get("tools", "")
        tool_names = {item.strip() for item in tools.split(",")} if isinstance(tools, str) else set()
        mutating_tools = not tool_names.isdisjoint({"Write", "Edit"})
        if path.stem in READ_ONLY_AGENTS and mutating_tools:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: read-only audit agent must not have Write/Edit tools")
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
        if sandbox not in ALLOWED_SANDBOX_MODES:
            raise ValueError(
                f"{path.relative_to(REPO_ROOT)}: declare sandbox_mode explicitly (got {sandbox!r})"
            )
        if path.stem in READ_ONLY_AGENTS and sandbox != "read-only":
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: read-only audit agent requires sandbox_mode = \"read-only\"")
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


def validate_agent_parity() -> None:
    """Every Claude agent has a Codex counterpart that names the skills Claude preloads."""
    for path in sorted((REPO_ROOT / ".claude" / "agents").glob("*.md")):
        counterpart = REPO_ROOT / ".codex" / "agents" / f"{path.stem}.toml"
        if not counterpart.is_file():
            raise ValueError(f"{counterpart.relative_to(REPO_ROOT)}: missing Codex counterpart")
        instructions = tomllib.loads(counterpart.read_text(encoding="utf-8"))["developer_instructions"]
        # Codex cannot preload skills, so the counterpart must tell the agent which skills to read.
        unnamed = sorted(
            skill
            for skill in frontmatter(path).get("skills", [])
            if not re.search(rf"(?<![\w-]){re.escape(skill)}(?![\w-])", instructions)
        )
        if unnamed:
            raise ValueError(
                f"{counterpart.relative_to(REPO_ROOT)}: developer_instructions must name preloaded skills {unnamed}"
            )


def validate_rules() -> None:
    for path in sorted((REPO_ROOT / ".claude" / "rules").glob("*.md")):
        metadata = frontmatter(path)
        paths = metadata.get("paths")
        if not isinstance(paths, list) or not paths or not all(isinstance(item, str) for item in paths):
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: non-empty paths list required")
        # Claude Code reads only `paths` from rule frontmatter; anything else is silently ignored.
        if set(metadata) != {"paths"}:
            raise ValueError(f"{path.relative_to(REPO_ROOT)}: rule frontmatter may only contain paths")


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


def validate_skill_portability() -> None:
    compose_root = CANONICAL_SKILLS / "compose"
    compose_paths = (
        compose_root / "SKILL.md",
        compose_root / "references" / "diagnostics.md",
        compose_root / "references" / "performance.md",
        compose_root / "references" / "report-template.md",
    )
    compose_content = "\n".join(path.read_text(encoding="utf-8") for path in compose_paths)
    for forbidden in (
        "/Users/po4yka",
        "assembleRelease",
        "COMPOSE-AUDIT-REPORT",
        ".claude/skills/compose",
    ):
        if forbidden in compose_content:
            raise ValueError(f".agents/skills/compose: non-portable contract {forbidden!r}")
    for required in (
        "git rev-parse --show-toplevel",
        ":app:compileGithubFullDebugKotlin",
        "build/reports/compose/compose-audit-<YYYY-MM-DD>.md",
    ):
        if required not in compose_content:
            raise ValueError(f".agents/skills/compose: missing portable contract {required!r}")

    scanner_path = (
        CANONICAL_SKILLS
        / "ripdpi-app-network-architect"
        / "scripts"
        / "analyze_app_network_surface.py"
    )
    scanner_content = scanner_path.read_text(encoding="utf-8")
    for excluded in (".agents", ".claude", ".codex", ".github", "AGENTS.md", "CLAUDE.md"):
        if f'"{excluded}"' not in scanner_content:
            raise ValueError(
                f"{scanner_path.relative_to(REPO_ROOT)}: missing harness exclusion {excluded!r}"
            )


def validate_factual_ground_truth() -> None:
    instructions = (REPO_ROOT / "AGENTS.md").read_text(encoding="utf-8")
    if "declared by `ripdpi.compileSdk` in `gradle.properties`" not in instructions:
        raise ValueError("AGENTS.md must derive compileSdk from gradle.properties")
    if instructions.count("libripdpi-amneziawg.so") != 1:
        raise ValueError("AGENTS.md must list the AmneziaWG artifact exactly once")

    factual_paths = (
        CANONICAL_SKILLS / "gradle-build-system" / "SKILL.md",
        CANONICAL_SKILLS / "edge-to-edge" / "SKILL.md",
        CANONICAL_SKILLS / "r8-jni-keep-rules" / "SKILL.md",
        CANONICAL_SKILLS / "rust-async-internals" / "SKILL.md",
        CANONICAL_SKILLS / "rust-event-loop-state" / "SKILL.md",
        CANONICAL_SKILLS / "rust-panic-safety" / "SKILL.md",
        CANONICAL_SKILLS / "rust-unsafe" / "SKILL.md",
        REPO_ROOT / ".claude" / "agents" / "kotlin-design-auditor.md",
        REPO_ROOT / ".claude" / "agents" / "rust-api-auditor.md",
        REPO_ROOT / ".claude" / "agents" / "async-cancel-safety.md",
        REPO_ROOT / ".claude" / "agents" / "jni-bridge-verifier.md",
        REPO_ROOT / ".claude" / "agents" / "native-verifier.md",
        REPO_ROOT / ".codex" / "agents" / "kotlin-design-auditor.toml",
        REPO_ROOT / ".codex" / "agents" / "async-cancel-safety.toml",
        REPO_ROOT / ".codex" / "agents" / "jni-bridge-verifier.toml",
        REPO_ROOT / ".codex" / "agents" / "native-verifier.toml",
        REPO_ROOT / ".claude" / "rules" / "android-app-and-rust-concurrency-gotchas.md",
    )
    factual_content = "\n".join(path.read_text(encoding="utf-8") for path in factual_paths)
    for stale in (
        "compileSdk = 36",
        "Gradle 9.4",
        "14 constructor params",
        "71+ Hilt modules",
        "~548 lines",
        "2 crates currently use anyhow",
        "io_loop.rs:122",
        "session.rs:88",
    ):
        if stale in factual_content:
            raise ValueError(f"harness contains stale factual snapshot {stale!r}")
    if re.search(r"(?:MainActivity\.kt|RipDpiNavHost\.kt|libs\.versions\.toml):\d+", factual_content):
        raise ValueError("harness contains brittle Android source line anchors")
    if re.search(
        r"(?:native/rust|app/src)/[^`\s]+\.(?:rs|kt|kts):\d+(?:-\d+)?",
        factual_content,
    ):
        raise ValueError("harness contains brittle project source line anchors")

    provider_path = (
        REPO_ROOT
        / "quality"
        / "detekt-rules"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "poyka"
        / "ripdpi"
        / "quality"
        / "detekt"
        / "RipDpiRuleSetProvider.kt"
    )
    provider_content = provider_path.read_text(encoding="utf-8")
    registered_rules = set(re.findall(r"^\s+([A-Z][A-Za-z0-9]+)\(config\),$", provider_content, re.MULTILINE))
    if not registered_rules:
        raise ValueError("RipDpiRuleSetProvider.kt contains no discoverable registered rules")
    skill_content = (CANONICAL_SKILLS / "detekt-custom-rules" / "SKILL.md").read_text(encoding="utf-8")
    missing_rules = sorted(rule for rule in registered_rules if rule not in skill_content)
    if missing_rules:
        raise ValueError(f"detekt-custom-rules skill omits registered rules {missing_rules}")

    lifecycle = (REPO_ROOT / ".claude" / "rules" / "android-vpn-lifecycle.md").read_text(encoding="utf-8")
    for required in (
        "inherits the creating thread's signal mask",
        "process-wide disposition",
        "android_support::ignore_sigpipe()",
        "it is not a Rust panic",
    ):
        if required not in lifecycle:
            raise ValueError(f"android-vpn-lifecycle.md missing SIGPIPE contract {required!r}")
    for stale in ("do NOT inherit the mask", "panic that originates from an unhandled SIGPIPE"):
        if stale in lifecycle:
            raise ValueError(f"android-vpn-lifecycle.md contains stale SIGPIPE claim {stale!r}")


def main() -> int:
    try:
        names = skill_names()
        validate_central_rust_skills(names)
        validate_mirrors(names)
        validate_claude_agents(names)
        validate_codex_agents()
        validate_agent_parity()
        validate_codex_external_writes()
        validate_rules()
        validate_instruction_entrypoints()
        validate_worktree_integration_contract()
        validate_specialist_ground_truth()
        validate_skill_portability()
        validate_factual_ground_truth()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, tomllib.TOMLDecodeError, yaml.YAMLError) as exc:
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
