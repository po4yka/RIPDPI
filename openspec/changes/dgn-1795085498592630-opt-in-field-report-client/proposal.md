# Change: Send opt-in anonymous aggregated field reports

Task ID: `DGN-1795085498592630`

## Why

The app measures connections and blocking locally (`FieldTelemetrySummary`, `DeveloperAnalyticsAllowListFilter`, `DiagnosticsArchiveRedactor`) but operators never see it, so server failures and regional blocking go undetected. The owner decided on opt-in, anonymous, aggregated field reports.

## What Changes

- Consent UI: off by default, revocable, explains what is sent; setting stored in `core/data/model/src/main/proto/app_settings.proto`.
- Hourly on-device aggregation into `client-field-report` v1 buckets (server_ref, protocol, outcome, failure_stage, attempts, latency histogram) on top of `FieldTelemetrySummary`, passed through the allow-list and redactor.
- Suppress buckets with attempts below 5.
- Upload only through the active tunnel to `field_report.endpoint` with `cohort_token`, at most `max_reports_per_day`; drop on failure with no retry storm.
- Label each hour with the contract `network.source` (owner decision, deploy contract at `60a05950`): `sim` with the operator MCC-MNC read on the device on cellular; `server_hint` with the ASN and country from the optional bundle `field_report.network_hint_endpoint` on other networks; else `none`. The hint is fetched directly, outside the tunnel, with the bundle refresh and only while consent is on.
- Legal review through `client-legal-safety` and `legal-check` before this change is accepted.

## Capabilities

### New Capabilities

- `field-report-client`: consent, aggregation, suppression, and tunnel-only upload of field reports.

### Modified Capabilities

- None.

## Impact

- Contracts: re-vendored deploy `contract/` at `60a05950b11c` (field-report `network.source`, `client-network-hint` v1, bundle `field_report.network_hint_endpoint`).
- Kotlin: `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/FieldTelemetrySummary.kt`, `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DeveloperAnalyticsAllowListFilter.kt` and `DiagnosticsArchiveRedactor.kt`, `core/service` (scheduling beside `SharedPriorsRefreshWorker.kt`, tunnel-bound upload, `FieldReportNetworkContext` for the operator code and the network hint), `core/data/runtime-state` (bundle parsing), `app/.../subscription/SubscriptionRefreshCoordinator.kt` (hint refresh), `core/data/model/.../app_settings.proto`, `app/.../ui/screens/settings/`, all 10 locales.
- Blocked by R2 (DGN-1795085357320979, taxonomy) and R4 (DAT-1795085420746769, `server_ref`, `field_report`).
- Policy: AGENTS.md says user data leaves the device only through explicit export and no required backend exists. Opt-in upload is an owner decision that deviates from that sentence; this change must update AGENTS.md wording through its owner and keep the app fully functional with opt-in off.
- Overlap: `correct-dia*` and `connection-stage-scale` (review) change telemetry sources; `svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup` (review) adds a protocol source; `device-server-migration` (review) must not leak identifiers into reports. Rebase on each.
- Counterpart `ripdpi-vpn-deploy` changes: ingest `po4yka/ripdpi-vpn-deploy#SEC-1795086333272606` (live receiver; local tests use a fake) under epic `po4yka/ripdpi-vpn-deploy#EPC-1795085241910080`, which also tracks D1 contracts, D6 retention/alert delivery, D7 cross-repo e2e (final acceptance) and D5 reports consumption.
- Parent: EPC-1795085162356362.
