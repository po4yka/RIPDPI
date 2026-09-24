# AGENTS.md -- RIPDPI

## Project

RIPDPI is an offline-first Android network-path diagnostics and performance toolkit. Jetpack Compose provides the UI, Android services own VPN/proxy lifecycles, and repository-owned Rust modules implement the native data plane, diagnostics, relays, tunnels, and JNI adapters.

## Source of truth

- For cross-module or architecture work, start at `docs/architecture/ARCHITECTURE.md` and follow its links (`NATIVE_RUST.md`, `JNI_CONTRACT.md`, `CONFIG_CONTRACTS.md`, `DIAGNOSTICS_ARCHITECTURE.md`, `FEATURE_EXTENSION_GUIDE.md`) as the change requires.
- Derive protocol and relay claims from current Kotlin/Rust registries, schemas, tests, and crate existence. Old plans, README prose, and rollout notes are not authoritative when code disagrees.
- Native build properties come from `gradle.properties`; dependency versions come from `gradle/libs.versions.toml`; Rust membership and dependencies come from `native/rust/Cargo.toml` and `cargo metadata --locked`.
- Generated artifacts and reports are regenerated through their owning task or script, never hand-edited.

## Setup

Requirements: JDK 17, the Android SDK level declared by `ripdpi.compileSdk` in `gradle.properties`, Android NDK `29.0.14206865`, the pinned Rust toolchain with Android targets, `just`, `lefthook`, and Android CLI 1.0+ (`android`). Install Android packages with slash notation such as `ndk/29.0.14206865` and `platforms/android-<compileSdk>`.

The Android build invokes the `ripdpi.android.rust-native` convention plugin from `:core:engine`, which builds the native workspace under `native/rust/`. Local non-release builds default to the host ABI; CI and releases build the full ABI set. For Gradle, KSP, sccache, worktree, and parallel-build tuning, see `docs/contributor/build-performance.md`.

## Build, test, and verification

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew :core:data:testDebugUnitTest
./gradlew staticAnalysis
./gradlew createModuleGraph
./gradlew :app:ciDevicesGroupGithubFullDebugAndroidTest
```

Use `just` recipes where they mirror CI. For Rust commands, pass `--locked` whenever Cargo resolves the workspace (for example `cargo test -p <crate> --locked` from `native/rust/`).

Local builds, unit tests, lint, formatting, and Cargo checks use disposable outputs and no production access. Run them, fix failures caused by your change, and rerun the affected checks without asking for approval at each step.

Match verification to risk. For a local change, run the narrowest gate that exercises it (the module's unit tests, `cargo test -p <crate> --locked`). Widen to `staticAnalysis`, workspace-wide Rust checks, or device tests when the change crosses modules, touches a JNI/protobuf/wire/storage contract, service lifecycle, or build logic, or when the user asks for a specific gate. Run the exact gate the user requests and report its actual result; a blocked or skipped gate is reported as such, never replaced by a weaker claim.

Work is done when the requested change is implemented, its relevant gate passes (or its failure is reported with output), and any finished atomic unit is committed on the job branch.

## Non-negotiable project rules

- Never extend detekt, lint, LoC, or architecture-health baselines to hide a regression. Fix the underlying violation. Golden and performance baselines follow their explicit approval workflows and are not covered by this blanket prohibition.
- The app must work fully on non-rooted devices. Root-only features are opt-in behind `root_mode_enabled` and degrade gracefully when root is unavailable.
- Do not add a required backend service. Product features work offline and locally; external data is bundled or fetched from static user-visible sources, and user data leaves the device only through explicit export.
- Reproduce a defect (ideally as a failing test) before fixing it. Surface undocumented JNI, protobuf, schema, activation, or migration contracts instead of guessing them.
- Removing quality gates, custom detekt rules, lint checks, or security enforcement is out of scope unless the user explicitly requests it.
- Never edit compiled `.so` files or generated JNI outputs. Change their Rust/Kotlin sources and rebuild.

## Decision boundaries

Proceed without asking for reversible, repository-local work: reading code, editing files in your worktree, running local builds and tests, and committing finished units on the job branch.

Ask first, and name the exact action, for anything that leaves the worktree or cannot be cheaply undone: integrating into `main`, pushing, deleting worktrees or branches, rewriting shared history, tags, releases and publishing (use the `ripdpi-release` skill), blessing golden fixtures (see `golden-bless-discipline.md`), CI secrets and signing material, and any external communication.

Plan before editing only where the approach is genuinely uncertain: cross-module or contract changes, concurrency or unsafe-code redesigns, migrations, and release procedures. `docs/tasks/README.md` defines when a change requires an OpenSpec specification.

## Locales

The app ships 10 locales: en, ru, es, de, fr, fa, ar, zh-CN, hi, and pt-BR. Any new key in app or service resources must land in every locale in the same commit. A locale may split strings across multiple XML files, so validate parity with Android lint rather than a single-file grep:

```bash
./gradlew :app:lintGithubFullDebug :core:service:lintDebug
```

`language_name_*` values are native display names and remain identical across locales. Register new locales in `app/src/main/res/xml/locales_config.xml` and `LocalesConfigTest.kt`. README selector changes must keep `scripts/check-readme-selectors.sh` green.

## Worktree and commit workflow

Every job or feature that changes files runs in a dedicated git worktree, never directly in the `main` checkout; other agents share that checkout. Read-only investigation may run anywhere. Every writer needs isolated ownership, and parallel writers use separate worktrees.

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
:core:diagnostics (active/passive diagnostics; depends on :core:data:*, :core:detection, :core:engine-api)
  -> :core:diagnostics-data (diagnostics contracts)
```

