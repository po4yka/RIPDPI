---
id: DGN-1795014101783145
title: Isolate QUIC version diagnostics from residual flow state
kind: feature
status: backlog
area: diagnostics
priority: high
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: quic-version-state-controls
created: 2026-11-18
updated: 2026-11-18
---

## Goal

Existing DIA-07 explicitly excludes QUIC v2 and provides Initial-response evidence, not application reachability; existing version-negotiation decoys are not a valid QUIC v2 comparator. A controlled version matrix and explicit flow-state isolation complement these owners.

## Acceptance criteria

- Construct valid integrity-protected QUIC v1 and v2 Initials with matching SNI/control intent and independent known-answer fixtures, using the correct version-specific salts and packet semantics. Unknown-version decoys MUST NOT count as a v2 test.
- Use fresh sockets and disjoint local UDP ports for independent matrix cells, record execution order and isolate any deliberate same-flow residual experiment as opt-in. Local port separation does not prove distinct external NAT tuples.
- Compare control/test names against an authorized known-capable endpoint with bounded repetitions, bytes and cancellation. Require a valid control for inference and keep network and transport scope attached.
- Separate Initial response, handshake, and verified application payload evidence. A version difference is a diagnostic observation and MUST NOT automatically change production relay settings.

## Ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

## Sources and scope

https://petsymposium.org/foci/2026/foci-2026-0010.php

The paper reports version-sensitive and residual flow filtering in March-April 2026 at three vantage points. Its observed timeout is a historical measurement, not a permanent provider guarantee. See also https://github.com/UPB-SysSec/RussiaQuicResults .

No implementation, device probe, credential use or deployment is performed by this planning change.

Initial protection uses public derivation inputs and does not authenticate the endpoint. Endpoint authentication requires a successful TLS handshake and certificate verification; Initial-only observations must never receive that status.
