## Purpose

Define consent, on-device aggregation, suppression, network labelling, and tunnel-only upload for anonymous aggregated field reports.

## ADDED Requirements

### Requirement: REQ-DGN-1795085498592630-001 — Consent is off by default and revocable

The implementation MUST keep field reporting disabled until the user enables it, MUST explain what is sent, and MUST stop aggregation and upload and discard unsent buckets when the user revokes consent.

#### Scenario: Default off

- **WHEN** the app is freshly installed
- **THEN** the setting is off and no bucket is stored

#### Scenario: Revoke discards pending data

- **WHEN** the user turns the setting off with buckets pending
- **THEN** pending buckets are deleted and no upload happens

### Requirement: REQ-DGN-1795085498592630-002 — Reports match client-field-report v1

The implementation MUST produce reports that validate against the vendored `client-field-report` v1 schema, including its `network.source` consistency rules, with window start rounded to the hour, app_version as major.minor, and no IPs, device or user IDs, subscription tokens, hostnames other than `server_ref`, or sub-hour timestamps.

#### Scenario: Report validates

- **WHEN** a report is built from a fixture hour of telemetry
- **THEN** it validates against the vendored schema and contains none of the forbidden fields

#### Scenario: Vendored fixtures

- **WHEN** the test validator runs over the vendored goldens (`golden`, `golden-sim`) and every `negative/report-*.json`
- **THEN** it accepts both goldens and rejects every negative, and the client builds no report for a network that breaks a source rule

#### Scenario: Allow-list blocks extra data

- **WHEN** the aggregation input carries a field outside the allow-list
- **THEN** the field is absent from the report

### Requirement: REQ-DGN-1795085498592630-003 — Small buckets are suppressed

The implementation MUST omit every bucket with attempts below 5 and MUST NOT upload a report with no remaining buckets.

#### Scenario: Bucket of four attempts

- **WHEN** a bucket has 4 attempts
- **THEN** it is not in the report

#### Scenario: Empty report

- **WHEN** all buckets are suppressed
- **THEN** no upload request is made

### Requirement: REQ-DGN-1795085498592630-004 — Upload only through the active tunnel

The implementation MUST send a report only while the tunnel is active, only to `field_report.endpoint` from the current bundle, with `cohort_token`, and MUST NOT send it over the underlying network.

#### Scenario: Tunnel down

- **WHEN** the tunnel is not active at upload time
- **THEN** no request is made and the report stays pending until the next hour or is dropped by age

#### Scenario: Non-https or missing endpoint

- **WHEN** the bundle has no valid `field_report.endpoint`
- **THEN** no upload occurs

### Requirement: REQ-DGN-1795085498592630-005 — Daily cap and no retry storm

The implementation MUST send at most `max_reports_per_day` reports in 24 hours and MUST drop a report after one failed attempt without immediate retry.

#### Scenario: Cap reached

- **WHEN** the count of uploads in the last 24 hours equals `max_reports_per_day`
- **THEN** further reports are not sent

#### Scenario: Server error

- **WHEN** the endpoint returns an error or times out
- **THEN** the report is dropped and the next attempt waits for the next hourly window

### Requirement: REQ-DGN-1795085498592630-007 — Each hour names its network source

The implementation MUST record each attempt in the hour of the network it was measured on and label it: cellular as `sim` with the operator MCC and MNC read on the device; another non-VPN network as `server_hint` with `asn` when a valid hint is cached for that network, else `none`; a VPN default network as `none`. Reading the operator code MUST make no network request and need no new runtime permission.

#### Scenario: Cellular

- **WHEN** an attempt is measured on a cellular default network with operator code `25001`
- **THEN** its hour reports `source` `sim`, `operator` `{mcc: "250", mnc: "01"}`, `network_type` `cellular`, and no `asn`

#### Scenario: Network changes within an hour

- **WHEN** attempts in one hour are measured on two networks
- **THEN** each network's attempts are reported separately with their own source

### Requirement: REQ-DGN-1795085498592630-008 — The network hint is fetched directly, never through the tunnel

The implementation MUST fetch the hint only while consent is on, with the scheduled bundle refresh, from an https `field_report.network_hint_endpoint`, on a non-cellular, non-VPN default network, as `GET` with `Authorization: Bearer <cohort_token>` sent directly on the underlying network. It MUST discard the hint when the request went to a proxy or loopback address or the network changed during it, and MUST treat any non-200 answer or invalid body as no hint without retry.

#### Scenario: Valid hint

- **WHEN** the endpoint answers 200 with the vendored hint golden on Wi-Fi
- **THEN** hours on that network report `source` `server_hint` with `asn` 64500 and `country` `ZZ` until `observed_at` plus `ttl_seconds`

#### Scenario: No hint

- **WHEN** the endpoint answers anything other than 200, or a body that matches a vendored `negative/hint-*.json`
- **THEN** hours report `source` `none` and no retry follows

### Requirement: REQ-DGN-1795085498592630-009 — The hint is cached per network and fetched rarely

The implementation MUST keep the hint only in memory, for the one network it was fetched on, until `observed_at` plus `ttl_seconds`, and MUST NOT fetch on the same network more often than the hint `ttl_seconds` (one hour after no hint) plus random jitter.

#### Scenario: Network change

- **WHEN** the default network changes after a hint was cached
- **THEN** the hint is dropped and is not reused when the earlier network returns

#### Scenario: Fetch interval

- **WHEN** a hint with `ttl_seconds` 21600 was fetched and the bundle refreshes again within 21600 seconds on the same network
- **THEN** no hint request is made

### Requirement: REQ-DGN-1795085498592630-006 — Legal review is recorded

The implementation MUST NOT be accepted before `client-legal-safety` and `legal-check` reviews of the consent text, endpoint handling, and data fields are recorded in the verification evidence.

#### Scenario: Review evidence present

- **WHEN** the verification record is inspected
- **THEN** it cites both reviews with their outcomes
