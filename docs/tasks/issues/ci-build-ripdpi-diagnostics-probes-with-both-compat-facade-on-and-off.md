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
updated: 2026-05-16
---

- [ ] #task CI: build ripdpi-diagnostics-probes with both compat-facade on and off #repo/RIPDPI #area/ci #status/backlog 🔼

## Work log

- 2026-05-16: Reclassified to backlog — no concrete blocker recorded in frontmatter (crate scaffolding precondition is described in prose but has no corresponding issue slug).
- 2026-05-16: BLOCKED. The `ripdpi-diagnostics-probes` crate currently has only `adaptive-tuning-v1.json` and `blockpage_fingerprints.csv` tracked in git — no `Cargo.toml`, no `src/`, and the crate is NOT in `native/rust/Cargo.toml` workspace members. Adding the CI feature-matrix job requires re-establishing the crate as a proper Cargo crate (with a `compat-facade` default feature gating the re-exports) and registering it in the workspace. That re-establishment is the precondition for this CI task and is out of scope here. Scaffolding has been drafted on disk but remains uncommitted pending architectural decision on whether to resurrect this crate or delete the remaining tracked data files.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `ci-build-ripdpi-diagnostics-probes-with-both-compat-facade-on-and-off`
- **Verify:** `just lint-rust`
- **Scope (only modify these + this file + the ledger):** `.github/**`, `native/rust/crates/ripdpi-diagnostics-probes/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Senior Build/Gradle/CI Engineer.

Context ripdpi-diagnostics-probes moved its historic root re-exports under `compat::*`, gated by a default `compat-facade` feature. Without explicit CI coverage, a future change could break the no-feature shape silently.

Acceptance criteria
- CI runs `cargo check -p ripdpi-diagnostics-probes --no-default-features` and `cargo check -p ripdpi-diagnostics-probes --features compat-facade`; both must be green.
- CI-only; no live network.

Definition of done PR merged; both jobs green on a sample PR.
