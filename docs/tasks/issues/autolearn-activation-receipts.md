---
id: DGN-1787230878672684
title: Record authoritative Autolearn activation receipts
kind: feature
status: review
area: diagnostics
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: record-autolearn-activation-receipts
created: 2026-08-20
updated: 2026-08-20
status_detail: Implementation and rebased local gates passed; remote CI pending after push.
---

## Goal

Ensure every successful proxy or VPN runtime start exposes its authoritative ready-time Autolearn state before `Connected` and retains a privacy-safe activation receipt that distinguishes persisted, resolved, and native-effective configuration.

## Acceptance criteria

- The authoritative native proxy snapshot used to resolve the listener is published with telemetry state `snapshot` before `Connected` for proxy and VPN modes.
- Initial starts and proxy-runtime replacements persist distinct activation receipts with runtime generation, mode, policy correlators, resolution source, and persisted/resolved/effective enabled states.
- Native/request divergence is classified explicitly; unavailable telemetry is never rendered as effective `disabled`.
- Receipt storage failure does not block networking, cancellation remains propagated, and diagnostics receive a structured warning.
- Existing native-session event storage/export and redaction retain the receipt without a Room, JNI, protobuf, native telemetry, or archive-schema migration.
- Focused lifecycle, recorder, archive, static-analysis, architecture-health, and locked metadata gates pass with exact-SHA evidence recorded in the OpenSpec verification artifact.
