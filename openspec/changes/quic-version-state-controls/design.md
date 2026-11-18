## Context

Existing DIA-07 explicitly excludes QUIC v2 and provides Initial-response evidence, not application reachability; existing version-negotiation decoys are not a valid QUIC v2 comparator. A controlled version matrix and explicit flow-state isolation complement these owners.

## Goals / Non-Goals

Goal: reproducible bounded evidence with honest capability and inference limits. Non-goals: automatic background scanning, public measurement upload, mandatory backend, country-wide guarantees or production transport changes.

## Decisions

Construct valid integrity-protected QUIC v1 and v2 Initials with matching SNI/control intent and independent known-answer fixtures, using the correct version-specific salts and packet semantics. Unknown-version decoys MUST NOT count as a v2 test.

Use fresh sockets and disjoint local UDP ports for independent matrix cells, record execution order and isolate any deliberate same-flow residual experiment as opt-in. Local port separation does not prove distinct external NAT tuples.

Compare control/test names against an authorized known-capable endpoint with bounded repetitions, bytes and cancellation. Require a valid control for inference and keep network and transport scope attached.

Separate Initial response, handshake, and verified application payload evidence. A version difference is a diagnostic observation and MUST NOT automatically change production relay settings.

## Contracts and ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

Implementation must use existing protection callbacks for outbound sockets, explicit network binding and cancellation/deadline ownership. Validate schema revisions from current source before altering wire or persistence. Extend existing tests where possible.

## Risks / Trade-offs

Controlled targets can still fail and mobile paths change. Preserve unknown/unsupported states, stable controls and local-only identity. External sources motivate methods but are not trusted configuration or executable code. No source code is copied in this proposal; check exact licenses before future reuse.

## Migration Plan

Add an opt-in manual capability, retaining old saved diagnostics. New optional evidence is versioned through existing contracts; older records remain readable with unknown fields. Roll back by disabling the capability, not rewriting past evidence. Field acceptance requires separately authorized owned endpoints and devices; local tests alone cannot prove Russian-network effectiveness.

Initial protection uses public derivation inputs and does not authenticate the endpoint. Endpoint authentication requires a successful TLS handshake and certificate verification; Initial-only observations must never receive that status.
