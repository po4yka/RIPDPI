---
task_id: DGN-1795085498592630
change: dgn-1795085498592630-opt-in-field-report-client
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: required
local_evidence: "Owner-authorized Gradle run on 2026-11-20 at dc1bc3d74: unit tests of :core:data, :core:data:model, :core:data:runtime-state, :core:diagnostics-data, :core:detection, :core:diagnostics, :core:service and :app (testGithubDebugUnitTest), build-logic :convention:test, :app:verifyRoborazziGithubDebug, staticAnalysis, just ci-gates and taskctl validate passed (exit 0)."
remote_ci: passed
remote_ci_evidence: "CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37."
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this task.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Gradle run (owner-authorized, 2026-11-20)

The owner authorized Gradle on 2026-11-20. At `dc1bc3d74` every gate below exited 0; logs are in `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-gradle/`.

| Gate | Log |
|---|---|
| `:core:data:model`, `:core:data:runtime-state`, `:core:data`, `:core:diagnostics-data`, `:core:detection` `testDebugUnitTest` | `test-1.log` |
| `:core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubDebugUnitTest` | `final-unit.log` |
| `-p build-logic :convention:test` | `final-convention.log` |
| `:app:verifyRoborazziGithubDebug -Pripdpi.includeRoborazziUnitTests=true --tests 'com.poyka.ripdpi.ui.screenshot.*'` | `final-roborazzi.log` |
| `staticAnalysis` | `final-static.log` |
| `just ci-gates`, `./taskctl validate` | `final-ci-gates.log`, `final-taskctl.log` |

The first run failed 17 tests, all caused by this branch; the fixes are `dd0c9402a` (field-report hour order), `d8ee5a883` (shared `RipDpiJson`), `6f877632e` (tests for the new data-transparency string and the `failureStage` probe parameter), `2a690f854` and `6a10104a9`, `9b8104b6f`, `fd0ac02c9` (lint and detekt, no baseline change). Blessed with owner authorization: `8b18b1e22` (`integrity_v12.json`, only the new `collectionStatus` field) and `dc1bc3d74` (two Roborazzi baselines: the data-transparency bullet and the onboarding chip). Rows above that say a test awaits the owner Gradle run are covered by this run.

Before the owner-authorized Gradle run above, every new and changed assertion is a Kotlin unit test, which no session compiled or ran (owner build policy: no Gradle). The device consent-flow run and the live ingest (deploy D2/D7) are not done.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795085498592630-001 | DGN-1795085529327828 | `field_report_enabled = 432` defaults off (`FieldReportConsentBackupTest`, which also shows a FULL backup restore clears it); `FieldReportUploaderTest` revoked consent sends nothing and deletes the store; `FieldReportTest` a write after revocation deletes the store; awaiting the owner Gradle run and the device consent flow | required |
| REQ-DGN-1795085498592630-002 | DGN-1795085530879631 | `FieldReportTest`: the test-only validator (now with `allOf`/`anyOf` for the source rules) accepts the vendored `golden` and `golden-sim` and rejects all 34 vendored `negative/report-*.json`; an hour of attempts builds exactly the vendored golden (`server_hint`) and the vendored `golden-sim` (`sim`) with a one-hour window and validates; a network that breaks a source rule (sim on Wi-Fi, sim with asn, server_hint without asn or with operator, none with asn or operator, an unknown source) and an hour without a recorded network build no report; the probe input's host, UUID, SNI, short id and profile/group ids are absent; app version is `major.minor`; the uploaded body validates (`FieldReportUploaderTest`); awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-003 | DGN-1795085530879631 | `FieldReportTest` (a 4-attempt bucket is absent, an all-suppressed hour builds no report) and `FieldReportUploaderTest` (no request for an all-suppressed hour); awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-004 | DGN-1795085532504090 | `FieldReportUploaderTest`: no request without the tunnel route (the hour stays pending and ages out after 24 hours), without an https target, or without a target; the poster sends `Authorization: Bearer <cohort_token>` and JSON through the local SOCKS route with no proxy fallback, no redirects and no connection retry; awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-005 | DGN-1795085532504090 | `FieldReportUploaderTest`: the cap of 2 allows 2 of 3 due hours, blocks a later hour inside 24 hours and resumes after 24 hours; a failed request drops the report and a second run sends nothing; only 202 counts as accepted; awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-007 | DGN-1795107223542717 | `FieldReportNetworkContextTest`: a cellular network is labelled `sim` with mcc `250`, mnc `01` and never fetches a hint, and an unusable operator code gives `none`; a VPN default network gives `none`/`other`; `FieldReportTest`: operator codes split into MCC and MNC with leading zeros kept and malformed codes refused; one hour on two networks keeps two entries; `FieldReportUploaderTest`: an hour on two networks sends one report per network with `server_hint` and `sim`, each valid; the operator getters need no runtime permission (see design); awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-008 | DGN-1795107223542717 | `FieldReportNetworkContextTest`: a 200 golden hint labels the network as `server_hint`; a network change during the request discards it; no request without consent, without a hint endpoint, on a VPN network, or on cellular; a non-200 or a `negative/hint-*` body gives no hint; a connection to a proxy or a loopback address counts as tunnelled; `FieldReportTest`: the hint parser accepts the vendored hint golden and discards all 6 vendored `negative/hint-*.json`, and the validator agrees; `RipdpiBundleContractTest`: `network_hint_endpoint` is parsed when present, absent is no hint, a non-https or trailing-newline value is refused; awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-009 | DGN-1795107223542717 | `FieldReportNetworkContextTest`: the hint is used until `observed_at` + `ttl_seconds`; a network change drops it and it is not reused when the earlier network returns; a second fetch waits `ttl_seconds` plus jitter; after no hint the next fetch waits one hour plus jitter; awaiting the owner Gradle run | required |
| REQ-DGN-1795085498592630-006 | DGN-1795085536438726 | `client-legal-safety` and `legal-check`: reviews below, no item `needs_local_counsel`; the owner waived local counsel on 2026-11-20. AGENTS.md carries the approved data-egress wording | pass |

