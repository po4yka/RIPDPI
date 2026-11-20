# DGN-1795085498592630: Send opt-in anonymous aggregated field reports

## Objective

Opted-in users send hourly anonymous aggregated reports through the tunnel; everyone else sends nothing.

## Ownership

core/diagnostics-data/.../FieldTelemetrySummary.kt, core/diagnostics/.../export/DeveloperAnalyticsAllowListFilter.kt and DiagnosticsArchiveRedactor.kt, core/service (worker, uploader, and FieldReportNetworkContext for the SIM operator and network hint), core/data/runtime-state (bundle `network_hint_endpoint` parsing), app/.../subscription/SubscriptionRefreshCoordinator.kt (hint refresh with the bundle refresh), core/data/model/src/main/proto/app_settings.proto, app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/, locale resources. Serialized lanes: app_settings.proto, locale sets, vendored contracts.

## Execution

- [x] DGN-1795085529327828 Add the opt-in setting to app_settings.proto and the consent screen under app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/ with strings in all 10 locales #feature !high @item:DGN-1795085498592630
- [x] DGN-1795085530879631 Implement hourly aggregation from FieldTelemetrySummary into client-field-report v1 buckets with allow-list, redactor, and k>=5 suppression #feature !high @item:DGN-1795085498592630
- [x] DGN-1795085532504090 Implement the tunnel-bound uploader with cohort_token, max_reports_per_day, drop-on-failure, and a scheduled worker in core/service #feature !high @item:DGN-1795085498592630
- [x] DGN-1795085534516661 Delete pending buckets on consent revocation and add tests with a fake ingest endpoint for cap, tunnel-down, error, and empty-report cases #feature !high @item:DGN-1795085498592630
- [x] DGN-1795085536438726 Run client-legal-safety and legal-check reviews and record outcomes, and propose the AGENTS.md data-leaves-device wording to the owner #feature !high @item:DGN-1795085498592630
- [x] DGN-1795107223542717 Label each hour by network source: SIM operator MCC-MNC on cellular, the network_hint_endpoint hint on other networks, else none, and validate reports against the vendored source rules #feature !high @item:DGN-1795085498592630

## Verification

./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :core:data:testDebugUnitTest :app:testGithubDebugUnitTest; python3 scripts/ci/check_vendored_deploy_contracts.py; ./gradlew :app:lintGithubDebug :core:service:lintDebug; scripts/ci/export-strings-for-translation.sh; just ci-gates; emulator run with the pool lease for the consent flow; legal review evidence; ./taskctl validate.
