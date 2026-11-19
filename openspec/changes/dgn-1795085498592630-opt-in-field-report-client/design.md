## Context

Inputs: `FieldTelemetrySummary` (core/diagnostics-data), filters in `core/diagnostics/.../export/` (`DeveloperAnalyticsAllowListFilter.kt`, `DiagnosticsArchiveRedactor.kt`), and the failure stage from R2. `server_ref`, `field_report` come from the R4 parse result. Periodic work follows the pattern of `core/service/.../SharedPriorsRefreshWorker.kt`. Settings use `core/data/model/src/main/proto/app_settings.proto` (serialized lane, see protobuf-schema-evolution).

## Goals / Non-Goals

- Goal: opt-in, anonymous, aggregated, tunnel-only reports.
- Non-goal: the server (deploy D2), retries, per-user analytics, crash reporting.

## Decisions

- Aggregate in memory and a small persisted pending queue (max 24 hourly reports); delete on revoke. Rejected: persisting raw events.
- Latency histogram with fixed upper bounds [50,100,250,500,1000,2500,5000,+Inf] ms, counts only.
- Upload via a socket bound to the VPN network and the project's HTTP client, honoring the VpnService protect invariant; if the tunnel is down, do not send. Rejected: direct upload, which would expose the underlying network's identity.
- `cohort_token` is sent as received; the client neither derives nor stores any other identifier.
- Consent text is a localized string in all 10 locales; wording is reviewed through `client-legal-safety` and `legal-check`.
- Assumption: `legal-check` is a local-only, gitignored skill that is absent from some checkouts; the reviewer runs it where present and records the result, and if absent that fact is recorded as a gap, not a pass.
- Assumption: AGENTS.md "no data leaves the device except explicit export" is amended by the owner decision; this change proposes the wording and does not edit AGENTS.md silently.
- Assumption: ASN and country come from existing network-fingerprint code and are coarse only; `network-fingerprint-privacy.md` rules apply and the implementation checks that no remembered-network key is sent.

## Implementation decisions

- Attempt source: `FieldTelemetrySummary` and its telemetry rows carry no server_ref and only latency bands, so they cannot build a v1 bucket. The source is the selector member probe (`RelayPayloadMemberLatencyProbe`), the one on-device measurement per bundle endpoint with a latency in milliseconds. The server_ref is the member tag, checked against `^[A-Za-z0-9_-]{1,64}$`. A failure before the probe's HTTP stage belongs to the local candidate runtime and is not counted. The probe does not report which remote handshake failed, so a remote failure reports `unknown`; wiring the typed native failure (`nativeFieldFailureStage`) into the candidate measurement is a follow-up.
- Allow-list and redaction: the report is a positive projection built from typed, pattern-checked values (`buildFieldReport`), so no other field of the probe input can reach it. `DeveloperAnalyticsAllowListFilter` and `DiagnosticsArchiveRedactor` project diagnostics archives and have no fields in common with the report, so they are not reused.
- Network context: no on-device source gives the ASN without a public IP lookup, and automatic work must not make one (`avoid-public-ip-lookup-in-automatic-diagnostics`); `ConnectionPolicyRuntimeContextAssembler` records the same gap. The schema requires `asn`, so production passes no network context and every completed hour is dropped unsent. Owner decision needed: an offline ASN source, a D1 contract change that makes `asn` optional, or another approved source.
- Transport: the app UID is excluded from its own VPN, so a socket bound to the VPN network cannot carry the report. The report goes through the tunnel's local SOCKS listener (`ServiceRuntimeHandle.tunnelSocksRoute`, the onboarding validation path) with `OwnedTlsClientFactory`, no redirects and no connection retry. Without a VPN runtime nothing is sent.
- Persistence and lifecycle: pending hours and request instants live in one fsynced, atomically renamed file under `noBackupFilesDir` (at most 24 hours). Every write re-checks consent under the lock and deletes the file when consent is off. `FieldReportConsentFollower` (app startup) schedules the hourly `FieldReportWorker` while consent is on, and on revocation cancels it and deletes the file. A backup restore clears the consent.
- Cap and failure: every request counts toward `max_reports_per_day` in the rolling 24 hours, a failed request drops its report, and the worker always returns success so WorkManager never retries.

## Contracts and ownership

- Writers: single owner for `app_settings.proto`, locale resources, and the vendored contract lane (after R2 and R4).
- Rust: none unless the latency source requires a native counter.

## Risks / Trade-offs

- Re-identification through rare ASN + server_ref combinations -> k >= 5 suppression and hour rounding; consider a higher k after D2 review.
- Opt-in rate too low for coverage -> accepted trade-off.
- Legal exposure of sending data from restrictive jurisdictions -> legal reviews are an acceptance gate.

## Migration Plan

New setting defaults off; no data migration. Rollback is reverting the change; pending buckets live only in app data. Rollout gate: R2, R4, legal evidence, and D2/D7 for live acceptance.
