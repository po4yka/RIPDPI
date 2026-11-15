---
id: DAT-1794728756596106
title: Correct WireGuard and AmneziaWG conf import findings
kind: bug
status: doing
area: data
priority: high
owner: IMP-04 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: imp04-conf-import-corrections
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the IMP-04 audit findings. The importer preserves routing policy, rejects invalid requests before save, and identifies settings the standalone tunnel does not apply.

## Acceptance criteria

- F1–F4: Preserve list entries, require explicit conf routes, validate before save, and apply the last keepalive value.
- F5–F8: Use one section grammar, reset the carrier for conf, verify the existing safe URI conversion, and report ignored settings.
- F9–F12: Classify AWG syntax by presence, bound parsing and redact errors, reject duplicate interfaces, and define empty AWG 3.x values.
- Keep one correction commit per finding. Use the existing F7 commit. Verify affected module tests and source gates before integration.

## Ownership

- Parser worker: conf parser/model, safe grammar and parse errors, ignored option names, parser and subscription regressions.
- Editor worker: AWG editor projection, Connect readiness and save order, carrier reset, editor and ViewModel regressions.
- Ingress worker: scanner, shared file, URI bridge, clipboard classification, text size checks and import regressions.
- Integrator: ignored-option display, direct Paste size check, cohort projection, locale resources and export key list, OpenSpec and combined verification.
- Each writer works in a dedicated worktree. The integrator serializes locale and generated files. No golden, baseline, native wire or persistence schema changes.
