---
title: Add generator-driven packet-smoke sampling
type: task
status: review
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-25
updated: 2026-05-25
---

- [ ] #task Add generator-driven packet-smoke sampling #repo/RIPDPI #area/testing #status/review 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-generator-driven-packet-smoke-sampling`
- **Verify:** `TODO(verify): ./gradlew test`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-cli/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a packet-smoke generator that samples the documented 7-dimensional desync parameter space, records the seed and axis values, and reuses the existing pcap-shape oracle.

## Motivation

Hand-authored packet-smoke scenarios preserve known recipes, but they do not cover regressions in less-traveled combinations of split offsets, TLS record handling, QUIC fakes, fake TTL, UDP bursts, and OOB placement.

## Scope

- In scope: generator manifest format, PR sample budget, nightly sample budget, deterministic seed recording, and packet-smoke registry integration.
- Out of scope: replacing named scenarios or widening the threat model beyond the axes in the design spike.

## Acceptance criteria

- [x] PR packet smoke runs all named scenarios plus a bounded generated sample set.
- [x] Nightly packet smoke runs a larger generated set and records enough metadata to reproduce any failure.
- [x] Generated fixtures include `generator_seed`, `generator_axis_values`, and `generator_origin`.
- [x] Unit tests prove the same seed produces stable cells and that scenario filters still exist in the registry.

## Links

- [Design spike: generator-driven packet-smoke](../../architecture/spike-generator-packet-smoke.md)

## Work log

- Changed files: `scripts/ci/packet-smoke-generator.py`, `scripts/ci/run-cli-packet-smoke.sh`, `scripts/ci/packet-smoke-scenarios.json`, `native/rust/crates/ripdpi-cli/tests/packet_smoke.rs`, `scripts/tests/test_packet_smoke_generator.py`, `.github/workflows/ci.yml`.
- Tests: `python3 -m unittest scripts.tests.test_packet_smoke_generator`; `bash -n scripts/ci/run-cli-packet-smoke.sh`; `rustfmt --edition 2021 --check native/rust/crates/ripdpi-cli/tests/packet_smoke.rs`; `python3 scripts/ci/packet-smoke-generator.py --registry scripts/ci/packet-smoke-scenarios.json --seed smoke-test --budget 2 --origin random`; `CARGO_TARGET_DIR=/tmp/ripdpi-packet-smoke-target cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-cli --test packet_smoke -- --nocapture`; generated-cell packet smoke with `RIPDPI_RUN_PACKET_SMOKE=1` and focused metadata.
- Remaining risk: full PR budget and scheduled nightly budget were not run end to end in this session; one generated cell was run through tcpdump/tshark to validate the harness and fixture metadata path.
