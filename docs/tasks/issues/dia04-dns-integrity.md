---
id: DGN-1794732408586377
title: Repair DIA-04 DNS integrity and ECH evidence
kind: bug
status: doing
area: diagnostics
priority: high
owner: DNS diagnostics maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: dia04-dns-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair the eight confirmed DIA-04 findings without adding a resolver protocol or claiming DNSSEC validation.

## Acceptance criteria

- F1: One resolver service cannot provide independent quorum votes through different IDs or protocols.
- F2: An extra loopback or private address cannot be hidden by a shared public address or prefix.
- F3: The real CDN fallback consumer uses the refreshed or restored configuration.
- F4: A direct CDN refresh protects outbound sockets and fails closed on protection failure.
- F5: Answer facts and endpoint metadata have one owner; missing selection remains explicit. Typed DNS address arrays contain only valid IP addresses, not error text or stale legacy values.
- F6: Valid acyclic DNS compression chains are not marked malformed; cycles and bounds remain rejected.
- F7: Numeric EDE reaches diagnostic facts through real UDP and encrypted exchanges.
- F8: Persisted ECH admission uses full structural validation and supported-entry selection.
- Each finding has a separate commit, a regression test, and independent read-only review.
- Observe affected package tests, formatting, lint, metadata, task checks and just ci-gates before main integration and push.

## Parallel ownership

- Oracle writer: F1, F5 and F7. Owns diagnostics-runner probe_context and connectivity/probes/dns, diagnostics-dns dns_oracle and dns observation/query files, diagnostics-classification observations/dns.rs and its owning tests, ech-dns endpoints and fallback. The ech-dns wire.rs lane is assigned only to this writer.
- CDN writer: F8, F4 and F3 in that order. Owns diagnostics-dns cdn_ech, diagnostics-tls TLS configuration and owning tests, diagnostics-transport transport/protect.rs and its export, and refresh-only platform integration when needed.
- Serialized handoff: ech-dns encrypted.rs and lib.rs belong to the oracle writer through F7; only after explicit release and adoption of that committed source may the CDN writer edit the hooks-aware export for F4.
- Primary writer: F2 and F6. Owns diagnostics-contracts DNS overlap helper and tests, diagnostics-dns compression analysis and tests, task and specification artifacts, combined checks and main integration.
- Each writer uses a separate worktree. Review agents are read-only. Only the primary writer updates task state and integrates commits.
- Cargo.lock, dependency versions, schemas, locale sets, golden fixtures and baseline files have no writer: changes to these files are not planned.
