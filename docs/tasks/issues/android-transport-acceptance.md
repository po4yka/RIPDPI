---
id: TST-1792776319490914
title: Verify Android transport payloads and migration through real tunnels
kind: feature
status: doing
area: testing
priority: high
owner: Android transport acceptance
parent: null
blocked_by: []
spec_mode: required
openspec_change: android-transport-acceptance
created: 2026-10-23
updated: 2026-10-23
---

## Goal

Risk: high because the acceptance contract covers VPN routing, TLS trust and migration.

Run authenticated Android VPN traffic through REALITY, nginx TLS XHTTP,
Hysteria2, and AmneziaWG. Verify payload integrity, recovery, and server migration
with separate-UID traffic and destination receipts.

## Acceptance criteria

- Each selected transport completes DNS, bounded download and upload, and a sustained session through the production app TUN.
- P1 uses an actual nginx TLS frontend. XHTTP over REALITY retains its separate identity.
- Direct LAN scenarios preserve the no-bypass boundary after peer loss and recovery. A selected TCP reserve restores traffic after a UDP transport fails.
- Separate local USB scenarios verify full-TUN payloads and native protocol wire behavior when there is no common LAN. They do not claim direct UDP or underlying-network bypass acceptance.
- Migration reaches the replacement endpoint and records its independent receipt before the old endpoint can be retired.
- Wrong identity, bad trust, failed replacement, incomplete payload and missing receipts fail the selected scenario.
- Exact source, native artifacts, executed tests and cleanup are verified. Physical sleep/wake is checked on the explicitly authorized device. Wi-Fi/cellular handover, filtered networks and public providers remain outside this local acceptance scope.

- The USB carrier preserves concurrent native UDP flows with versioned IDs and bounded, authenticated host sockets.

## Ownership

- Android transport acceptance owns app/src/androidTest/kotlin/com/poyka/ripdpi/e2e transport tests and separate-UID probes; scripts/fixtures/xray-provider-peer; scripts/ci/run-android-local-acceptance.py; test-lab/acceptance Android adapters and their tests; this task and change.
- AWG engine owner controls production Rust/Kotlin AWG, schema-v2 bundle mirrors and independent AWG peer version. This lane consumes its contract.
- Xray security owner controls libXray compiler pins, packaging and managed artifacts. This lane consumes verified artifacts.
- Coordinator owns scripts/ci/android_migration_fixture.py, scripts/tests/test_android_migration_fixture.py, scripts/ci/android_udp_bridge.py and scripts/tests/test_android_udp_bridge.py in its isolated worktree. This lane owns their runner integration and Android consumer.
- Coordinator integrates branches and owns shared workflow changes. No worker commits before independent review.