## Checks run in this session

The network-source amendment's logs are under `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r3-net/`; the first pass's logs are under `runs-r3/` beside it.

No Gradle build ran (owner build policy), so no Kotlin source in this amendment was compiled or executed. Checks at `caa170dd1` plus the documentation commit:

- Re-vendor: `python3 scripts/ci/check_vendored_deploy_contracts.py --source <deploy>/contract --update 60a05950b11c`, then the check with and without `--source`: exit 0 (`revendor.log`, `check-vendored-source.log`, `check-vendored-final.log`); `python3 -m unittest scripts.tests.test_vendored_deploy_contracts`: exit 0 (`test-vendored-final.log`)
- `ktlint` on every changed Kotlin file, after `ktlint -F`: exit 0 (`ktlint-2.log`)
- A Python mirror of the test-only schema validator (`allOf`, `anyOf`, Java `$` semantics) accepted both report goldens and the hint golden and rejected all 34 `negative/report-*.json` and all 6 `negative/hint-*.json` (`validator-mirror.log`); it is not a substitute for the Kotlin test run
- `scripts/ci/export-strings-for-translation.sh`: exit 0, no key change (`export-strings.log`)
- `just ci-gates`: exit 0, including Rust API snapshots (`ci-gates.log`)
- `./taskctl validate` after `./taskctl generate-board`, and `just task-check`: exit 0 (`taskctl-validate.log`, `task-check.log`)

## Gradle gates

```bash
./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.fieldreport.*' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.FieldReportConsentBackupTest' --tests 'com.poyka.ripdpi.data.RipdpiBundleContractTest' -Pripdpi.skipNativeBuild=true
./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.AppStartupInitializerTest' --tests 'com.poyka.ripdpi.subscription.RelayPayloadMemberLatencyProbeTest' --tests 'com.poyka.ripdpi.subscription.SubscriptionMemberPersistenceTest' --tests 'com.poyka.ripdpi.subscription.*Refresh*' -Pripdpi.skipNativeBuild=true
./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :core:data:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew :app:lintGithubDebug :core:service:lintDebug
./gradlew staticAnalysis
```

The settings screen, the data transparency text, and the longer consent body changed, so the Settings Roborazzi baselines are expected to change; blessing them needs explicit approval under `golden-bless-discipline.md`. The consent flow on a device uses the emulator pool lease.

## client-legal-safety review

