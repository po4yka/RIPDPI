## Context

Active probes use blocking TCP/TLS and use an async SOCKS5 negotiation inside a
short-lived runtime. The scan carries an absolute deadline. Each probe owns its
socket until the end of the network operation.

## Goals and scope

- Propagate administrative stops and keep only complete observations.
- Reuse the candidate connector and keep the configured endpoint identity.
- Keep existing trust roots, VPN protection, and scheduled probe scope.

## Decisions

Bound waits with the existing scan deadline and a cancellation check. Use a
scoped socket watchdog for a blocking read or write. A dropped DNS response
does not cancel an OS call already in progress. The resolver's existing permit
limit bounds those calls.

Use typed TLS errors and report an alert without inferring who sent it. Omit
the extra IN_PATH system lookup. Measure outbound IPv4 TTL only and label its
socket leg. Use elapsed opening time for composite timers.

## Contracts and ownership

- WSS lane owns WSS, its watchdog, and Telegram result projection.
- TLS lane owns the TLS capture/classification, the version-split consumer,
  and the direct-mode policy evidence. It updates HTTPS detail selection
  before integration.
- Web lane owns the Web probe cancellation and system DNS handling.
- Integration lane owns shared TCP, resolver, SOCKS transport control, TTL,
  and timing. Only the integration lane changes TTL and timing hunks after
  merging the TLS and WSS contracts.
- Integration lane owns the spec, shared fixtures, and API snapshots.

## Risks and verification

Use loopback TLS and scripted SOCKS peers for cancellation, fallback,
trust, and wire behavior. Check report facts in Rust and Kotlin. Physical
Android coverage is a separate acceptance step.

## Migration

New reports use the corrected detail names. The report readers handle absent
typed values as unknown. No change to proxy credentials or TLS trust storage.
