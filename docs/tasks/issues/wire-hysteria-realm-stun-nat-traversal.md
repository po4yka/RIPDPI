---
id: TRN-1786264762917675
title: Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)
kind: feature
status: dropped
area: transport
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917675-wire-hysteria-realm-stun-nat-traversal
created: 2026-05-22
updated: 2026-08-09
source_wiki_pages:
  - hysteria2-tuic
linked_task: null
closed_at: "2026-08-09T11:12:18Z"
closed_reason: obsolete alpha proposal
evidence_summary: The Hysteria Realm alpha proposal has no implementation, current contract, deployment owner, or supported topology.
---

## Motivation

sing-box v1.14.0-alpha.22 (2026-05-11) introduced a Hysteria Realm service that enables direct peer-to-peer Hysteria2 QUIC tunnels between two clients behind separate NATs — without a fixed listening server on a datacenter ASN. Datacenter-path QoS policies (including short-transfer stalls and session-volume caps) can affect conventional Hysteria2 deployments; Realm permits alternate peer placement because the data peer can live on a residential or mobile ASN behind NAT.

> [!warning] LOW dedup confidence
> The `ripdpi-hysteria2` crate already exists. Realm is a new sing-box feature (v1.14.0-alpha.22, 2026-05-11) that the existing crate likely does not yet support; PR description must explicitly confirm Realm functionality was not previously available.

## Proposed change

Extend `ripdpi-hysteria2` (or add a sibling `ripdpi-hysteria-realm` crate) to support sing-box Realm rendezvous:

1. STUN-discovered public address+port registration with the realm rendezvous server.
2. UDP hole-punching from both peers based on rendezvous metadata.
3. Direct Hysteria2 QUIC tunnel between peers post-hole-punch (realm leaves the data path).
4. JNI/Kotlin diagnostic surface for realm-vs-direct-Hysteria2 mode selection in UI.

### Linked deploy task

`linked_task:` points to the sibling deploy task standing up the realm rendezvous server. Both must ship together.

## Acceptance criteria

- [ ] Two RIPDPI clients on separate NATs (test-lab `relay/` scenario or two real RU-ASN devices) successfully hole-punch and exchange data via Hysteria2 QUIC.
- [ ] Empirical NAT-compatibility report: which RU mobile carrier CGNAT configurations succeed / fail with STUN hole-punch.
- [ ] Diagnostic verdict surfaces `HYSTERIA_REALM_OK` / `HYSTERIA_REALM_FAIL_STUN` / `HYSTERIA_REALM_FAIL_PUNCH` distinguished by phase.
- [ ] LOW-confidence dedup resolved in PR description: confirmed Realm functionality not previously available in `ripdpi-hysteria2`.

## Risks / open questions

- STUN-based hole-punching is unreliable for symmetric NATs (typical RU carrier-grade NAT). Empirical success rate from RU mobile CGNAT is the gating question.
- Do path middleboxes drop "unsolicited inbound UDP after outbound STUN burst" as a class? If so, Realm fails at the hole-punch step.
- QUIC connection ID persistence under typical RU CGNAT NAT-table timeouts — sustained connection over 30-min idle followed by burst traffic needs measurement.

## References

- hysteria2-tuic section Hysteria Realm NAT-Traversal (sing-box v1.14.0-alpha.22, 2026-05-11) — wiki concept page section
- censorship-update-github-releases-2026-05-22 — source digest
- Linked deploy task: `add-hysteria-realm-rendezvous-role`

## Work log

- 2026-06-05: No Realm/STUN/NAT-traversal code found anywhere in ripdpi-hysteria2 (native/rust/crates/ripdpi-hysteria2/src/ has client.rs, config.rs, quic_transport, etc. but no realm/hole-punch logic); no sibling ripdpi-hysteria-realm crate; HYSTERIA_REALM_OK/FAIL_STUN/FAIL_PUNCH constants absent. All acceptance criteria unmet; work not started.