Additional modules include `:quality:detekt-rules` and `:baselineprofile`. Convention plugins live in `build-logic/convention/`. Full module, crate, and CI inventories live in the architecture documents and machine-readable build configuration.

### Native artifacts

Repository-owned Android outputs include `libripdpi.so`, `libripdpi-tunnel.so`, `libripdpi-relay.so`, `libripdpi-warp.so`, `libripdpi-amneziawg.so`, and the `ripdpi-root-helper` executable. Kotlin bridges live under `core/engine`; service-owned lifecycle integration lives under `core/service`. Supported ABIs are armeabi-v7a, arm64-v8a, x86, and x86_64.

Relay, diagnostics, VPN protection, root-helper IPC, candidate-family, and transport details are maintained in `docs/architecture/` and their source registries. Validate schema versions from code before changing both sides of a Kotlin/Rust contract.

## Task board

Repository tasks live under `docs/tasks/`; use the `repo-task-board` skill when creating, updating, triaging, executing, or closing work.

- `docs/tasks/issues/<slug>.md` is the portfolio source of truth with stable IDs.
- Simple execution lives in `docs/tasks/work/<TASK-ID>.md`; specification-driven execution lives in `openspec/changes/<change>/tasks.md`.
- `docs/tasks/board.md` is generated by `./taskctl generate-board` and read-only.
- `docs/tasks/README.md` defines the strict schema, OpenSpec risk rule, and two-commit closure lifecycle.

Use only `./taskctl` for task state, mdtask access, OpenSpec archival, validation, and closure. Direct upstream archive, `--no-validate`, manual task IDs, and deleting a task before its committed terminal state are forbidden. Shared-file ownership for parallel work must be recorded before writers start.

## Skills and subagents

Project skills live in `.agents/skills/` (Codex reads them there; `.claude/skills/` and `.github/skills/` are symlink mirrors). Rust engineering skills come from the pinned `.agents/vendor/rust-skills` submodule; initialize it with `git submodule update --init --depth 1 .agents/vendor/rust-skills` and never copy or fork its skill bodies.

Subagents are defined in `.claude/agents/` (Claude Code) and `.codex/agents/` (Codex) under the same names. Delegate when isolation helps, and do small or local work directly:

- Independent review of a non-trivial diff before committing: `pr-reviewer`; for unsafe Rust, JNI, or async changes add `unsafe-code-auditor`, `jni-bridge-verifier`, or `async-cancel-safety`.
- Long test or benchmark runs and their triage: `rust-test-runner`, `android-test-runner`, `packet-smoke-debugger`, `perf-profiler`.
- Baseline or fixture updates: `native-verifier` and `golden-blesser`, which write under isolation.

Audit and review agents never modify files. Changes to skills, subagents, rules, hooks, or these entry points follow `.claude/rules/harness-maintenance.md`.

## Path-scoped rules

File-area rules live in `.claude/rules/`. Claude Code loads them automatically when matching files are read; other tools should read the matching file when working in that area:

- `vpnservice-protect-invariant.md`: outbound non-loopback sockets while VPN protection is active.
- `android-vpn-lifecycle.md`: Android VPN/FGS and native tunnel lifecycle.
- `network-fingerprint-privacy.md`: remembered-network keys, identifiers, and privacy logging.
- `golden-bless-discipline.md`: golden fixtures and any blessing operation.
- `rust-toolchain-pin.md`: Rust toolchain, Cargo, and lockfile changes.
- `llm-rust-prompts.md`: authored or reviewed Rust changes.
- `compose-preview.md`: Compose preview rendering and generated images.
- `rds-spec.md`: Compose UI and RDS implementation.
- `android-app-and-rust-concurrency-gotchas.md`: Android/Kotlin and native concurrency-sensitive code.
- `harness-maintenance.md`: agent instructions, skills, subagents, rules, hooks, and their CI.
- `ansible-molecule.md`: only work against the sibling deployment repository's Ansible/Molecule files.

## Design sources

For UI work use, in order, `DESIGN.md`, `docs/design-system.md`, the Compose theme implementation, and Roborazzi baselines. Implementation and verified baselines win when descriptive prose disagrees.
