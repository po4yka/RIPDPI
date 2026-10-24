## Context

The real proxy on source `47f9c8f2` completes a split-header 1 MiB upload with
autolearn disabled. With it enabled, the origin receives no body and reaches its
deadline. Direct native Hysteria completes the same request, including a pause
between headers and body. The shared first-exchange wait precedes duplex copying.

## Goals / Non-Goals

- Goal: Complete request continuation while preserving first-response evidence, bounded memory and byte ownership.
- Goal: Prevent duplicate non-idempotent requests after unretained continuation starts.
- Non-goal: Disable autolearn, change detector or socket timeouts, or alter Hysteria, HTTP fixtures, public schemas or dependencies.

## Decisions

- Wait for both client continuation and the first upstream response. A continuation result hands off without consuming those client bytes, so the existing outbound copy remains their single owner.
- Carry bounded pending response context into the prepared relay. End replay and reconnect eligibility before this handoff; classification still runs but cannot resend this flow.
- Use one initial-response observer in both portable and io_uring inbound copy. Preserve the existing TLS collector, deadlines, accepted byte order, autolearn and terminal failure policy.
- Let the existing outbound copy perform continuation, including desync steps, session progress and backpressure. Do not add a blocking write loop inside the response poller or retain an entire body.
- Preserve shutdown, cancellation and half-close through the current copy lifecycle. Tests must cover an early response while a large send is backpressured.
- Pending context retains bounded already-read response bytes, TLS boundaries, timeout and deadline state, TTL and the original request, route, host and strategy context. Poll both descriptors during partial TLS collection; handle readiness, EOF and cancellation without consuming client continuation.
- Seal replay eligibility at handoff for every route, including SynData, TFO and alternate upstreams. Keep one outbound owner for desync, Lua, progress and backpressure.
- Use explicit pending, accepted and rejected observation outcomes. Generic relay-result accounting must not promote a rejected response or duplicate accepted learning.
- Finish pending observation in each inbound worker before it acquires the first registered copy buffer. Preserve io_uring completion and fallback ownership; errors and cancellation shut down the opposite half before join, while normal EOF keeps half-close semantics.
- Explicit owner cancellation shuts down the write half and then the read half independently. A real macOS socket probe shows that combined shutdown after peer FIN can return ENOTCONN without releasing a blocked writer; write-half shutdown releases it. Apply this sequence to the active socket registry and equivalent explicit abort sites touched by this relay change. Keep normal FIN handling, the existing 3-second graceful drain and 1-second forced drain unchanged.

## Contracts and ownership

- Protocol worker owns native/rust/crates/ripdpi-proxy-runtime/src/runtime/relay first-exchange, first-outbound, response, session, execution and stream-copy files, plus their focused runtime tests.
- Protocol worker also owns native/rust/crates/ripdpi-proxy-runtime/src/runtime/state/listener.rs for the owner-cancellation correction. No desync writer, partial-send contract or cross-crate cancellation interface change is needed.
- The coordinator owns this task and OpenSpec records. The security worker reviews byte ownership, replay safety and lifecycle independently before implementation and commit.
- No Kotlin, JNI, protobuf, wire, persistence, lockfile, version catalog or credential change is planned. No compatibility path is added.
- Android acceptance worker alone owns physical-device operations. The rebuilt Hysteria upload and automatic-recovery scenario remains a required integration gate.
- Android acceptance worker owns the required Linux CI command; the coordinator integrates it. Existing Linux workspace tests do not enable the optional io-uring feature. The explicit feature-enabled runtime gate must prove the io_uring path ran without capability fallback.

## Risks / Trade-offs

- A second reader could consume or duplicate bytes: continuation handoff must not consume them, and response buffers have one owner.
- An alternate route could repeat a POST: replay eligibility ends at handoff and regression tests require zero alternate-route requests after failure.
- A blocked upload could prevent an early response: keep both directions active under existing bounded backpressure.
- Portable and io_uring behavior could diverge: share the observer and validate both paths through the existing local and Linux gates.
- Optional no-response waits can remain open by contract: cancellation and owner shutdown must still terminate them without leaked tasks or sockets.

## Migration Plan

Capture the failing real-proxy case as a regression, implement the handoff and
shared observer, then run the complete affected crate suites, workspace Clippy,
format and architecture checks. Run existing Linux coverage for io_uring and a
fresh genuine Android build with the authorized Hysteria transaction scenario.
No data migration is needed. Rollback reverts this source fix; it does not change
stored profiles, devices or deployment state.
