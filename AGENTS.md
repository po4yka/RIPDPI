# AGENTS.md -- RIPDPI

## Project

RIPDPI is an offline-first Android network-path diagnostics and performance toolkit. Jetpack Compose provides the UI, Android services own VPN/proxy lifecycles, and repository-owned Rust modules implement the native data plane, diagnostics, relays, tunnels, and JNI adapters.

This file is the always-loaded operational contract for coding agents. Keep it below Codex's default 32 KiB project-instruction limit. Detailed architecture belongs under `docs/architecture/`; task-specific procedures belong in skills; file-specific guidance belongs in path-scoped `.claude/rules/` files.

## Source of truth

- Start architecture work at `docs/architecture/ARCHITECTURE.md`, then follow links to `NATIVE_RUST.md`, `JNI_CONTRACT.md`, `CONFIG_CONTRACTS.md`, `DIAGNOSTICS_ARCHITECTURE.md`, and `FEATURE_EXTENSION_GUIDE.md`.
- Derive protocol and relay claims from current Kotlin/Rust registries, schemas, tests, and crate existence. Old plans, README prose, and rollout notes are not authoritative when code disagrees.
- Native build properties come from `gradle.properties`; dependency versions come from `gradle/libs.versions.toml`; Rust membership and dependencies come from `native/rust/Cargo.toml` and `cargo metadata --locked`.
- Generated artifacts and reports must be regenerated through their owning task or script; do not hand-edit generated files.

## Setup

Requirements: JDK 17, the Android SDK level declared by `ripdpi.compileSdk` in `gradle.properties`, Android NDK `29.0.14206865`, the pinned Rust toolchain with Android targets, `just`, `lefthook`, and Android CLI 1.0+ (`android`). Install Android packages with slash notation such as `ndk/29.0.14206865` and `platforms/android-<compileSdk>`.

The Android build invokes the `ripdpi.android.rust-native` convention plugin from `:core:engine`, which builds the native workspace under `native/rust/`. Local non-release builds default to the host ABI; CI and releases build the full ABI set.

Merge only the required entries from `.claude/settings.example.json` into the gitignored `.claude/settings.local.json` when using optional local Claude MCP configuration. Never overwrite the committed `.claude/settings.json`; it contains repository security hooks. Security enforcement must live in committed project settings or blocking CI; local settings must not be treated as the enforcement boundary.

See `docs/contributor/build-performance.md` for Gradle, KSP, sccache, worktree, Android Studio, and parallel-build tuning.

## Build and test

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew :core:data:testDebugUnitTest
./gradlew staticAnalysis
./gradlew createModuleGraph
./gradlew :app:ciDevicesGroupGithubFullDebugAndroidTest
```

Use `just` recipes where they mirror CI. For Rust commands, pass `--locked` whenever Cargo resolves the workspace. Run the exact requested gate and report its actual result; do not replace a blocked gate with a weaker claim.

## Non-negotiable project rules

- Never extend detekt, lint, LoC, or architecture-health baselines to hide a regression. Fix the underlying violation. Golden and performance baselines follow their explicit approval workflows and are not covered by this blanket prohibition.
- The app must work fully on non-rooted devices. Root-only features are opt-in behind `root_mode_enabled` and degrade gracefully when root is unavailable.
- Do not add a required backend service. Product features work offline and locally; external data is bundled or fetched from static user-visible sources, and user data leaves the device only through explicit export.
- Before implementation, define verifiable success criteria. Reproduce defects before fixing them and surface undocumented JNI, protobuf, schema, activation, or migration contracts rather than guessing.
- Removing quality gates, custom detekt rules, lint checks, or security enforcement is out of scope unless the user explicitly requests it.
- Never edit compiled `.so` files or generated JNI outputs. Change their Rust/Kotlin sources and rebuild.

## Locales

The app ships 9 locales: en, ru, es, de, fr, fa, ar, zh-CN, and hi. Any new key in app or service resources must land in every locale in the same commit. A locale may split strings across multiple XML files, so validate parity with Android lint rather than a single-file grep:

```bash
./gradlew :app:lintGithubFullDebug :core:service:lintDebug
```

`language_name_*` values are native display names and remain identical across locales. Register new locales in `app/src/main/res/xml/locales_config.xml` and `LocalesConfigTest.kt`. README selector changes must keep `scripts/check-readme-selectors.sh` green.

## Worktree and commit workflow

Every job or feature runs in a dedicated git worktree, never directly in the `main` checkout. Read-only fan-out may use ordinary subagents; every writer needs isolated ownership, and multiple writers use separate worktrees.

Each atomic unit is a self-contained Conventional Commit with an imperative subject under 72 characters. Preserve unrelated dirty state and stage only the task slice.

Integration to `main`, worktree removal, branch deletion, and push require explicit user authorization. Once authorized, use this sequence:

1. In the job worktree, run `git fetch origin` and `git rebase origin/main`.
2. Re-run combined-tree gates on the rebased job branch.
3. In the main checkout, run `git merge --ff-only <job-branch>`.
4. Push only when separately authorized.
5. Remove the worktree and branch only after successful integration and when authorized.

Do not run `git rebase <upstream> <job-branch>` from another checkout while the job branch is checked out in its worktree.

### Serialized high-risk files

Assign these to a single writer and validate them on the combined tree:

- `native/rust/Cargo.lock` and `gradle/libs.versions.toml`.
- `*.proto`, `EngineContract.kt`, Rust `wire.rs`, and diagnostics/relay schema-version constants.
- All locale resource sets.
- Baseline and architecture-health files.
- Golden fixtures.
- `RelayKindDescriptors` and relay-core kind/backend registries.

When multiple branches are in flight, prefer a PR merge queue. Before integration run `python3 scripts/ci/check_architecture_health.py`, `cargo metadata --manifest-path native/rust/Cargo.toml --locked`, and the area-specific locale, golden, or wire-contract gates.

## Architecture map

```text
:app (Compose UI)
  -> :core:service (VPN/proxy foreground services)
  -> :core:engine (JNI + Rust native build)
  -> :core:data:* (settings, runtime state, catalogs, protobuf)
  -> :core:diagnostics (active/passive diagnostics)
  -> :core:diagnostics-data (diagnostics contracts)
