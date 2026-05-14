---
title: Audit and migrate in-workspace ripdpi-diagnostics-probes consumers off the compat facade
type: task
status: doing
area: rust-native
priority: medium
owner: Senior Rust Native Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Audit and migrate in-workspace ripdpi-diagnostics-probes consumers off the compat facade #repo/RIPDPI #area/rust-native #status/doing 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `audit-and-migrate-in-workspace-ripdpi-diagnostics-probes-consumers-off-the`
- **Verify:** `just test-rust`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-diagnostics-probes/**`, `native/rust/crates/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Senior Rust Native Engineer.

Context
ripdpi-diagnostics-probes is now a compat facade for external consumers. In-workspace callers should depend directly on the narrower ripdpi-diagnostics-* crates so the compat-facade feature can eventually be marked external-only.

Acceptance criteria
- Inventory every in-workspace caller of `ripdpi_diagnostics_probes::*` (now `compat::*`).
- Migrate each caller to the appropriate narrow ripdpi-diagnostics-* crate.
- `rg "ripdpi-diagnostics-probes" native/rust/crates -l` returns only the crate itself plus documented external boundary.
- All affected crates compile and tests pass.
- No behavioral change.

Definition of done
PR merged; QA Lead confirms inventory in POY-4 closure note.
