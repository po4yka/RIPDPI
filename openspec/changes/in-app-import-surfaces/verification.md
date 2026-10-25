---
task_id: AND-1792945974437056
change: in-app-import-surfaces
commit_sha: null
local: passed
local_evidence: Combined gate on fix/import-review: :app:testGithubFullDebugUnitTest, :app:lintGithubFullDebug, :core:service:lintDebug, :app:detekt, :app:ktlintCheck and the core data, runtime-state, engine-api, engine and service JVM suites plus their detekt/ktlint exited 0; cargo test, clippy -D warnings and fmt --check for ripdpi-warp-core exited 0; check_architecture_health --check and check_native_hotspot_budgets exited 0 with no new indicators.
remote_ci: required
remote_ci_evidence: Hosted gates on the pushed commit are required.
device: not_applicable
device_evidence: The change is app-layer parsing and navigation covered by Robolectric tests; no device acceptance is owned by it.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMPORT-PARITY | AND-1792946024484513 | ScannerUriDispatcherTest, ClipboardImportClassifierTest, ImportHandlerActivityTest, ImportIntentFilterTest | Passed locally |
| REQ-IMPORT-SUBSCRIPTION | AND-1792946025753354 | SubscriptionLinkClassifierTest, SingBoxDeepLinkParserTest, SubscriptionInitialImportTest, SubscriptionProfileHeadersTest | Passed locally |
| REQ-IMPORT-CONF | AND-1792946025753354 | AmneziaWgProfileViewModelTest, AmneziaVpnKeyDecoderTest, QrScannerViewModelTest, ImportTextReaderTest | Passed locally |
| REQ-IMPORT-SECRETS | AND-1792946024484513 | ImportHandlerActivityTest (no key in forwarding extras), ScannerUriDispatcherTest (redacted toString), PendingProxyImportStoreTest | Passed locally |