| Item | Classification | Basis | Product action |
|---|---|---|---|
| Upload endpoint | `sensitive` | Not shipped in the app: the subscription operator supplies `field_report.endpoint` in the bundle, and the client accepts only https. The request goes only through the active tunnel, so the underlying network sees no request to it | keep, opt-in only |
| Direct network-hint request | `sensitive` | With consent on, the app sends one GET to the operator-supplied https `network_hint_endpoint` outside the tunnel, no more often than the hint `ttl_seconds` with jitter and only on a non-cellular network. The underlying network sees a TLS connection to the subscription's report host, the same host class the subscription refresh already reaches. The source address is personal data under 152-FZ; the host uses it only in memory to look up an ASN and country, and the deploy role keeps it out of logs, metrics and storage (nginx logs only status categories; the ingest disables request logging). That obligation is the operator's, not the app's. The consent body discloses the direct request | keep, opt-in only |
| Operator code | `sensitive` | MCC-MNC is read on the device with no network request and no runtime permission, and names only the mobile operator; it is sent only in an opt-in report through the tunnel | keep, opt-in only |
| Automatic transmission | `sensitive` | An automatic upload of data about relay connections is a new client action. Under current Russian law the use of a tunnel by an individual is not an offence; liability attaches to advertising circumvention means (KoAP 14.3 part 16), to distributing information about them (149-FZ art. 15.1), and to intentional search for extremist materials (KoAP 13.53, in force from 2025-09-01). The report holds no visited site, content, IP, device, account or host identifier, so it cannot evidence any of those acts, and it travels only inside the tunnel. Buckets under 5 attempts are suppressed | keep off by default; neutral copy; revocation deletes unsent data |
| Consent copy (10 locales) | `safe` | Neutral technical wording: "report server", "tunnel", "connection counts", "operator code", "outside the tunnel"; no circumvention or advertising framing. `legal-check` on 2026-11-20 over every changed public Markdown file and string: no hard-block or soft-flag hit. The onboarding chip "No telemetry" became "No tracking" in all 10 locales, because the opt-in report made "No telemetry" untrue when the user turns it on. The agent reviewed all 10 translations on 2026-11-20 and fixed the Arabic verb agreement and one Russian phrase | keep |

Owner decision on 2026-11-20: no local counsel; this agent review is the decision record. Sources were current law commentary and press reports on KoAP 13.53, KoAP 14.3 part 16, 152-FZ art. 13.1 (processing of anonymised data from 2025-09-01) and the RKN anonymisation order No. 140 of 2025-06-19; the official texts on `publication.pravo.gov.ru` were not fetched. No item remains `needs_local_counsel`.

## AGENTS.md wording

AGENTS.md said "user data leaves the device only through explicit export". The opt-in field report is a second path. The owner approved this wording on 2026-11-20, and AGENTS.md now carries it:

