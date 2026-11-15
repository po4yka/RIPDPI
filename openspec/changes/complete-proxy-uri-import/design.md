## Context

The audit report is /Users/npochaev/Downloads/RIPDPI_network_audits/IMP-01.md. Current code has a shared URI codec, typed relay mapping and Room transaction owner. TUIC runtime fields exist but URI input is opaque. Mieru has no advanced runtime implementation.

## Goals / Non-Goals

- Goal: Fix F1-F7, activate existing native TUIC capabilities, and apply pinned advanced Mieru TCP settings end to end.
- Non-goal: New UDP carrier, unsupported TUIC transport modes, root, remote servers, new dependencies or speculative audit hypotheses.

## Decisions

- Reuse the shared query and RFC percent decoders. Decode names once and refuse ambiguous scalar collisions. Preserve intentional scheme limits.
- Put imported group membership in the existing Room mutation, with stable profile identity and display name. Do not create an application journal. The atomic-profile-persistence specification still applies.
- Add a typed Tuic profile and update all exhaustive callers. Existing native TUIC supports UUID/password, SNI, ALPN, congestion control and datagram UDP. Reject unavailable zero-RTT, insecure TLS, disabled SNI and UDP stream modes. Refuse URI export when a custom root cannot be represented.
- Use typed optional Mieru settings that retain protobuf presence, separate from resolved effective runtime defaults. Support pinned v3.36.0 field 6 handshakeMode and field 7 TrafficPattern, including seed/unlockAll, TCPFragment, Nonce, Padding and LowEntropy. Use a fixed non-identifying RIPDPI app label for host-dependent implicit defaults; never collect device or network identifiers. Explicit-seed values follow pinned SHA256 FixedInt labels.
- Implement nonce rewrite before user hint stamping, bounded padding, owned-writer TCP fragmentation, low-entropy protocol IDs 10/11 with authenticated bounded decoding, and no-wait reply validation in an owned inbound pump. Keep the absent-pattern legacy path unchanged. Add cancel-safety documentation for each changed async path.
- The authoritative Mieru source is enfein/mieru v3.36.0 commit 155ebbd60f86e472586a60d7ffe58ec8f8682cb1. Use actual pinned Go vectors and local peer exchange, not mutually matching Rust encoder/decoder as the sole oracle.

## Contracts and ownership

- Root owns portfolio/OpenSpec, combined validation, integration and push. The task body records the serialized lanes.
- Codec writer owns F1/F4/F5/F7 and TUIC, then yields shared profile/admission/mapping files to Mieru. Persistence writer owns F2/F3/F6 and yields the share mapper after F3.
- Mieru writer owns advanced Kotlin types, native mieru files, relay record fields and subsequent native/service/config plumbing. Native config constants and flat strict fields have a single writer. Advance both Kotlin and Rust schema versions together when new fields require it.
- Store fields are additive with defaults. Existing imported groups remain readable; previously overwritten credentials cannot be reconstructed. No AppSettings protobuf or dependency change is planned.

## Risks / Trade-offs

- Query normalization can reveal encoded duplicates: reject them before a partial profile exists.
- Transaction changes can affect cancellation and selection: use real Room failure, reopen and authority tests.
- Mieru changes affect authenticated framing: validate masks/lengths before allocation and use pinned vectors, loopback peers and async review.
- Disk capacity is limited: use unit-test native skip for JVM tests and locked host Cargo gates through repository build-gate. Do not claim unit tests as Android/native acceptance.

## Migration Plan

Retain existing data with default absent advanced settings. Keep group/profile storage additive. Refuse malformed new inputs instead of guessing settings. A rollback to an older binary can reject new native config versions; stored advanced settings must not be silently rewritten by old code. Validate current serializer compatibility and document any actual version restriction. Rebase against origin/main, repeat changed-input gates, integrate by fast-forward and push only the reviewed commits. Preserve all unrelated work.