```

Additional modules include `:quality:detekt-rules` and `:baselineprofile`. Convention plugins live in `build-logic/convention/`. Do not reproduce full module, crate, candidate, resolver, or CI inventories here; use the canonical architecture documents and machine-readable build configuration.

### Native artifacts

Repository-owned Android outputs include `libripdpi.so`, `libripdpi-tunnel.so`, `libripdpi-relay.so`, `libripdpi-warp.so`, `libripdpi-amneziawg.so`, and the `ripdpi-root-helper` executable. Kotlin bridges live under `core/engine`; service-owned lifecycle integration lives under `core/service`. Supported ABIs are armeabi-v7a, arm64-v8a, x86, and x86_64.

Relay, diagnostics, VPN protection, root-helper IPC, candidate-family, and transport details are maintained in `docs/architecture/` and their source registries. Validate schema versions from code before changing both sides of a Kotlin/Rust contract.

## Task board

Repository tasks live under `docs/tasks/`. Read the `repo-task-board` skill before creating, updating, triaging, executing, or closing work.

- `docs/tasks/issues/<slug>.md` is the portfolio source of truth with stable IDs.
- Simple execution lives in `docs/tasks/work/<TASK-ID>.md`; specification-driven execution lives in `openspec/changes/<change>/tasks.md`.
- `docs/tasks/board.md` is generated by `./taskctl generate-board` and read-only.
- `docs/tasks/README.md` defines the strict schema, OpenSpec risk rule, and two-commit closure lifecycle.

Use only `./taskctl` for task state, mdtask access, OpenSpec archival, validation, and closure. Direct upstream archive, `--no-validate`, manual task IDs, and deleting a task before its committed terminal state are forbidden. Shared-file ownership for parallel work must be recorded before writers start.

## Skills and subagents

Portable project skills are exposed under `.agents/skills/`. The centralized Rust catalog is pinned as the `.agents/vendor/rust-skills` submodule from `https://github.com/po4yka/rust-skills` and exposed through symlinks; initialize it with `git submodule update --init --depth 1 .agents/vendor/rust-skills`. `.claude/skills/`, `.codex/skills/`, and `.github/skills/` contain compatibility symlinks to the same entries. Never copy or locally fork centralized Rust skill bodies. Use only skills and agents present in the active tool's catalog, and read the selected `SKILL.md` completely before acting.

Claude subagents live in `.claude/agents/`; Codex subagents live in `.codex/agents/`. Prefer the narrowest specialist matching the task. Audit/review/verifier agents must be technically read-only; write-capable agents must use worktree isolation.

Codex agents do not support Claude's `skills:` preload field. Their instructions must explicitly read any required skill at runtime. Keep counterpart prompts aligned semantically even when their file formats differ.

## Path-scoped rules

Long-form file-specific rules live in `.claude/rules/` and have `paths:` frontmatter so Claude loads them only for matching work. Codex receives this routing table through `AGENTS.md` and should read the matching `.claude/rules/<name>.md` before acting:

- `vpnservice-protect-invariant.md`: outbound non-loopback sockets while VPN protection is active.
- `android-vpn-lifecycle.md`: Android VPN/FGS and native tunnel lifecycle.
- `network-fingerprint-privacy.md`: remembered-network keys, identifiers, and privacy logging.
- `golden-bless-discipline.md`: golden fixtures and any blessing operation.
- `rust-toolchain-pin.md`: Rust toolchain, Cargo, and lockfile changes.
- `llm-rust-prompts.md`: authored or reviewed Rust changes.
- `compose-preview.md`: Compose preview rendering and generated images.
- `rds-spec.md`: Compose UI and RDS implementation.
- `android-app-and-rust-concurrency-gotchas.md`: Android/Kotlin and native concurrency-sensitive code.
- `ansible-molecule.md`: only work against the sibling deployment repository's Ansible/Molecule files.

Rules are instructions, not enforcement. Security boundaries belong in committed permissions, hooks, and blocking CI checks.

## Design sources

For UI work use, in order, `DESIGN.md`, `docs/design-system.md`, the Compose theme implementation, and Roborazzi baselines. Implementation and verified baselines win when descriptive prose disagrees.

## Harness maintenance

Any change to `AGENTS.md`, `CLAUDE.md`, skills, subagents, rules, hooks, or harness CI must run the strict harness validation suite. Keep this file below 32 KiB and ensure `CLAUDE.md` imports it with `@AGENTS.md`.
