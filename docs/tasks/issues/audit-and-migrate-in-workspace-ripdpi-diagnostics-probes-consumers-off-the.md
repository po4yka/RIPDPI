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
