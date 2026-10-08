---
id: TST-1791477382746187
title: Build reproducible local VPN acceptance lab
kind: feature
status: doing
area: testing
priority: high
owner: Acceptance lab coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: local-vpn-acceptance-lab
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Run reproducible local Android VPN acceptance against independent protocol peers,
with a Linux router for controlled faults and evidence that distinguishes native
interop, Android TUN traffic, packet fidelity, and external-provider acceptance.

## Acceptance criteria

- One manifest drives local and CI selection, required scenarios, peer versions,
  evidence levels, and explicit external-provider boundaries.
- A strict runner rejects missing, skipped, stale, or incomplete mandatory evidence
  and emits JSON and JUnit reports with source and artifact identities.
- A disposable ARM64 Lima VM hosts isolated Linux routing and protocol peers;
  TCP and UDP path controls prove that faults affect the selected data path.
- Android acceptance uses real native libraries, separate-UID traffic, peer
  receipts, positive and negative authentication, restart, and no-bypass checks.
- Existing independent Xray, AWG, SSH, Mieru, and AnyTLS peers are reused;
  supported local protocol and fault scenarios have executable adapters.
- Packet-fidelity checks run on Linux paths and never infer original packets
  from an emulator NAT or a TCP forwarding proxy.
- Local correctness, CI, Android, VM, and external-provider evidence remain
  separate. Missing runtime prerequisites fail visibly, never report success.

## Ownership

- Coordinator: portfolio/OpenSpec, test-lab/acceptance manifest and orchestration,
  shared evidence contracts, documentation, justfile, and CI integration.
- VM worker: test-lab/acceptance/vm/**, test-lab/lima/ripdpi-acceptance.yaml,
  existing ripdpi-netem.yaml readiness fix, and VM-specific tests.
- Android worker: scripts/ci/run-android-local-acceptance.py, Android acceptance
  adapters/tests, scripts/fixtures/xray-provider-peer/**, and their focused runner tests.
- Peer worker: test-lab/acceptance/peers/**, protocol peer adapters and tests, and Hysteria2 native interop tests.
- Each worker uses a separate worktree. The coordinator integrates commits.
  Shared manifests, task files, workflow files, Cargo.lock, dependency versions,
  schemas, and production registries have one coordinator writer.
