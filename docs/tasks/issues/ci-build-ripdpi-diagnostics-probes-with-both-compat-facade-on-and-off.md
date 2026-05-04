---
title: CI: build ripdpi-diagnostics-probes with both compat-facade on and off
type: task
status: doing
area: ci
priority: medium
owner: Senior Build Gradle CI Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task CI: build ripdpi-diagnostics-probes with both compat-facade on and off #repo/RIPDPI #area/ci #status/doing 🔼

Owner: Senior Build/Gradle/CI Engineer.

Context
ripdpi-diagnostics-probes moved its historic root re-exports under `compat::*`, gated by a default `compat-facade` feature. Without explicit CI coverage, a future change could break the no-feature shape silently.

Acceptance criteria
- CI runs `cargo check -p ripdpi-diagnostics-probes --no-default-features` and `cargo check -p ripdpi-diagnostics-probes --features compat-facade`; both must be green.
- CI-only; no live network.

Definition of done
PR merged; both jobs green on a sample PR.
