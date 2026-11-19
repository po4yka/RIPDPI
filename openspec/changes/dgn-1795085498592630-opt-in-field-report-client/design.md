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
- Network identity (owner decision, deploy contract at `60a05950`): the report names its `network.source`. Cellular uses the operator MCC-MNC read on the device; other networks use the server network hint; otherwise `none`. No remembered-network key, SSID, BSSID, gateway, or carrier id is sent (`network-fingerprint-privacy.md`); the operator code is the coarse MCC-MNC only.

## Implementation decisions

- Attempt source: `FieldTelemetrySummary` and its telemetry rows carry no server_ref and only latency bands, so they cannot build a v1 bucket. The source is the selector member probe (`RelayPayloadMemberLatencyProbe`), the one on-device measurement per bundle endpoint with a latency in milliseconds. The server_ref is the member tag, checked against `^[A-Za-z0-9_-]{1,64}$`. A failure before the probe's HTTP stage belongs to the local candidate runtime and is not counted. The probe does not report which remote handshake failed, so a remote failure reports `unknown`; wiring the typed native failure (`nativeFieldFailureStage`) into the candidate measurement is a follow-up.
- Allow-list and redaction: the report is a positive projection built from typed, pattern-checked values (`buildFieldReport`), so no other field of the probe input can reach it. `DeveloperAnalyticsAllowListFilter` and `DiagnosticsArchiveRedactor` project diagnostics archives and have no fields in common with the report, so they are not reused.
- Network label at record time: `FieldReportLedger.recordProbe` labels each attempt with `FieldReportNetworkContext.current()` and `FieldReportState.record` keeps one hour entry per (hour start, network), so a network change inside an hour never mixes identities. A pending file written before this change has hours without a network; they are never sent and age out in 24 hours. The app's own UID is excluded from its VPN, so `ConnectivityManager.activeNetwork` is the underlying network; a default network with `TRANSPORT_VPN` (another VPN) is labelled `none`.
- Operator code API: `TelephonyManager.getNetworkOperator()` (the registered operator, which matches the network that carries the traffic when roaming), falling back to `getSimOperator()`, on `createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())`. The Android reference lists only the telephony feature requirement for both getters and no runtime permission, so the code does not rely on the manifest's `READ_PHONE_STATE` grant; a missing feature or platform error gives no operator and `source` `none`. `simNetwork` splits the 5- or 6-digit code into a 3-digit MCC and a 2- or 3-digit MNC, keeping leading zeros. Rejected: `getSimCarrierId` or carrier names, which are not the contract identity; an IP-to-ASN lookup on the device.
- Network hint fetch: `SubscriptionRefreshCoordinator.refreshAll` (the scheduled bundle refresh) calls `FieldReportNetworkContext.refreshHint` with every stored `field_report` block; a failure never fails the refresh, and a manual refresh does not fetch. The fetch needs consent, an https `network_hint_endpoint`, and a non-cellular, non-VPN default network. `OkHttpNetworkHintFetcher` binds the socket factory and DNS to that `Network`, sets `Proxy.NO_PROXY`, drops loopback DNS answers, and discards the response when OkHttp connects through a proxy or to a loopback address (the tunnel listener). Only 200 with a body of at most 1 KiB that passes `parseNetworkHint` (every `client-network-hint` v1 rule) is a hint; 204, 400, 401, 404, 429, any other status, a timeout, or an invalid body is no hint, and there is no retry. The deploy host answers 404 for a missing or invalid token and 204 when the request reached it from its own node (a tunnelled fetch), so the client never needs to tell these apart. A hint is discarded when the default network changed during the request.
- Hint cache and schedule: one in-memory entry keyed by `Network.getNetworkHandle()`. A different handle in `current()` clears it, so a hint never survives a network change and is never persisted. It is used until `observed_at` plus `ttl_seconds`. After any fetch, the next fetch on the same network waits the hint `ttl_seconds` (one hour after no hint) plus a random jitter of up to a quarter of that interval, and each fetch starts after a random delay of up to 30 seconds, so the direct request is neither more frequent than `ttl_seconds` nor a fixed-period, bundle-aligned flow. Rejected: fetching through the tunnel (the hint would describe the relay node); a WorkManager job of its own (another regular flow); persisting the hint (it would outlive the network).
- Transport: the app UID is excluded from its own VPN, so a socket bound to the VPN network cannot carry the report. The report goes through the tunnel's local SOCKS listener (`ServiceRuntimeHandle.tunnelSocksRoute`, the onboarding validation path) with `OwnedTlsClientFactory`, no redirects and no connection retry. Without a VPN runtime nothing is sent.
- Persistence and lifecycle: pending hours and request instants live in one fsynced, atomically renamed file under `noBackupFilesDir` (at most 24 hours). Every write re-checks consent under the lock and deletes the file when consent is off. `FieldReportConsentFollower` (app startup) schedules the hourly `FieldReportWorker` while consent is on, and on revocation cancels it and deletes the file. A backup restore clears the consent.
- Cap and failure: every request counts toward `max_reports_per_day` in the rolling 24 hours, a failed request drops its report, and the worker always returns success so WorkManager never retries.

## Contracts and ownership

- Writers: single owner for `app_settings.proto`, locale resources, and the vendored contract lane (after R2 and R4).
- Rust: none unless the latency source requires a native counter.

## Risks / Trade-offs

- Re-identification through rare ASN or operator + server_ref combinations -> k >= 5 suppression and hour rounding; consider a higher k after D2 review.
- The direct hint request reveals to the underlying network a connection to the report host, and to the host the device's source address (the host maps it to an ASN and does not return or store it) -> opt-in only, disclosed in the consent copy, no more often than `ttl_seconds` with jitter.
- Opt-in rate too low for coverage -> accepted trade-off.
- Legal exposure of sending data from restrictive jurisdictions -> legal reviews are an acceptance gate.

## Migration Plan

New setting defaults off; no data migration. Rollback is reverting the change; pending buckets live only in app data. Rollout gate: R2, R4, legal evidence, and D2/D7 for live acceptance.
