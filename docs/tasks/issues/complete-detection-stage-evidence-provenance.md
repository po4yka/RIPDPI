---
id: DGN-1786559468691266
title: Complete detection stage evidence provenance
kind: bug
status: review
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Detection runner provenance is preserved in finalized outcomes and archive stage evidence.
---

## Goal

Preserve the detection runner as a first-class evidence source on the completed
home diagnostics stage so exported stage provenance agrees with the detection
verdict and findings already present in the home analysis.

## Acceptance criteria

- A completed detection stage records its detection-runner provenance, verdict,
  detected-signal count, and bounded findings on the stage summary.
- Composite archive stage summaries and the manifest stage index export that
  structured provenance without marking the stage as `evidence_unavailable`.
- Completeness metadata treats scan-session artifacts as not applicable for the
  detection runner while counting its verdict evidence references.
- Regression tests, diagnostics unit tests, static analysis, task validation,
  and architecture health checks pass.
