---
title: CI: cargo-tree assertion that monitor-engine no longer pulls ripdpi-runtime-api or ripdpi-diagnostics-pcap
type: task
status: done
area: ci
priority: medium
owner: Senior Build Gradle CI Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-16
---

- [x] #task CI: cargo-tree assertion that monitor-engine no longer pulls ripdpi-runtime-api or ripdpi-diagnostics-pcap #repo/RIPDPI #area/ci #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `ci-cargo-tree-assertion-that-monitor-engine-no-longer-pulls-ripdpi-runtime`
- **Verify:** `just lint-rust`
- **Scope (only modify these + this file + the ledger):** `.github/**`, `native/rust/crates/ripdpi-monitor-engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Senior Build/Gradle/CI Engineer.

Context
ripdpi-monitor-engine dropped direct deps on ripdpi-runtime-api and ripdpi-diagnostics-pcap. We want a CI guard so a future workspace edit cannot reintroduce them transitively without explicit review.

Acceptance criteria
- CI step runs `cargo tree -p ripdpi-monitor-engine -i ripdpi-runtime-api` and `cargo tree -p ripdpi-monitor-engine -i ripdpi-diagnostics-pcap`, expects no matching crate.
- Documented update procedure if either is intentionally reintroduced.
- CI-only; no live network.

Definition of done
PR merged; guard job green on main.

## Work log — 2026-05-16

Local verification (both commands exit 101 = package not found):

```
$ cargo tree --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine -i ripdpi-runtime-api
error: package ID specification `ripdpi-runtime-api` did not match any packages
EXIT: 101

$ cargo tree --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine -i ripdpi-diagnostics-pcap
error: package ID specification `ripdpi-diagnostics-pcap` did not match any packages
EXIT: 101
```

CI guard added: `.github/workflows/cargo-tree-monitor-engine-guard.yml`
- Triggers on PR/push to `main` for changes to `native/rust/Cargo.toml`, `Cargo.lock`, `ripdpi-monitor-engine/**`, or the workflow file itself.
- Two steps: one per forbidden dependency; each uses `if cargo tree … 2>/dev/null; then exit 1; fi` so the job fails on reintroduction.
- Update procedure documented inline in the workflow file header.
