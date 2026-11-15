## Context

DIA-07 measures an isolated first flight through Direct or SOCKS5. It sends QUIC v1 and has no authenticated handshake state.

## Goals / Non-Goals

- Goal: Produce bounded, path-scoped evidence for valid first responses and distinguish lack of evidence from a network block.
- Non-goal: Add a handshake, QUIC v2, a Retry token round trip, or HTTP/3 to DIA-07.

## Decisions

- A dedicated packet validator checks the response frames and labels the first valid correlated reply. Existing runtime and fingerprint callers retain their separate checks.
- The transport uses complete datagrams and the existing deadline and cancellation facilities. A new exchange uses fresh IDs. Plain Direct can send one new packet with the same CRYPTO and a new packet number.
- The runner emits distinct outcome and typed observation states. The UI uses those states. Scheduled evidence does not update direct capability or imply a blocking recommendation.
- The app normalizes valid internationalized DNS names. The native boundary rejects invalid names and ports without I/O.

## Contracts and ownership

- Packet writer owns the scheduled response validator, SNI validation and packet tests.
- Transport writer owns UDP, route experiments, SOCKS control and transport regressions.
- Kotlin writer owns report projections, direct capability policy, target preparation and their tests.
- Integrator owns Rust outcome and observation contracts, request limits, scheduler, running the combined tests, and integration. Lockfiles, dependencies and baselines have no assigned changes.

## Risks / Trade-offs

- UDP and Initial integrity help correlate replies. The wire does not authenticate the endpoint.
- Test valid and invalid replies independently. Use loopback for first-flight, timeout, cancellation and association behavior.

## Migration Plan

Changes apply when the app and native engine are built together. Diagnostic history retains its recorded outcome and has no authority to update direct capabilities from this probe. Revert the paired application changes together if necessary.
