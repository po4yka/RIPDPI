# Design

## Context

Task DGN-1794732408586377 repairs eight confirmed DIA-04 audit findings. Preserve existing DNS query binding and transport security. Local tests do not prove external compatibility or Android routing.

## Decisions

- Canonicalize the catalog default resolver and deduplicate known service/provider identity before quorum. Shared infrastructure addresses alone do not prove shared provider ownership.
- Check unexpected sinkhole addresses before public overlap shortcuts. Keep matching private answers and ordinary CDN divergence distinguishable.
- Keep response metadata and semantic facts tied to the same selected or explicitly observed-first attempt. Preserve negative observations without claiming selected consensus.
- Add explicit bounded EDNS only to diagnostic queries so the generic proxy restore contract remains intact. Preserve numeric EDE and discard extra text.
- Use full existing ECH parsing for persisted admission. The real fallback consumer reads only a fresh cached or restored configuration. A stale or missing cache uses bundled bytes; only the protected refresh producer performs network I/O. This preserves the IN_PATH transport and avoids network work under the cache lock.
- Use the existing protected socket machinery and resolver socket factory hooks. Do not add unsafe code or a blocking Tokio connector.
- Validate DNS compression by bounds and real cycles with a bounded traversal; do not mistake a short safety budget for malformed wire data.

## Ownership and contracts

The portfolio record defines writers and the serialized encrypted.rs/lib.rs handoff. The primary writer alone owns task and OpenSpec files. No Kotlin DTO, JNI, protobuf, dependency, locale, baseline or golden migration is planned. ProbeDetail additions remain compatible optional facts. Public Rust API snapshots are regenerated only if an intentional API change requires it.

## Validation

Reuse the observed audit reproduction when the defective source is unchanged; otherwise observe a new failing regression before the source change. Keep each regression with its fix in a separate commit after read-only review. Use Rust 1.100.0 and Cargo --locked. Run owning package suites and all-target clippy, formatting, metadata, task checks and just ci-gates on the combined tree. Protect failure tests use local callbacks; wire tests use loopback and independently specified bytes. No active external or physical-device probe is authorized by this repair request.

## Rollback and limits

Each fix is independently revertible. New EDE evidence is not DNSSEC validation. Do not expand support to live AAAA, generic SVCB service targets, negative quorum or new encrypted transports. Investigate audit hypotheses only if an implemented fix exposes a concrete supported-path defect. Android and external acceptance remain separate evidence categories.
