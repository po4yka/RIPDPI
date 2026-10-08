## Context

The finalizer currently trusts the prepared fingerprint. The handover flow is debounced and cannot prove continuity. A process-owned physical observer already issues opaque tokens for relay candidate checks.

## Goals / Non-Goals

- Goal: prevent wrong-network diagnostic policy and runtime effects.
- Non-goal: change network probes, classification heuristics, or existing persisted fingerprints.

## Decisions

- Expose the existing physical callback token through a small shared model interface. Reuse CandidateRelayNetworkEpoch; do not reuse the VPN-owned direct-DNS generation or debounced handover events.
- Capture the token before asynchronous scan preparation and retain it on the prepared request.
- Require both token equality and fingerprint scope equality at finalization and immediately before runtime effects. Fail closed when either is unavailable.
- Sanitize reports after enrichment so recommendations cannot be reconstructed by that step. Use a warning diagnosis, retain raw observations, and remove nested recommendation authority. Apply the same rule to recovered reports.
- Give DNS-corrected follow-up scans a fresh scope and do not carry an override across a network change.

## Contracts and ownership

- Shared model interface and opaque transient token only; no wire or storage migration.
- Primary writer owns diagnostics, planning, and integration. The service adapter writer owns the shared interface. Service adapter writer and regression writer use separate worktrees as recorded in the portfolio task.
- No Rust, lockfile, locale, baseline, or golden changes.

## Risks / Trade-offs

- Callback evidence can be temporarily unavailable at startup; retain the report but withhold recommendation authority.
- Conservative callback invalidation can reject a scan after harmless link changes. A safe retry is preferable to applying evidence to an unproven path.
- Unit tests verify fault sequences. A device handover remains separate runtime acceptance evidence.

## Migration Plan

No migration. Existing stored reports are not reclassified. Revert the scoped commit to roll back. Validate with core:diagnostics and core:service unit tests, staticAnalysis, check_architecture_health.py, cargo metadata --locked, and a read-only review. Use the repository native-less JVM test lane; this does not validate native packaging.
