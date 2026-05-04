---
title: CI: cargo-tree assertion that monitor-engine no longer pulls ripdpi-runtime-api or ripdpi-diagnostics-pcap
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

- [ ] #task CI: cargo-tree assertion that monitor-engine no longer pulls ripdpi-runtime-api or ripdpi-diagnostics-pcap #repo/RIPDPI #area/ci #status/doing 🔼

Owner: Senior Build/Gradle/CI Engineer.

Context
ripdpi-monitor-engine dropped direct deps on ripdpi-runtime-api and ripdpi-diagnostics-pcap. We want a CI guard so a future workspace edit cannot reintroduce them transitively without explicit review.

Acceptance criteria
- CI step runs `cargo tree -p ripdpi-monitor-engine -i ripdpi-runtime-api` and `cargo tree -p ripdpi-monitor-engine -i ripdpi-diagnostics-pcap`, expects no matching crate.
- Documented update procedure if either is intentionally reintroduced.
- CI-only; no live network.

Definition of done
PR merged; guard job green on main.
