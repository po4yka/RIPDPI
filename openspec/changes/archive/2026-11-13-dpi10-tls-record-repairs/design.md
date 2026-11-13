## Context

See proposal.md and the DPI-10 audit. The current record parser exposes payload spans, but the private prelude uses a single-record IR. Adaptive callers also repeat a coordinate correction. The code is synchronous and each call owns its buffers.

## Goals / Non-Goals

- Goal: correct offsets and bounded repeat execution, with byte-preserving multi-record processing.
- Goal: retain existing no-op behavior for incomplete or unsupported inputs.
- Non-goal: change public first-flight IR, configuration syntax, TLS endpoints, root behavior, or ECH cryptography.

## Decisions

- Keep adaptive resolver output in payload coordinates. Only legacy non-adaptive offsets need the caller's header adjustment.
- Limit TLS record split work to 1024 iterations per configured step. Reject a larger request before its mutation; do not silently truncate it. Keep zero and duplicate skips inside the bounded loop, since later skip positions can be useful.
- Use the existing span-aware packet layout privately in the prelude. Include complete records through the end of the first ClientHello and retain the full payload of its final record. Preserve later wire bytes exactly. Preserve source record boundaries for tlsrec.
- Build semantic metadata on contiguous synthetic payload. Keep public IR range contracts unchanged. Inputs that exceed the synthetic record representation remain a documented no-op.
- Detect visible ECH through span-aware layout in runtime activation and protocol adapter callers. This does not decrypt or log ECH.
- Use captured complete TLS fixtures and independent expected offsets in tests. Require applied counts and byte equality. Keep separate commits for F1, F2, F3 and G1.

## Contracts and ownership

- Rust crates: ripdpi-desync, ripdpi-desync-runtime, ripdpi-proxy-runtime-desync-adapter. No Kotlin module edits are planned.
- No dependency, public API, JNI, protobuf, persisted field, schema version, fixture or baseline edits.
- Worker ownership and the serialized tls_prelude.rs integration order are in the portfolio task.
- Local gates: owning crate tests with --locked, formatting, targeted Clippy, just ci-gates, architecture health, locked Cargo metadata and task validation.
- Independent review precedes implementation commits. Local loopback acceptance uses the production adapter and an independent TLS peer; no external or physical target is required.

## Risks / Trade-offs

- Coalesced bytes can be lost if the final ClientHello record is cut at the handshake boundary. Preserve its full payload and keep following records as wire tail.
- Invalid or partial inputs can use tolerant parser metadata. Validate complete contributing record envelopes before changing real traffic.
- The work limit changes excessive configurations to the existing plain fallback. Document the limit and test both sides of it without a load probe.
- Shared source edits can conflict. Use separate worktrees and serialize integration.

## Migration Plan

No data migration or new configuration field is required. Integrate each reviewed change as its own commit, run the combined gates, then fast-forward main and push. A normal revert can roll back an implementation commit; no shared history rewrite is required. Device and external compatibility remain separate from local evidence.
