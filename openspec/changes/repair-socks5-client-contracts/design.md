## Context

LOC-09 found differences between the TUN client, synchronous proxy upstream, shared DNS client, reqwest DNS path, and Kotlin clients. Existing source and loopback reproductions identify six findings and two policy questions. Task PRX-1794125331192374 owns this repair.

## Goals / Non-Goals

- Repair invalid replies, control lifetime, total setup timeout, proxy IPv6 authority, relay endpoint handling, and credential validation.
- Resolve strict owned-listener authentication and conflicting per-endpoint credentials.
- Preserve valid wire formats, error codes, non-root operation, socket protection, and shutdown.
- Do not add dependencies, generic multi-hop UDP, GSSAPI, BIND, DoQ over SOCKS, schema migration, or a new DNS policy surface.

## Decisions

- Keep each caller's existing ownership and synchronous or asynchronous I/O model. Add only checks and state needed for the repair.
- Validate reply fixed fields before address parsing or payload use. Share existing codecs only where their contracts match.
- Monitor TUN control readiness and check its terminal state before UDP send and delivery. Keep a terminal invalid state across session clones; do not add a detached watcher.
- Start one monotonic upstream TCP setup deadline before socket connection. Use remaining time for every subsequent handshake read and write; retain active-socket shutdown registration and None timeout semantics.
- Format IPv6 proxy authorities without changing the raw host stored in DNS transport.
- For domain UDP relay endpoints, TUN and Kotlin accept localhost, localhost., and numeric IP literals without DNS. Other TUN names fail closed with an explicit error. Diagnostics uses its existing bounded resolver. The unused generic numeric-only helper stays numeric-only. Do not add system-DNS lookup or a public resolver seam merely to expand scope.
- Normalize unspecified relay addresses to the control peer and reject relay port zero. Preserve valid small domain datagrams instead of applying an IPv4 minimum size.
- Advertise only the configured auth method for DNS and diagnostics; validate credential byte lengths before auth output.
- Reject conflicting optional credential pairs for one upstream endpoint at startup instead of changing route identity or choosing a pair by order.

## Contracts and ownership

- Coordinator owns task/spec/docs, Kotlin engine API and service probe changes, integration, and push.
- Tunnel worker owns ripdpi-tunnel-core.
- Shared/DNS worker owns ripdpi-socks5-core, ripdpi-dns-resolver, and ripdpi-diagnostics-transport.
- Upstream worker owns ripdpi-proxy-runtime and ripdpi-proxy-runtime-adapter.
- Each writer has a separate worktree. The coordinator alone owns serialized task/spec files, Cargo.lock, manifests, API snapshots, baselines, locale sets, and golden fixtures. No dependency, baseline, golden, or locale changes are planned.
- Every finding remains a separate atomic commit. Workers provide per-finding patches before independent review; the coordinator assembles commits after review.

## Risks / Trade-offs

- EOF and UDP readiness can race. Check terminal control state both before send and before delivery, with loopback FIN/RST and queued-reply tests.
- Strict authentication rejects previously permitted downgrades. This matches credentials issued for owned listeners and preserves explicit NoAuth.
- Arbitrary TUN relay names remain unsupported without an explicit resolver policy. Test and document this boundary rather than silently using system DNS.
- Shared client changes can affect diagnostics. Run both owning crates' relevant tests and existing wire regressions.

## Migration Plan

No persisted or wire schema changes. Integrate reviewed commits after affected tests, source gates, and build/type checks. Run just ci-gates on the combined tree. Roll back a repair with a normal revert if its regression criteria fail; do not weaken gates or reset shared history. Device and external-server acceptance remain separate evidence categories.
