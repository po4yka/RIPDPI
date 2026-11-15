---
id: DGN-1794732423381679
title: Repair DIA-06 ECH probe contracts
kind: bug
status: done
area: diagnostics
priority: high
owner: dia06-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dia06-ech-probe-contracts
created: 2026-11-15
updated: 2026-11-15
closed_at: "2026-11-15T10:58:00Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Six DIA-06 fixes are separate commits on main and pushed to origin at fa7cd279ea32175e02c54b752877995dc7ba498d. Final source e76f12019402e57cb2d466ad6ea7ce3e6400c8ae passed 163 tests, strict Clippy, exact ci-gates, architecture, locked metadata and task contracts; general and async reviews passed. External and device compatibility are not claimed.
---

## Goal

Repair the six confirmed DIA-06 findings with one implementation commit per finding.

## Acceptance criteria

- Clean HTTPS NODATA produces NoEchConfig; NXDOMAIN remains a distinct error.
- Unsupported relay, resolver and strategy context fails before I/O and has no used relay.
- Authenticated ECH rejection remains distinct without usable retry configs.
- AliasMode parameters and incompatible mandatory records do not supply ECH configs.
- Transport timeouts without TLS evidence produce Inconclusive.
- Complete DNS discovery, including retries, observes one deadline.
- Regression tests, independent reviews and combined source gates pass.

## Ownership and serialized lanes

- dns_fixes: driver DNS discovery, private dns.rs and owning dns_tests.rs; F1/F4/F6.
- tls_fixes: driver TLS handshake, outcome enum/classifier and owning tls_tests.rs; F3/F5.
- context_fix: runner attempt/context and ech_handshake_runner_tdd.rs; F2.
- Integration owner: planning, manifests, Cargo.lock, generated API snapshot, combined checks and main integration.
- Writers use separate worktrees; reviewers are read-only.
- Serialize driver root integration: DNS F1/F4/F6, then TLS F3/F5. Workers edit separate ranges in isolated worktrees; integration owner resolves overlap.
- Serialize ech_handshake.rs integration: F2 runner first, then F3/F5 enum/classifier.
- Dependency versions, locales, quality baselines and goldens stay unchanged.
