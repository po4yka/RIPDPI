---
title: "Spike: DNS-Morph bootstrap as fallback bootstrap channel"
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-05-28
source_wiki_pages:
  - "dns-morph-bootstrap"
linked_task: null
---

## Motivation

DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the threat model: handshake uses DNS port-53 (a TSPU policy gap as of 2026-05-22), data plane uses any underlying transport. Provides a structurally new bootstrap surface that does not depend on TSPU not yet having signature-trained against TLS ClientHello or QUIC Initial. No mature Russia-targeting fork exists yet. Spike validates whether the bootstrap shim is buildable on Android and whether RU-ASN clients can complete the ~80 type-A query handshake under typical TSPU port-53 inspection.

> [!warning] LOW dedup confidence
> Adjacent existing surface: `ripdpi-dns-resolver` crate (resolver, not handshake bootstrap). The old DoH arbitrary-payload tunnel task was dropped as obsolete task-board planning, so this spike should compare only against current resolver and bootstrap code before merging.

## Proposed change

Stand up DNS-Morph bootstrap as a fallback bootstrap channel in RIPDPI Android:

1. New Rust crate `ripdpi-dns-morph` under `native/rust/crates/` implementing the DNS-Morph client: base32-encoded type-A query fragments (20–50 chars), A+CNAME response demux, selective-repeat reliability upstream, stop-and-wait downstream.
2. Bootstrap orchestrator integration: when primary transport bootstrap fails, fall back to DNS-Morph to exchange handshake bytes with the bridge, then switch data plane to VLESS+Reality (or pre-configured transport) on a separate port.
3. JNI/Kotlin layer: expose DNS-Morph status (bootstrap in progress / OK / failed) to the UI diagnostic view.

### Linked deploy task

`linked_task:` points to the sibling deploy task that stands up the DNS-Morph bridge server. Both must ship together — this client task is gated on the bridge being reachable from RU-ASN.

## Acceptance criteria

- [ ] `ripdpi-dns-morph` crate compiles for all 4 Android ABIs.
- [ ] Bootstrap completes against a synthetic DNS-Morph bridge in `test-lab/dns/` scenario (~3–8 s end-to-end per paper).
- [ ] Active-probing defense verified: probing the bridge with `dig @bridge www.example.com` returns normal DNS responses.
- [ ] Integration test in `core/diagnostics-data/` covers bootstrap → primary-transport handoff.
- [ ] LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a duplicate of `ripdpi-dns-resolver` or any current bootstrap transport code.

## Risks / open questions

- Bootstrap latency (~3–8 s) is acceptable as fallback, painful as primary path.
- RU "trusted DNS" mandates may route outbound port-53 queries to TSPU-friendly resolvers — bridge reachability and resolver-routing topology are open questions for the linked deploy task.
- Paper-based reference code targets Tor pluggable transports; re-targeting cost is part of the spike.

## References

- dns-morph-bootstrap — wiki concept page with mechanism + threat-model comparison
- censorship-update-net4people-2026-05-22 — net4people #619 source
- Linked deploy task: `add-dns-morph-bridge-ansible-role`
