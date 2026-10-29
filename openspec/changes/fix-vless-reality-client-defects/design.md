## Context

The audit reproduced buffered Vision writes and a Direct transition inside an inner TLS record. It also traced lossy import, transport aliases, automatic XHTTP mode, and placeholder admission version to their owning modules.

## Goals / Non-Goals

- Goal: Correct the six defects with regression evidence and six separate implementation commits on main.
- Non-goal: Implement ML-DSA, VLESS Encryption, packet-up, gRPC, or new profile fields.

## Decisions

- Reject options that cannot be represented, rather than drop them. Keep existing import result types and redact values in errors.
- Resolve method before network once, and use the selected transport for validation and profile construction.
- Resolve XHTTP auto with security context. REALITY uses stream-one; automatic TLS selection that requires unimplemented packet-up fails explicitly.
- Keep Vision's buffered AsyncWrite contract; XUDP must drive flush within the same write deadline.
- Track inner TLS record completion across writes before Direct, including coalesced records.
- Use the reviewed Xray v26.10.29 handshake compatibility version as an explicit named constant. This is not a claim of complete Xray feature parity. Preserve independent crypto-vector coverage with explicit input versions where needed.

## Contracts and ownership

- Kotlin core/data/runtime-state import parsers and existing tests: isolated import worker.
- Rust ripdpi-xhttp mode resolution and ripdpi-relay-core callers/tests: isolated XHTTP worker.
- Rust ripdpi-vless vision.rs: isolated Vision worker. Primary writer owns xudp.rs and REALITY version code/tests.
- Primary writer alone edits main, applies reviewed patches, updates task/spec records, commits, and pushes. Other writers never commit or push.
- No serialized dependency, baseline, locale, JNI, protobuf, or persistence contract changes.

## Risks / Trade-offs

- Stricter import rejects profiles previously accepted with lost settings; supported-profile round trips must still pass.
- Explicit stream-up remains available for supported TLS servers; auto cannot stand for an unsupported upstream mode.
- A version-gated server may require a different future compatibility reference; document the fixed reviewed reference and test both bounds.

## Migration Plan

No stored-data migration is required. Re-import rejected profiles only after removing or implementing the unsupported options. Revert an atomic fix commit for rollback.

Run affected module tests, cargo test --locked for VLESS/XHTTP and affected relay code, strict crate Clippy, staticAnalysis for cross-module changes, architecture health, locked Cargo metadata, and task/OpenSpec validation. Independent pr-reviewer and async-cancel-safety review precede the relevant commits. Hosted CI and Android/network acceptance remain distinct from local checks.
