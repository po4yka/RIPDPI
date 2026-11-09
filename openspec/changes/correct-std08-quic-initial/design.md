## Context

The existing packet helper is stateless. Active diagnostics reuse fake TLS templates. Kotlin validates only response headers. The seven audit findings have local reproductions.

## Goals / Non-Goals

- Goal: repair F1 through F7 with one isolated fix commit each and independent wire evidence.
- Non-goal: full QUIC, TLS or HTTP/3 sessions, external probes, physical-device acceptance, new production dependencies, or changes to fake templates.

## Decisions

- Move active ClientHello generation out of response validation. Keep the current Chrome builder as a wrapper and add a concrete active profile selector for the Android adapter. Rebuild active lengths/extensions, drop SID and duplicate padding, set h3 ALPN, bind transport parameters to fresh SCID and use fresh valid key shares.
- Add a native byte-array JNI validator to the existing bindings. Reuse packet integrity validation and a shared reserved-VN structural path; remove Kotlin header-only validation.
- Add token bytes before Length and include them in padding/header accounting.
- Skip bounded, checked ACK/ACK_ECN ranges while collecting CRYPTO.
- Iterate compatible Initial packets in one datagram. Defragment logical CRYPTO separately from wire marker positions. Keep public layout shape if it can represent positions correctly. Reject conflicting Initial CID/version contexts; skip other valid coalesced packet types without decrypting them.
- Enforce declared ClientHello and nested vector completeness only at the QUIC full-result boundary. Preserve the generic tolerant TLS template parser.
- Close sockets from cancellation handlers. Bound resolution and exchange with one deadline. Reuse existing resolver infrastructure if available; otherwise use a bounded resolver worker so caller cancellation does not wait for non-interruptible InetAddress lookup on API27/28. No OS-level cancellation promise for that fallback.

## Contracts and ownership

Portfolio DGN-1794211297803022 records three isolated writer lanes. Probe writer owns active generation and adapter build paths until handoff. Packet writer owns parse/frames/build, seed parsing and tamper. Consumer writer owns validation, JNI and Kotlin cancellation. Integrator owns planning, generated API snapshots and gates. mod.rs additions are serialized at integration. No Cargo.lock, schema, locale, golden or baseline edit is planned. If an existing test-only oracle can run in its consumer crate, no dependency changes are needed.

JNI adds jniValidate(request: ByteArray, response: ByteArray, versionNegotiation: Boolean): Boolean, descriptor ([B[BZ)Z. It fails closed and preserves FFI panic containment. No stored data migration. Full results require complete input; callers must handle None as before. Initial response success still does not authenticate a peer.

## Risks / Trade-offs

- Strict parsing may expose invalid fake templates: preserve templates and fix only inappropriate active-template use; investigate each affected caller.
- Coalesced marker coordinates must remain wire-relative. Verify independent offsets and reject inconsistent contexts.
- Cancellation races must close sockets on every path. Test real loopback cancellation and bounded resolver deadline behavior.
- The current main deleted boring-sys build scripts. Verify that baseline before attributing any native build failure to this change; do not silently restore unrelated user work.

## Migration Plan

No migration. Revert a fix commit to roll back its isolated behavior. Run targeted Rust --locked regressions and strict rustls oracle, affected native consumers, core diagnostics unit tests, staticAnalysis where applicable, JNI signature/binding checks and an emulator pool smoke if supported. Run just ci-gates, architecture health and locked Cargo metadata on the combined tree before authorized fast-forward integration. Publish each fix commit on main and push origin/main only after observed local validation or an explicitly reported unrelated blocker. CI and device evidence remain distinct from unit tests.
