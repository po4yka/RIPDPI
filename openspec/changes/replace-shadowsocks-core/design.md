## Context

The local codec owns encryption and wire parsing. Its fixture uses the same codec. The adapter uses detached pumps, custom address encoding, a bounded UDP replay window, and NetworkTimeProvider. See proposal.md for the task and scope.

## Goals / Non-Goals

- Goal: use shadowsocks 1.25.0 protocol code with the current six-method capability matrix and AES SS2022 EIH support.
- Non-goal: add legacy UDP, stream ciphers, new relay kinds, UI options, or a backend service.

## Decisions

- Pin the MIT upstream crate and retain its source and license under native/rust/vendor. Disable default DNS features and stream ciphers; enable AEAD, AEAD2022, and replay detection.
- Apply a recorded integration patch: per-context time, authenticated UDP timestamps, initialized buffers and safe integer reads, cache ownership, credential redaction, cancellation-safe AEAD writes and shared read/write wakeups, Android BLAKE3 exports, and the upstream service packet-window filter. Upstream master also lacks the clock hook and retains unsafe UDP integer loads.
- Keep ripdpi-shadowsocks as the owner of method validation, time integration, and bounded association policy. Delete its private cipher, TCP, UDP, and URI codecs. It is not a compatibility facade for those APIs.
- Use ProxyClientStream over a boxed caller-owned stream. Explicitly send the initial address header before return; retain server-first traffic and half-close without duplex pumps.
- Wrap the existing protected UDP socket with ProxySocket. Check client session identity and the upstream replay filter before calibration or delivery. Preserve the current bounded server-session policy.
- Use upstream server APIs for local fixtures. Add independent known-wire and negative inputs where client/server round trips alone cannot catch a regression.
- Keep legacy UDP rejected across Rust and Kotlin. The existing public capability matrix stays unchanged.

## Contracts and ownership

- Primary implementer owns ripdpi-shadowsocks, relay-tls-transports, local-network-fixture, affected tests, vendor source, Cargo manifests and locks, and documentation.
- Read-only agents inspect the upstream API and review the final behavior and cancellation rules.
- JNI fields, stored profiles, and Kotlin capability projection do not change. One Kotlin validator aligns editing, import, and activation with upstream key rules. The private Rust codec API is removed.

## Risks / Trade-offs

- Vendor maintenance: record the release checksum, source commit, MIT license, and exact patch; do not reimplement protocol algorithms in the adapter.
- Replay checks are partly service-level upstream: retain bounded session ownership and use its packet-window algorithm. ProxySocket alone is not a complete anti-replay policy.
- Upstream Debug includes secrets: never format its config or socket objects into errors or logs.
- Native dependencies can affect Android: run host tests, dependency checks and a targeted Android compile; report device and hosted CI evidence separately.

## Migration Plan

Replace the codec and all call sites in one coherent change. No storage migration is required. Validate the owning crates and relay integration, cargo deny, formatting/lints, just ci-gates, and architecture health. Use independent general, unsafe, and async review. Rebase onto current origin/main before fast-forward integration and push. Rollback is a normal revert of the complete change.
