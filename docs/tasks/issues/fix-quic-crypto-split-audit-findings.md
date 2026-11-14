---
id: ENG-1794638026548237
title: Fix QUIC CRYPTO split audit findings
kind: bug
status: review
area: engine
priority: high
owner: Engine maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: quic-crypto-split-audit-fixes
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Correct DPI-19 findings F1 through F5. Preserve the original UDP payload and reject ambiguous or unsupported split inputs.

## Acceptance criteria

- Oversized preludes and local size/buffer send errors cannot suppress a usable original payload when the normal TTL is known. Fallback evidence cannot claim Applied; network/ICMP flow errors remain terminal.
- The selected UDP group keeps the configured fixed fake TTL.
- Conflicting CRYPTO overlaps are rejected; matching overlaps preserve all bytes.
- The CRYPTO split count and aggregate count are at most 64 in Kotlin, Rust parsing, and the typed planner.
- Standalone client Initial datagrams shorter than 1200 bytes do not activate shaping; valid coalesced tails remain supported.
- Owning tests, independent wire checks, review, and required integration gates pass. Each finding has its own fix commit.

## Parallel ownership

- Parent, /tmp/ripdpi-dpi19-fixes: F1 runtime executor, receipt tests, plan_udp/quic.rs and existing plan_udp tests; F5 normalized input guard; architecture documentation; task/OpenSpec files; integration.
- overlap_guard, /tmp/ripdpi-dpi19-overlap: F3 packets/src/quic/frames.rs, tests.rs, datagram_tests.rs.
- count_budget, /tmp/ripdpi-dpi19-count: F4 config constants and API snapshot, proxy-config chain/udp.rs and validation.rs and tests_conversion.rs; Kotlin chain validation and core/data/model/src/test/kotlin/com/poyka/ripdpi/data/StrategyChainValidationTest.kt; desync plan_udp.rs and separate quota test module registration.
- TTL worker, /tmp/ripdpi-dpi19-ttl: F2 proxy-config convert/protocol.rs and dedicated projection tests outside tests_conversion.rs.
- Serialized lanes: count_budget alone owns API snapshot changes. Parent alone owns task/generated board and final combined gates. No Cargo.lock, dependency, locale or golden updates are planned.

Final integration and evidence owner: /Users/npochaev/GitHub/RIPDPI-dpi19-integration-20261114. The paths above record the original parallel ownership. See openspec/changes/quic-crypto-split-audit-fixes/verification.md for observed checks and remaining hosted CI.

## Scope

The method remains a low-TTL decoy prelude. It does not promise a stateful replacement for a QUIC endpoint. No new protocol, relay, root operation, deployment, or external probe is included.
