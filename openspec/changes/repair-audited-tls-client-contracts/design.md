# Design

Task ID: `TRN-1794158908788387`

## Context

The confirmed findings are consumer-policy and ownership faults. Shared TLS libraries already provide version negotiation, certificate validation and cryptographic operations. The repair must not replace those libraries or extend unrelated protocol scope.

## Decisions

- Treat an explicit diagnostic TLS name as transport intent. Keep plain callers explicit. Do not add a persisted or JNI field.
- Make the existing pinned DoT API pin-only, consistent with its rustls consumers. Do not add a generic trust-mode framework. Keep the unpinned default PKIX and blocklist path intact.
- Set DoT ALPN to dot at the consumer, including custom-builder hooks. Accept dot or no ALPN, and reject a different selection before DNS bytes.
- Check h2 after TLS and before Hyper in both MASQUE H2 paths and xHTTP. A narrow read-only REALITY negotiated-ALPN accessor is allowed if the wrapper needs it.
- Cancel/reset only the owned DoQ request streams with DOQ_REQUEST_CANCELLED=3; keep the shared connection alive. Review each await and Drop edge.
- Give synchronous TLS classifier I/O a short polling interval and an absolute cell/scan deadline plus scan cancellation. Keep the helper private to monitor-engine. This polling bound applies after the existing bounded protected TCP connect; it does not promise immediate cancellation of a synchronous protection callback or kernel connect. Use existing administrative result classification, not a network failure verdict.
- Reject unsupported TUIC early data before network activity. A real implementation would need shared credential/trust-bound tickets, exporter authentication and a separate replay policy; it is outside this repair. Do not add a silent fallback or a misleading active mode.

## Ownership and order

The portfolio task records the three writer lanes and serialized files. Each worker has a dedicated worktree. The integrator alone edits planning, generated API snapshots and any lockfile change. Each finding has a separate reviewed code commit. No worker merges or pushes.

## Validation

Reproduce the old behavior with real loopback peers, then reverse the assertions for the fix. Use an independent TLS peer for ALPN and actual application bytes. Test trusted wrong pin, legacy no-ALPN, concurrent DoQ timeout/cancel, partial-record/silent TLS peers and administrative cancellation. Run affected package tests with --locked, relevant Clippy/format, general and async review, then combined ci-gates and architecture/metadata checks. No public server, physical device or actual credential is required.

## Risks and limits

Pin-only keeps its intentional name/validity policy and requires proof of private-key possession. Legacy DoT without ALPN remains supported. Rejecting zero_rtt=true changes an earlier silent no-op into a visible unsupported error; stored schema is unchanged. A polling tolerance is measured by tests. Host evidence does not prove Android artifacts or external compatibility.
