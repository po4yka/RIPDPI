---
id: ENG-1794638006879351
title: Repair DPI-25 QUIC Initial contracts
kind: bug
status: doing
area: engine
priority: high
owner: Network engine team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi25-quic-initial-contracts
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Repair the five DPI-25 audit defects: QUIC seed requirements, CID/PN/CRYPTO consistency, UDP fake TTL propagation, count bounds, and encoded datagram bounds. Keep the original datagram and relay fallback contracts.

## Acceptance criteria

Both profiles and QUIC versions pass independent local ALPN and transport parameter checks. Each decoy has fresh CIDs and a matching SCID transport parameter. The selected UDP group keeps the user TTL. Each chain emits at most 32 DPI-25 decoys. Each encoded decoy fits the 1200-byte budget for unknown PMTU. Owning tests, static analysis, CI source gates and independent review pass before main integration.

## Ownership

- Seed worker in fix/dpi25-seed: ripdpi-packets/src/quic/{fake_initial.rs,probe.rs,mod.rs} and new owning tests. Keep frozen builders and hash tests unchanged.
- TTL worker in fix/dpi25-ttl: proxy-config convert/protocol.rs and tests_conversion.rs; runtime adapter TTL resolution; desync sequencing.rs, one plan_udp.rs call argument and TTL tests in tests/plan_udp.rs. Release shared desync files before integrator edits them.
- Count worker in fix/dpi25-count: config constants.rs; proxy-config convert/chain/{udp.rs,validation.rs} and local tests; Kotlin StrategyChainValidation.kt and StrategyChainValidationTest.kt. Do not edit tests_conversion.rs or desync planner files.
- Integrator in fix/dpi25-complete: seed wiring, fresh independent connections, planner count preflight, size budget, generated API snapshots, task/OpenSpec records and combined validation. It edits shared desync files after TTL patch delivery.
- Review agents are read-only. No dependency, locale, architecture baseline or golden fixture changes.

## Scope decisions

- Each alternating profile is an independent synthetic connection: fresh DCID/SCID, PN zero, complete matching CRYPTO transcript. Exact browser-version parity is not claimed without a capture.
- Positive count emits max(count,2); zero disables. Per-step and aggregate DPI-25 limits are 32. Invalid internal models return original-only before allocations.
- Unknown-PMTU decoys fit 1200 encoded bytes. PADDING fills that fixed size instead of increasing length by index; oversized seeds are omitted. Local input size does not prove remote PMTU.
- Preserve fixed/adaptive TTL settings and consume the existing resolver result. Do not add speculative UDP learning from relay outcomes.
- Physical devices, root activation and external active probes remain outside local validation.
