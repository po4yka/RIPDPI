---
name: client-legal-safety
description: Client legal-safety review for domains, diagnostics targets, probes, workflows, and jurisdiction risk.
---

# Client Legal Safety

Conservative review workflow for what an end user may be exposed to by running
the app, enabling a feature, or executing built-in diagnostics.

## When To Use

- Shipping or editing diagnostics target lists
- Adding probe hosts, bootstrap endpoints, or service checks
- Deciding whether a blocked or politically sensitive domain should stay in-app
- Reviewing client-side risk in a specific jurisdiction

## Required Standard

This is a current-law task. Always verify with live sources before concluding
that a host or workflow is safe enough to ship.

Prefer primary or official sources:

- `publication.pravo.gov.ru`
- prosecutor / regulator / ministry sites (`genproc.gov.ru`, `rkn.gov.ru`, etc.)
- official statute databases (`consultant.ru`, `garant.ru`) when needed for the
  current text of a law

Use secondary reporting only to locate a designation or enforcement event, then
confirm with a primary or official source where possible.

## Review Steps

1. Identify the exact client action:
   - passive inclusion in the app
   - automatic reachability probe
   - manual probe
   - DNS lookup only
   - service bootstrap / handshake
   - download, promotion, or onboarding flow
2. Enumerate every touched host:
   - source generators
   - shipped JSON/assets
   - service/bootstrap targets
   - tests and docs that may reintroduce the host later
3. Classify each item with a conservative client-risk label:
   - `safe`
   - `sensitive`
   - `unsafe`
   - `needs_local_counsel`
4. Recommend the lowest-risk product action:
   - keep
   - keep manual-only
   - remove from automatic runs
   - remove entirely
   - replace with neutral controls

## Conservative Rules

- Treat hosts tied to organizations designated as undesirable as `unsafe` for
  client reachability probing.
- Treat circumvention-tool bootstrap, download, or promotion hosts as at least
  `sensitive`; move to `unsafe` when current law or current enforcement makes
  client access materially risky.
- Distinguish operational blocking from client-side liability. A blocked domain
  is not automatically unlawful to probe.
- When evidence is mixed or incomplete, bias toward removal from shipped default
  diagnostics and mark the item `needs_local_counsel`.

## Output Format

Return a compact matrix with:

- host
- classification
- legal basis / enforcement signal
- recommended product action
- sources

If code changes are requested, update all of the following together:

- source-of-truth catalogs / generators
- shipped generated assets
- tests and docs that mention removed hosts

## Non-Goals

- Do not give definitive legal advice for a user or company.
- Do not rely on stale training knowledge for current law.
