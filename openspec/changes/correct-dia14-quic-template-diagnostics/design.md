## Context

DIA-14 sends three QUIC v1 Initial templates and one reserved-version probe. The suite has no TLS handshake state. DIA-07 connectivity and DIA-03 HTTP/3 have separate entry points.

## Goals / Non-Goals

- Goal: Preserve bounded evidence for comparable first flights on a selected peer.
- Non-goal: Add full QUIC/TLS, HTTP/3, browser clones, or an alternate Android network route.

## Decisions

- Treat template asymmetry as a difference in replies. Only independently supported suite categories can contribute a DPI verdict.
- Resolve each target once. Keep fresh connection IDs for each attempt and retain the outcome of at most two attempts for each template. Bound the suite's concurrency, target count, and attempt deadlines.
- Filter packets on the connected socket and continue receipt after an unrelated or invalid datagram. Preserve the count within a fixed receive limit. Apply version, CID, packet protection, and frame checks to candidate packets. Read compatible coalesced packet boundaries.
- Use typed outcomes for observed wire replies, socket or setup errors, and missing evidence. Keep packet bytes and CID values out of the result.
- Normalize the QUIC endpoint before scheduling. Retain the endpoint's exact IPv6 address and use UDP port 443. Use the established input contract for other probes.
- Show synthetic template labels, response kinds, and observed delays. A Retry, ACK, or transport close can be useful evidence. Each is distinct from progress in the TLS handshake.

## Contracts and ownership

The Rust JNI bridge and Kotlin decoder use the same integer result mapping in one commit. The native writer owns validation. The runner writer owns socket and evidence. The target writer owns QUIC endpoint normalization. The integrator owns classification, presentation, the serialized locale set, and combined validation.

## Risks / Trade-offs

A correlated reply does not authenticate the endpoint. A shared remote IP can still reach different backends. A bounded probe can miss late responses. Report these results without a filtering claim.

## Migration Plan

Ship the Kotlin and JNI code in the same application build. Results are held in memory and require no history migration. Continue to run HTTP/3 GET in the separate HTTP/3 probe.
