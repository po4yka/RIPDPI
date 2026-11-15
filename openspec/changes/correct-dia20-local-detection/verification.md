---
task_id: DGN-1794755305945452
change: correct-dia20-local-detection
commit_sha: 98c1641d201768857206f9ccbd237a61d35c2d4f
local: passed
local_evidence: All DIA-20 cases, 90 Detection UI cases, app and service lint, Detection Detekt and source gates passed. Three unchanged ping fixture cases failed in the separate full core run.
remote_ci: required
remote_ci_evidence: Exact published main CI has not been verified.
device: not_applicable
device_evidence: Android device visibility and active tunnel acceptance were outside this repair.
artifact: not_applicable
artifact_evidence: No release package was built.
deployment: not_applicable
deployment_evidence: Git publication only.
---

# Verification

Portfolio DGN-1794755305945452. The tests use local fixtures and host JVM or Robolectric. The package and APK inspection interval is a cooperative budget between operations. A synchronous platform call can finish after the check is cancelled.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA20-TLS | DGN-1794755399805907 | TlsFingerprintCheckerTest, DetectionRecommendationsTest and StealthScoreTest verify failed and empty reads, coverage boundaries, configured labels and local-only presentation. | passed |
| REQ-DIA20-APP | DGN-1794755401380915 | VerdictNarrativeTest, VpnDumpsysParserTest, VerdictDecisionSignalsTest and StealthScoreTest verify ownership separation, prepared package hints and review-only name matches. | passed |
| REQ-DIA20-INVENTORY | DGN-1794755403886207 | InstalledVpnAppDetectorTest and VpnAppApkInspectionTest verify declared services, cancellation, partial and empty results, split provenance and the ZIP entry limit. | passed |
| REQ-DIA20-EXPORT | DGN-1794755402904296 | DetectionAppExportPrivacyTest and DetectionTlsExportTest verify app redaction and TLS status across plain, debug, JSON and Markdown reports. | passed |

## Commits

| Finding | Code commit |
|---|---|
| F1 | 413819af5 |
| F2 | 59e46dbad |
| F3 | 9274b4b8f |
| F4 | 0fa6c9bb0 |
| F5 | b2c8bc95e |
| F6 | d8eda9aac |
| F7 | 3bfa161da |
| F8 | 98c1641d2 |

## Observed checks

- The checker, inventory and export lanes passed 16, 20 and 28 focused JVM cases before integration.
- In the combined Gradle run, 336 of 340 core cases and all 90 Detection UI cases passed. Android lint for app and service and Detection Detekt passed. The four initial failures were confined to the unchanged SystemPingProberTest.
- The exact code tree passed 337 of 340 core cases on a separate run. The baseline ee25ddb7f passed all 318 core cases. A focused five-case ping run then failed one case on the candidate, a different failure set. All 22 compiled SystemPingProber and test class files were byte-identical to the baseline. The cause of the varying ping fixture outcomes is unresolved.
- An independent reviewer checked the combined result, the prepared-package attribution correction, and both the Detection Check and Diagnostics Home export paths. The Diagnostics Home archive uses its existing separate allowlist.
- build-gate -- just ci-gates passed. Locked Cargo metadata, architecture health, LoC checks, locale and translation checks, strict OpenSpec validation and repository task validation passed.
- The app and service host checks use the standard ripdpi.skipNativeBuild property. They do not establish JNI, device, browser or external network behavior.

The full app test and lint run used integration commit c535f9b45. The publish sequence has the same application and test sources.

Rebased onto e93e88121. The upstream import commit changes backup, relay codec and subscription fixtures. The DIA-20 detection code, UI, locales and owning tests have identical blobs.
