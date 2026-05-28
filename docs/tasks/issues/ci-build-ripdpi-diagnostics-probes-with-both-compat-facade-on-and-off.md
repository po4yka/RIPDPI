---
title: CI: build ripdpi-diagnostics-probes with both compat-facade on and off
type: task
status: backlog
area: ci
priority: medium
owner: Senior Build Gradle CI Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-28
---

- [ ] #task CI: build ripdpi-diagnostics-probes with both compat-facade on and off #repo/RIPDPI #area/ci #status/backlog 🔼

## Work log

- 2026-05-28: Docs audit refreshed the precondition. `native/rust/crates/ripdpi-diagnostics-probes` is now a registered Cargo crate with `Cargo.toml`, `src/`, tests, and a default empty `compat-facade` feature. Root exports are unconditional today. This task is no longer blocked on crate scaffolding; the remaining work is the CI feature-matrix check below.
- 2026-05-16: Reclassified to backlog — no concrete blocker recorded in frontmatter.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `ci-build-ripdpi-diagnostics-probes-with-both-compat-facade-on-and-off`
- **Verify:** `just lint-rust`
- **Scope (only modify these + this file + the ledger):** `.github/**`, `native/rust/crates/ripdpi-diagnostics-probes/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Senior Build/Gradle/CI Engineer.

Context `ripdpi-diagnostics-probes` keeps its root exports unconditional and exposes a default-on, currently empty `compat-facade` namespace. Without explicit CI coverage, a future change could break either the no-default-features shape or the default feature shape silently.

Acceptance criteria
- CI runs `cargo check -p ripdpi-diagnostics-probes --no-default-features` and `cargo check -p ripdpi-diagnostics-probes --features compat-facade`; both must be green.
- CI-only; no live network.

Definition of done PR merged; both jobs green on a sample PR.
