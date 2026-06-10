---
title: "Redact raw BSSID from detection Finding strings and CapturedWifiIdentity"
type: task
status: todo
area: android
priority: high
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Kotlin audit found the highest-risk privacy issue of the pass. `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/LocationSignalsChecker.kt:241` interpolates the raw BSSID into a `Finding` string:

```kotlin
findings.add(Finding("BSSID: ${snapshot.bssid}"))
```

`Finding` objects feed detection scoring and are currently in-memory only, but the data model permits serialization into a diagnostics report, crash log, or telemetry event. `.claude/rules/network-fingerprint-privacy.md` forbids raw BSSID in any log, persisted artifact, or telemetry under any encoding — only the SHA-256 scope hash may appear. A leak here would also expand the Play Store Data Safety declaration to list "Device identifiers."

Secondary (lower risk, same rule): `core/service/.../NetworkFingerprintProvider.kt:149` constructs `CapturedWifiIdentity(ssid = ..., bssid = ...)` as an in-memory intermediate before hashing — a crash reporter intercepting `toString()` or a stray debug log would leak it.

## Proposed change

1. Replace the raw-value interpolation at `LocationSignalsChecker.kt:241` with a presence flag, e.g. `Finding("BSSID: ${if (snapshot.bssid.isUsable()) "present" else "absent"}")`, treating the consent-denied sentinel `02:00:00:00:00:00` as absent.
2. Harden `CapturedWifiIdentity`: give it a redacting `toString()` (or null the raw fields immediately after the scope hash is computed) so the raw value cannot escape via logging/crash-reporter paths.
3. Grep the detection + service modules for any other raw `bssid`/`ssid`/IMEI/IP interpolation into `Finding`, log, or telemetry strings; fix any sibling sites.

## Acceptance criteria

- [ ] PR confirms current state at `LocationSignalsChecker.kt:241` and `NetworkFingerprintProvider.kt:149`.
- [ ] No raw BSSID reachable in any `Finding`, log, or serialized artifact — verified by the rule's audit grep returning only intentional hashing-helper hits.
- [ ] `CapturedWifiIdentity.toString()` redacts (or raw fields are cleared post-hash).
- [ ] Unit test: a `Finding` produced for a known BSSID does not contain the BSSID substring.
- [ ] `./gradlew :core:detection:testDebugUnitTest :core:service:testDebugUnitTest` green.

## Risks / open questions

- Detection scoring must not regress — confirm the presence/absence signal is sufficient for whatever `LocationSignalsChecker` was scoring on.
- Confirm `Finding` is not already redacted downstream (the audit found `DiagnosticsSummaryProjector` SSID emission was already safe via `RedactedWifiSummary` — mirror that pattern if a redaction layer already exists).

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 2).
- `.claude/rules/network-fingerprint-privacy.md` (forbidden inputs, audit grep).
- Safe precedent: `DiagnosticsRedactedSummaries.kt` / `NetworkSnapshotModel.toRedactedSummary()`.
