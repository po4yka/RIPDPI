---
title: "Investigate RKN unannounced protocol-class signatures (Dec 2025 shift)"
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: epic-transport-obfuscation-research
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-06-10
source_wiki_pages:
  - "rkn-protocol-class-blocking-shift-dec-2025"
linked_task: null
---

## Motivation

RKN shifted in Dec 2025 to protocol-class fingerprint blocks (SOCKS5, VLESS, L2TP) without enumerating operators. Open question: which additional unannounced protocol-class signatures have landed, and do dpi-checkers / DPI Detector / rkn-block-checker tools detect them? This directly determines which protocols RIPDPI can still rely on as primary transports.

Child task of `epic-transport-obfuscation-research` (the former `epic-direct-mode-transport-policy-and-verdicts` was removed).

## Proposed change

Diagnostic investigation, not a feature build:

1. Run dpi-checkers + DPI Detector + rkn-block-checker against the full transport catalogue (every protocol crate under `native/rust/crates/ripdpi-<transport>/`).
2. Catalog which protocol fingerprints currently trigger blocks vs pass through, across multiple RU ISP vantages.
3. Update `rkn-protocol-class-blocking-shift-dec-2025` wiki page with the empirical fingerprint catalog.
4. Feed results into `ripdpi-runtime-policy` defaults — automatically de-prioritize transports with high block rate.

## Acceptance criteria

- [ ] Empirical block-rate matrix produced for every transport in `native/rust/crates/`.
- [ ] At least 3 RU ISP vantages sampled (e.g., MTS mobile, Rostelecom home, MegaFon).
- [ ] Wiki page updated with `## Field measurement 2026-XX-XX` section.
- [ ] `ripdpi-runtime-policy` defaults adjusted (with explicit reasoning per change).

## Risks / open questions

- "Unannounced signatures" are by definition not catalogued publicly — empirical detection requires sustained testing across many protocols.
- False positives possible: a transport may fail for reasons unrelated to RKN (server outage, ISP issue, certificate expiry).

## References

- rkn-protocol-class-blocking-shift-dec-2025 — wiki concept page
- rkn-block-checker-methodology — diagnostic tool
- Parent epic: `epic-transport-obfuscation-research` (reassigned 2026-06-10; former `epic-direct-mode-transport-policy-and-verdicts` was removed)
- Linked deploy task: `investigate-rkn-unannounced-protocol-class-signatures-deploy`
- Gating dependency: cannot progress without sustained access to ≥3 RU ISP vantages (external, not a repo-side task) — see Risks.

## Work log

- 2026-06-05: No acceptance criteria met — no empirical block-rate matrix, no wiki field-measurement section, no ripdpi-runtime-policy defaults adjusted from RKN fingerprint research. Parent epic `epic-direct-mode-transport-policy-and-verdicts` is dangling (nulled). Task remains open as a research/diagnostic investigation requiring external ISP vantage testing.
