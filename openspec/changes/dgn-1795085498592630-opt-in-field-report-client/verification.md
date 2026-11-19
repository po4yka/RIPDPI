---
task_id: DGN-1795085498592630
change: dgn-1795085498592630-opt-in-field-report-client
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this task.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

`local_evidence` stays null until the owner runs the Gradle gates below. Every new and changed assertion is a Kotlin unit test, which no session compiled or ran (owner build policy: no Gradle). The device consent-flow run and the live ingest (deploy D2/D7) are not done.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795085498592630-001 | DGN-1795085529327828 | `field_report_enabled = 432` defaults off (`FieldReportConsentBackupTest`, which also shows a FULL backup restore clears it); `FieldReportUploaderTest` revoked consent sends nothing and deletes the store; `FieldReportTest` a write after revocation deletes the store; awaiting the owner Gradle run and the device consent flow | required |
| REQ-DGN-1795085498592630-002 | DGN-1795085530879631 | `FieldReportTest`: the test-only validator accepts the vendored golden and rejects all 19 vendored `negative/report-*.json`; an hour of attempts builds exactly the vendored golden buckets (one-hour window) and validates; the probe input's host, UUID, SNI, short id and profile/group ids are absent; app version is `major.minor`; the uploaded body validates (`FieldReportUploaderTest`); awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-003 | DGN-1795085530879631 | `FieldReportTest` (a 4-attempt bucket is absent, an all-suppressed hour builds no report) and `FieldReportUploaderTest` (no request for an all-suppressed hour); awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-004 | DGN-1795085532504090 | `FieldReportUploaderTest`: no request without the tunnel route (the hour stays pending and ages out after 24 hours), without an https target, or without a target; the poster sends `Authorization: Bearer <cohort_token>` and JSON through the local SOCKS route with no proxy fallback, no redirects and no connection retry; awaiting the owner Gradle run. Production sends nothing yet: no ASN source (see Open items) | required |
| REQ-DGN-1795085498592630-005 | DGN-1795085532504090 | `FieldReportUploaderTest`: the cap of 2 allows 2 of 3 due hours, blocks a later hour inside 24 hours and resumes after 24 hours; a failed request drops the report and a second run sends nothing; only 202 counts as accepted; awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-006 | DGN-1795085536438726 | `client-legal-safety`: review below, outcome `needs_local_counsel`, live sources not consulted. `legal-check`: not present in this checkout, recorded as a gap, not a pass | required |

## Checks run in this session

Logs are under `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r3/`.

- `ktlint` 1.8.0 on every changed Kotlin file: exit 0 (`ktlint-final.log`)
- `scripts/ci/export-strings-for-translation.sh`: exit 0, 2 keys added (`export-strings.log`)
- `just ci-gates`: every step from architecture health through the translation export passed (architecture health, shipped-asset secrets, LoC limits 0 new violations, native hotspot budgets, cross-language contracts, vendored deploy contracts, protocol fixtures, diagnostics boundary 0 violations, route coverage, localized settings options, locale parity in all 9 translated locales, translation export in sync). The last step, `check_rust_api_snapshots.py`, was stopped by the session's 20-minute timeout while it built rustdoc in a cold target directory (exit by signal 15). This change touches no Rust, so the owner can rerun that step alone (`ci-gates.log`)
- `./taskctl validate` and `just task-check`: exit 0 after `./taskctl generate-board` (`taskctl-validate.log`, `task-check.log`)
- A Python mirror of the test-only schema validator accepted the vendored golden and rejected all 19 vendored negative reports, which confirms the keyword semantics the Kotlin validator implements; it is not a substitute for the Kotlin test run.

## Gradle gates left for the owner

```bash
./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.fieldreport.*' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.FieldReportConsentBackupTest' --tests 'com.poyka.ripdpi.data.RipdpiBundleContractTest' -Pripdpi.skipNativeBuild=true
./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.AppStartupInitializerTest' --tests 'com.poyka.ripdpi.subscription.RelayPayloadMemberLatencyProbeTest' --tests 'com.poyka.ripdpi.subscription.SubscriptionMemberPersistenceTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :core:data:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew :app:lintGithubDebug :core:service:lintDebug
./gradlew staticAnalysis
```

The settings screen and the data transparency text changed, so the Settings Roborazzi baselines are expected to change; blessing them needs explicit approval under `golden-bless-discipline.md`. The consent flow on a device uses the emulator pool lease.

## client-legal-safety review

| Item | Classification | Basis | Product action |
|---|---|---|---|
| Upload endpoint | `sensitive` | Not shipped in the app: the subscription operator supplies `field_report.endpoint` in the bundle, and the client accepts only https. The request goes only through the active tunnel, so the underlying network sees no request to it | keep, opt-in only |
| Automatic transmission | `needs_local_counsel` | An automatic, unattended upload of data about relay connections is a new client action. In restrictive jurisdictions the user may be exposed by the fact of use, not by the data; the data has no IP, device, account or host identifiers and buckets under 5 attempts are suppressed | keep off by default; neutral copy; revocation deletes unsent data |
| Consent copy (10 locales) | `safe` | Neutral technical wording: "report server", "tunnel", "connection counts"; no circumvention or advertising framing | keep; translations need native review |

Live primary sources (`publication.pravo.gov.ru`, `rkn.gov.ru`) were not consulted in this session, which the skill requires before calling a workflow safe, so no item is classified `safe` on legal grounds beyond the copy wording. The owner or local counsel must confirm the `needs_local_counsel` item before acceptance.

## AGENTS.md wording for the owner

AGENTS.md says "user data leaves the device only through explicit export". The opt-in field report is a second path. This change does not edit AGENTS.md. Proposed wording for the owner to approve:

> Do not add a required backend service. Product features work offline and locally; external data is bundled or fetched from static user-visible sources, and user data leaves the device only through explicit export or the opt-in anonymous field report (`field_report_enabled`, off by default, sent only through the active tunnel).

## Open items

- ASN source: no on-device source gives the ASN without a public IP lookup, so production drops every completed hour unsent (a `shortcut:` in `FieldReportUploader`). The upload path is implemented and tested with an injected network context, but it stays inactive in production until the owner approves a source or the deploy D1 contract makes `asn` optional. Country and network type are not wired for the same reason.
- Failure stage: a remote probe failure reports `unknown`; wiring the typed native failure into `CandidateRelayMeasurement` is a follow-up.
- Translations for 9 locales were written in this session and need native-speaker review.
- Live acceptance needs deploy D2 (ingest) and D7 (cross-repo e2e).
