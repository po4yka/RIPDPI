## Context

Current platform_ttl reads the local configured outbound TTL, not router responses or interference location. An opt-in paired control/test SNI hop experiment is a distinct diagnostic, not a reinterpretation of that value.

## Goals / Non-Goals

Goal: reproducible bounded evidence with honest capability and inference limits. Non-goals: automatic background scanning, public measurement upload, mandatory backend, country-wide guarantees or production transport changes.

## Decisions

Probe only when platform capabilities and the selected direct network are verified; unsupported ICMP capture or socket TTL operations return unsupported without root escalation or fabricated hops.

Use a bounded user-started control/test SNI experiment against the same user-authorized endpoint with ordered TTL values, fresh connections, repeated controls and a total cancellation deadline. Do not scan arbitrary address ranges.

Keep path addresses and timings local; redact raw hops, public IPs and precise timing from ordinary exports. Any diagnostic detail export requires a separate visible choice and disclosure.

Report missing ICMP, asymmetric paths, load balancing, invalid control, proxy interception and network changes as measurement limits, never proof of no censorship.

## Contracts and ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

Implementation must use existing protection callbacks for outbound sockets, explicit network binding and cancellation/deadline ownership. Validate schema revisions from current source before altering wire or persistence. Extend existing tests where possible.

## Risks / Trade-offs

Controlled targets can still fail and mobile paths change. Preserve unknown/unsupported states, stable controls and local-only identity. External sources motivate methods but are not trusted configuration or executable code. No source code is copied in this proposal; check exact licenses before future reuse.

## Migration Plan

Add an opt-in manual capability, retaining old saved diagnostics. New optional evidence is versioned through existing contracts; older records remain readable with unknown fields. Roll back by disabling the capability, not rewriting past evidence. Field acceptance requires separately authorized owned endpoints and devices; local tests alone cannot prove Russian-network effectiveness.

The TTL experiment MUST establish TCP normally, then apply the bounded outbound TTL to ClientHello transmission, not to the initial SYN. Retransmissions and socket reuse MUST remain isolated within the per-attempt budget. If the platform cannot enforce this lifecycle or observe the necessary responses, it MUST return unsupported or inconclusive without a hop-location inference.
