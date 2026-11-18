## Context

The selective availability matrix DGN-1791519134625558 already measures bounded target cohorts and payloads; ComparisonScanCoordinator compares raw versus in-path runs. Extend those existing owners with explicit user-selected cross-network session pairing rather than add another scanner.

## Goals / Non-Goals

Goal: reproducible bounded evidence with honest capability and inference limits. Non-goals: automatic background scanning, public measurement upload, mandatory backend, country-wide guarantees or production transport changes.

## Decisions

Pair two explicit manual sessions only when target set, endpoint identity, probe parameters and app configuration revisions match, recording elapsed age and transport labels without retaining SIM, SSID or public-IP identifiers.

Use verified TLS and bounded complete application payload observations as eligibility controls, retaining control-failed and unmeasured rows outside the denominator of path-specific failures.

Reuse the existing matrix result and history models; network transitions during either leg invalidate that leg. Do not switch networks automatically or disable VPN protection to force a result.

Present descriptive differences only, preserving per-leg stage outcomes and bounded repetition evidence in redacted exports. Do not infer permitted prefixes, ASN-wide availability or nationwide effectiveness.

## Contracts and ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

Implementation must use existing protection callbacks for outbound sockets, explicit network binding and cancellation/deadline ownership. Validate schema revisions from current source before altering wire or persistence. Extend existing tests where possible.

## Risks / Trade-offs

Controlled targets can still fail and mobile paths change. Preserve unknown/unsupported states, stable controls and local-only identity. External sources motivate methods but are not trusted configuration or executable code. No source code is copied in this proposal; check exact licenses before future reuse.

## Migration Plan

Add an opt-in manual capability, retaining old saved diagnostics. New optional evidence is versioned through existing contracts; older records remain readable with unknown fields. Roll back by disabling the capability, not rewriting past evidence. Field acceptance requires separately authorized owned endpoints and devices; local tests alone cannot prove Russian-network effectiveness.
