---
title: Add HTTP injection error-page diagnostic probe
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Add HTTP injection error-page diagnostic probe #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-http-injection-error-page-diagnostic-probe`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-http`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-diagnostics-http/**`, `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

A targeted plain-HTTP probe that detects ISP-injected error-pages on
cleartext HTTP responses, distinct from RIPDPI's existing TLS-side
classification and the runtime error-page fingerprinter.

## Motivation

`ripdpi-failure-classifier` already includes error-page fingerprinting
on the runtime path, but the active diagnostics suite has no
equivalent of dpi-detector's `check_http_injection`: an explicit
probe that issues a plain `GET http://<domain>/` and compares the
response against known error-page shapes (transparent proxy headers,
HTML markers, redirects to operator portals). This produces a
positive "HTTP injection observed on this network" verdict for
inclusion in the direct-mode classifier and the diagnostics summary.

## Scope

- **In scope:** new HTTP injection probe in `ripdpi-monitor`,
pluggable into the diagnostic catalog and the per-domain reachability
card; reuses the existing fingerprint set in
`ripdpi-failure-classifier` rather than maintaining a parallel one;
feeds `DiagnosticResult` reasons (specifically a new
`HTTP_INJECTION` evidence flag).
- **Out of scope:** new fingerprint authoring (use the curated set
already shipped); HTTPS-side error-page detection (already exists);
payload archival of injected pages.

## Acceptance criteria

- [ ] Probe issues a single GET against plain HTTP for each target,
    bounded by the standard probe wall-clock budget.
- [ ] Response is matched against `ripdpi-failure-classifier`
    fingerprints; verdict is one of `clean`, `injected:<operator>`,
    `redirect_to_portal`, `connection_reset_after_request`.
- [ ] At least three operator-class fingerprints (transparent proxy
    header, middlebox-style HTML marker, captive-style redirect) are
    covered by unit tests with golden response bodies.
- [ ] `HTTP_INJECTION` evidence flag is propagated into the
    `DiagnosticResult` reason for `DNS_BLOCK` and `IP_BLOCK_SUSPECT`
    classes where applicable.
- [ ] Probe is included in the export bundle's `report.json` as its
    own entry with the matched fingerprint id (not the response
    body) — never persist captured HTML in artifacts.

## Design notes

Cleartext-HTTP probes are sensitive: do not run against arbitrary
user-supplied domains in automatic profiles. Limit automatic runs to
the curated diagnostic target pack; manual runs against user-supplied
domains require an explicit confirmation in the UI. Hash responses for
fingerprint matching; do not store full response bodies.

## Source reference

dpi-detector v3.2.2: `core/tls_scanner.py` `check_http_injection`.
RIPDPI parallel: `ripdpi-failure-classifier` error-page fingerprint set.

## Risks / open questions

- Cleartext probing may itself trigger ISP logging; document this in
the diagnostics user manual and keep the probe gated.
- Some operators inject only on specific Host headers; the probe must
send a realistic UA + Host pair, not a synthetic test fixture.

## Links

- [[ripdpi-android]]
- [[Epic - Direct-mode diagnostic state machine]]
