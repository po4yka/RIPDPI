---
id: ENG-1794637912833130
title: Correct DPI-23 QUIC packet generation audit findings
kind: bug
status: done
area: engine
priority: medium
owner: DPI-23 integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi23-quic-packet-generation-fixes
created: 2026-11-14
updated: 2026-11-14
closed_at: "2026-11-14T09:44:03Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Three reviewed fixes; 1314 Rust and 87 Kotlin tests passed; independent actual-emitter TShark oracle and all source gates passed. Code commit 90728fab830c7b4fd26a3e27989fe8ff231becc9.
---

## Goal

Correct the DPI-23 audit findings in bounded QUIC decoy generation. Preserve the original datagram and synthetic packet-number gap semantics.

## Acceptance criteria

- Oversized DPI-23 counts are rejected by Kotlin and native admission. Direct model planning has a bounded per-datagram generation budget.
- Each gap series has one fresh cryptographic context. Repeated activation does not reuse a key/nonce pair for new plaintext.
- A genuine loopback test determines whether a decoy-only QUIC response can mark real traffic successful. Correct confirmed behavior while preserving generic UDP and valid QUIC responses.
- Permanent regressions, independent wire decoding, required local gates and independent review pass. Each confirmed defect has its own fix commit before main integration and push.

## Ownership

- Parent integration owns this record, all OpenSpec artifacts, generated board, combined validation, commits, main integration and push.
- F1 worker owns ripdpi-config count/budget constants, ripdpi-proxy-config UDP admission and tests, core/data/model Kotlin UDP count validation and tests, ripdpi-desync plan_udp.rs budget enforcement and existing plan_udp unit tests.
- F2 worker owns ripdpi-desync/src/plan_udp/packet_family/churn_gap.rs and new dedicated gap-series integration tests. Shared seed helpers, packetizer, adjacent tactics and F1 files are out of scope.
- H1 worker owns ripdpi-proxy-runtime UDP receive/session/feedback code and existing owning loopback tests, only after reproducing the defect.
- Each writer uses a separate worktree. Workers do not modify task/OpenSpec files, Cargo.lock, versions, proto/JNI/wire schemas, locale resources, goldens or architecture baselines. Parent alone owns combined documentation updates.

## Evidence scope

Audit source: main at 74a434befe11cff7cf17226918201d251c89510c. Acceptance is host parser/planner, production UDP adapter, loopback and independent offline wire decoding. Devices, external servers, root activation, release artifacts and deployment are outside this change.
