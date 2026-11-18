---
id: DGN-1795014097920678
title: Compare selective availability across explicit network sessions
kind: feature
status: backlog
area: diagnostics
priority: medium
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: paired-network-availability
created: 2026-11-18
updated: 2026-11-18
---

## Goal

The selective availability matrix DGN-1791519134625558 already measures bounded target cohorts and payloads; ComparisonScanCoordinator compares raw versus in-path runs. Extend those existing owners with explicit user-selected cross-network session pairing rather than add another scanner.

## Acceptance criteria

- Pair two explicit manual sessions only when target set, endpoint identity, probe parameters and app configuration revisions match, recording elapsed age and transport labels without retaining SIM, SSID or public-IP identifiers.
- Use verified TLS and bounded complete application payload observations as eligibility controls, retaining control-failed and unmeasured rows outside the denominator of path-specific failures.
- Reuse the existing matrix result and history models; network transitions during either leg invalidate that leg. Do not switch networks automatically or disable VPN protection to force a result.
- Present descriptive differences only, preserving per-leg stage outcomes and bounded repetition evidence in redacted exports. Do not infer permitted prefixes, ASN-wide availability or nationwide effectiveness.

## Ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

## Sources and scope

https://github.com/vadim-tolmachev/allowlist-probe

The pilot motivates same-device near-time controls and distinguishing a dead endpoint from path-specific failure. Its small sample is not country-wide proof; neither public endpoint lists nor claimed permitted ASNs are trusted inputs.

No implementation, device probe, credential use or deployment is performed by this planning change.
