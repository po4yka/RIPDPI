---
title: Spike adversarial network harness, generator-driven packet-smoke, and real-provider Phase-16 matrix
type: task
status: done
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-25
---

- [x] #task Spike adversarial network harness, generator-driven packet-smoke, and real-provider Phase-16 matrix #repo/RIPDPI #area/testing #status/done 🔼 — real-provider evidence contract wired into Phase-16 and follow-up issues filed

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-adversarial-network-harness-and-realprovider-matrix`
- **Verify:** `python3 scripts/ci/phase16_matrix.py validate && python3 -m unittest scripts.tests.test_phase16_matrix`
- **Scope (only modify these + this file + the ledger):** `docs/architecture/**`, `docs/testing.md`, `docs/tasks/**`, `contract-fixtures/phase16_lab_matrix.json`, `.github/workflows/phase16-matrix.yml`, `scripts/ci/phase16_matrix.py`, `scripts/ci/run-phase16-matrix-entry.sh`, `scripts/ci/phase16_pcap_summary.py`, `scripts/tests/test_phase16_matrix.py`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Cover the three infrastructure-shaped gaps left after the 2026-05-16 test-pyramid review:

1. An adversarial L7 emulator in `test-lab/chaos/`.
2. A generator-driven packet-smoke harness across the 7-dimensional desync parameter space.
3. A Phase-16 lab matrix that runs on actual carrier SIM rather than only synthetic environments.

Each is sized as a design-then-implement effort: the goal of this task is to land a one-page design spike per item under `docs/architecture/` and to follow up with concrete implementation issues once the design is reviewed.

## Context

`docs/testing.md` § "Known gaps & coverage roadmap" lists these as "open and not yet tracked". They are not bugs; they are coverage shapes the existing pyramid does not produce.

- The Toxiproxy + netem stack in `test-lab/chaos/` produces packet loss, latency, and jitter, but it does not reproduce the aggressive L7 middlebox behaviour set (RST-injection on SNI, blackhole after N bytes, selective QUIC-Initial drop, MTU-clamp).
- `scripts/ci/packet-smoke-scenarios.json` hand-lists ~10-15 scenarios against a 7-dimensional input space (split offset, TLS record split, TLS-randrec profile, UDP burst, QUIC fake profile, fake-TTL, OOB-byte placement).
- `contract-fixtures/phase16_lab_matrix.json` is synthetic. Self-hosted `ripdpi-lab` runners do not yet exercise real carrier SIMs.

## Acceptance criteria

- [x] `docs/architecture/spike-l7-adversarial-emulator.md` exists and enumerates the target adversary pattern set (RST-after-SNI, SNI-replace, IP-blackhole, QUIC-Initial-drop, MTU-clamp) with a proposed implementation surface (nftables + `nfqueue`/scapy or equivalent) and a contract for "the harness reports pass/fail/partial per (desync mode, adversary pattern) cell".
- [x] `docs/architecture/spike-generator-packet-smoke.md` exists and defines the 7-dimensional input space, the sampling strategy (named L7 patterns always run + N random samples per PR), and the oracle (pcap-byte-shape vs. expected plan, using the existing `phase16_pcap_summary.py` summary contract or a successor).
- [x] `docs/architecture/spike-phase16-real-provider.md` exists and proposes how a self-hosted `ripdpi-lab` runner exposes symbolic target-provider namespaces to the existing `contract-fixtures/phase16_lab_matrix.json` fanout, including secret handling and a fallback that keeps PR CI viable when the runner is offline.
- [x] Each spike doc links back to this task and is referenced from `docs/testing.md` § "Known gaps & coverage roadmap".

## Completion proof

- `python3 scripts/ci/phase16_matrix.py validate` exit 0 on 2026-05-25.
- `python3 -m unittest scripts.tests.test_phase16_matrix` exit 0 on 2026-05-25.
- Follow-up implementation issues filed: [`gate-l7-adversarial-emulator-in-phase16-release-matrix.md`](gate-l7-adversarial-emulator-in-phase16-release-matrix.md), [`add-generator-driven-packet-smoke-sampling.md`](add-generator-driven-packet-smoke-sampling.md), and [`operate-phase16-real-provider-sim-runner.md`](operate-phase16-real-provider-sim-runner.md).

## Definition of done

Three design-spike documents merged, this task moved from `backlog` to `done`, and three follow-up implementation issues filed (one per spike) that the design docs unblock.

## Why this is a single task instead of three

The three coverage gaps are highly correlated: the adversarial harness produces the scenarios, the generator decides which scenarios to run, and the real-provider matrix gates which scenarios are credible at release time. Designing them as three independent threads risks contracts that do not compose. The implementation that follows the design spikes will be three separate issues.

## Non-goals

- Implementing the harness, generator, or runner. That is for the follow-up issues this spike unblocks.
- Replacing Toxiproxy / netem. The adversarial emulator is additive.
- Removing hand-authored packet-smoke scenarios. The generator augments the named scenarios; it does not replace them.
