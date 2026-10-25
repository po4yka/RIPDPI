## Context

Task TRN-1792776081374051 adds the profile described in proposal.md. The current native core wraps boringtun with an authenticated AWG envelope. The exact upstream reference is Go commit b5928efb6ca19f0153958460c3d141f04abc5c2e. This change does not certify that source for production deployment.

## Goals / Non-Goals

- Goal: complete import-to-native support for header ranges and generated signature packets.
- Non-goals: change Noise, add a production Go runtime, enable other AWG 3 options or change physical padding acceptance.

## Decisions

- Use `wire_profile=awg-3.1-ranges-v1` in delivery and `wireProfile` in the native contract. Headers are canonical decimal strings: a singleton or `low-high` with low strictly less than high. All intervals are disjoint.
- Compile signature programs once. Accept only `<b 0x...>`, `<r N>`, `<rd N>`, `<rc N>` and `<t>`, with exact whitespace and lowercase even hex. Limit text to 4096 characters, tags to 64 and generated size to 1..1280 bytes. Generate dynamic values for every initiation.
- Select a header once when encoding. Match intervals when decoding; calculate MACs over the received or selected bytes. Keep the existing cookie translation and safety guard.
- The fingerprint preimage adds `wire_profile=awg-3.1-ranges-v1&` before the existing ordered parameter fields.
- Unsupported options fail explicitly. Old numeric headers and bare-hex signatures are not accepted as the new contract.

## Contracts and ownership

- Client lane owns native warp core, AWG fixtures, Kotlin data/engine/service/editor callers and related tests.
- Integration lane owns producer schema, emitter, canonical fingerprints and goldens. Client mirrors are copied only after producer review.
- Xray verification is a separate lane. The common task board is regenerated at integration.

## Risks / Trade-offs

- Random range headers affect handshake MACs and cookie replies. Unit authentication tests and a real pinned upstream peer cover these paths.
- User-controlled templates can allocate memory. Admission checks size and tag counts before allocation.
- WARP settings use new protobuf string fields 414–417, the wire profile in 418, and I1–I5 in 419–423. Numeric fields 152–155 are reserved. Old active settings retain their unknown bytes and fail profile admission until explicit reconfiguration. JSON, UI state, presets, and native projection preserve the complete new field set.
- Public and stored headers change type. Every caller changes together; stale external profiles require refresh. No compatibility parser is added.
- Native host evidence does not establish physical Android acceptance. Device evidence remains required and open.

## Migration Plan

Update all client interfaces and producer contract in coordinated commits. Keep S3/S4 zero and deployment eligibility separate. Roll back the complete coordinated change rather than mixing scalar and range contracts.

Run the warp-core tests and Clippy through build-gate with --locked, the real upstream interop runner, affected Gradle unit tests, Android compilation, staticAnalysis and task-check. Hosted CI and physical acceptance are recorded separately.