> Do not add a required backend service. Product features work offline and locally; external data is bundled or fetched from static user-visible sources, and user data leaves the device only through explicit export or the opt-in anonymous field report (`field_report_enabled`, off by default; reports go only through the active tunnel, and its network-hint request goes directly to the subscription's report host).

## Open items

- The hint is fetched only with the scheduled bundle refresh (`refreshAll`), so a network joined between refreshes reports `source` `none` until the next refresh; a manual refresh does not fetch. Owner decision if a fetch on network change is wanted.
- `Network.getNetworkHandle()` identifies a network for the hint cache; Android may reuse a netId only after a long churn, which the `ttl_seconds` bound limits.
- The OkHttp hint fetcher on a real `android.net.Network` (socket and DNS binding) has no unit test; only its tunnel-detection listener and the cache rules do. A device run on Wi-Fi with the VPN on should confirm the request leaves on the underlying network.
- Failure stage: relay runtimes do not emit `lastFailureClass` today, so the native-class path of a candidate attempt is ready but idle; the typed exception and SOCKS-reply mapping (`relayProbeFailureStage`) is what names DNS, TCP and TLS stages now. A QUIC stage appears only when a runtime reports `quic_breakage`.
- Live acceptance needs deploy D2 (ingest) and D7 (cross-repo e2e).

## Review fixes

Findings of the independent review of `fd922df2b..HEAD`. Each Kotlin regression test below was written to fail before its fix; none was compiled or run (owner build policy: no Gradle).

| Finding | Fix and regression test | Result |
|---|---|---|
| B3 DNS outside the tunnel | With noDomain on, the upload used system DNS for the report host, outside the tunnel. `fieldReportTunnelRoute` skips the upload under noDomain (the listener cannot resolve names), and the poster always sends the host name to the listener. `FieldReportUploaderTest` `the report host is resolved by the tunnel listener and never by system DNS` | written, owner Gradle run pending |
| M3 upload may exit from the real address | An upload through the tunnel listener could leave from the device's own address (local desync) or through a foreign relay. The uploader sends a report only when the applied VPN configuration of the current runtime names a group or member of the target (`fieldReportUpstream`, `FieldReportTarget.isServedBy`); otherwise the hours wait. `FieldReportUploaderTest` `a report is sent only while the tunnel exits through a member of the target` and `the upstream is read only from the applied configuration of the current runtime`. Limit: when several targets exist, a due hour sent to the served target is dropped for the others | written, owner Gradle run pending |
| B4 every remote failure was `unknown`, pre-HTTP failures dropped | The typed stage now travels probe -> `CandidateHttpPayloadEvidence` -> `CandidateRelayMeasurement` -> `fieldReportAttempt`; Ready-stage failures are attempts. `RelayProbeFailureStageTest` (types and SOCKS replies, message text ignored), `CandidateRelayPayloadProbeTest` `a failed request carries the typed stage of the probe, else of the native runtime`, `FieldReportTest` `a remote failure reports its typed stage and a pre-HTTP failure is an attempt` | written, owner Gradle run pending |
| M7 amneziawg and local-desync never counted | No clean seam: AmneziaWG profiles are not relay members (separate store and runtime) and local desync has no bundle endpoint, so no record point was added. The limit is now stated in the spec Purpose and design instead of being implied. No test: documentation only | documented scope limit |
| M8 ledger cost and failure isolation | `FieldReportLedger.recordProbe` checks consent before the `network()` lookup (binder calls), counts into an in-memory state and writes it at most every `FlushIntervalMillis` (5 min) through the same temp-file, fsync and rename; `update` and `clear` write through at once. `RelayPayloadMemberLatencyProbe` contains any exception from the count. `FieldReportLedgerTest` (no network read without consent; buffered then flushed counts) and `RelayPayloadMemberLatencyProbeTest` (a failing count keeps the probe result) | written, owner Gradle run pending |
| Consent TOCTOU in the buffered ledger (security review of M8) | The consent pre-check ran before the lock, so a revocation between them could leave a count in memory that a later consent would persist and upload. `recordProbe` now re-checks consent under the lock and discards a count captured before a `clear()` (consent epoch); `clear()` also resets the flush time so the first count after a new consent writes through with its own check. `FieldReportLedgerTest` `a revocation between the consent check and the lock discards the count` | written, owner Gradle run pending |
| Minor: hint fetch on the refresh critical path | The scheduled refresh waited up to 30 s of jitter plus a 15 s timeout for the hint before publishing its result. The injected coordinator now launches the contained fetch on the application scope and returns. No new test: this is Hilt constructor wiring, which the project rule keeps without a test seam | not tested (wiring) |
| Minor: future observed_at | A hint whose `observed_at` is ahead of the device clock lived longer than its ttl. `parseNetworkHint` now counts the ttl from the earlier of `observed_at` and the receive time. `FieldReportTest` `a hint observed in the future expires one ttl after it was received` | written, owner Gradle run pending |
| Minor: consent follower flow unguarded | Only scheduling was contained, so a settings-flow error or a failing ledger clear escaped into the handler-less application scope. `follow()` now contains the flow and each consent step. `FieldReportConsentFollowerTest` `a failing settings flow stops the follower without escaping` | written, owner Gradle run pending |
| Minor: hint cache kept after revocation | The in-memory network hint survived a consent revocation until the next refresh or network change. The consent follower now drops it with the pending reports. `FieldReportConsentFollowerTest` `revoking consent drops the cached network hint` | written, owner Gradle run pending |
| Minor: poster test did not prove the SOCKS route | The poster test checked only `Proxy.NO_PROXY`. `FieldReportUploaderTest` `the poster connects only to the tunnel listener and speaks SOCKS5 there` opens the poster client's socket and asserts the SOCKS5 greeting reaches a loopback listener at the route's port. Test-only change | written, owner Gradle run pending |
| Minor: duplicate server_ref and country patterns | One `ServerRefPattern` now lives with the bundle parser (`RipdpiEndpointHealth.kt`) and the report uses it; one `CountryPattern` serves the report and the hint parser. Behavior unchanged, so existing `FieldReportTest` server_ref and hint-negative cases cover it; no new test | refactor, existing tests |

Checks for these fixes, logs under `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-final-fix/`: `ktlint` on every changed Kotlin file exit 0; `python3 -m unittest scripts.tests.test_shipped_asset_secrets` 13 tests OK; `python3 scripts/ci/check_vendored_deploy_contracts.py --source <deploy>/contract` exit 0 at deployer `ce0fc650` (no contract change since `60a05950`, so no re-vendor); `just ci-gates` exit 0. No Gradle task ran, so no Kotlin test above was compiled or run.
