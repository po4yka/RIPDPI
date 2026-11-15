---
task_id: DGN-1794745065716076
change: repair-dia15-dpi-suite
commit_sha: b9829da49967842ed393b7a0b72eb8e391cb2bb3
local: passed
local_evidence: "170 focused core diagnostics JVM tests, 42 app JVM tests, and eight locked native ECH tests passed. AndroidTest Kotlin compilation, app and service lint, and offline source gates passed."
remote_ci: not_applicable
remote_ci_evidence: Report remote CI separately from local acceptance.
device: required
device_evidence: Packaged Android JNI and active VPN routing require device acceptance. External Telegram and ECH interoperability also remains open.
artifact: not_applicable
artifact_evidence: No release artifact is required for this source repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by the repair.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA15-TCP | DGN-1794745216921361 | Loopback HEAD train, HTTP rejection, peer close, address admission, and cancellation on one occupied IO worker | Passed on host |
| REQ-DIA15-SNI | DGN-1794745216921361 | Matched TLS baseline, HTTP authority, measured candidates, and timeout with partial evidence | Passed on host |
| REQ-DIA15-TRANSFER | DGN-1794745217875425 | Loopback HTTP payload counts, timing, independent response outcomes, cancellation, and DC dispatcher tests | Passed on host |
| REQ-DIA15-ECH | DGN-1794745218960894 | Kotlin DNS and JNI result contract tests and eight native ECH tests, including BoringSSL loopback peer checks | Passed on host |
| REQ-DIA15-SUITE | DGN-1794745219809954 | Scheduler, aggregate, Compose, and localized result tests, including the integrated DIA-14 QUIC behavior | Passed on host |
| Combined source gates | DGN-1794745220607665 | Independent JNI, cancellation, and source review, `just ci-gates`, locked Cargo metadata, AndroidTest Kotlin compilation, and lint | Passed on host |

The core test run selected the DPI suite, TCP16, SNI, Telegram, ECH, HTTPS RR, and QUIC regressions. The app run selected the DPI suite and its Russian, Spanish, and Hindi presentation tests. Both Gradle runs used `ripdpi.skipNativeBuild=true`. The native adapter was tested separately with `cargo test --offline --locked -p ripdpi-android-fetch-adapter native_ech`.

## Device and external checks still required

- Run `NativeBridgeInstrumentedTest.nativeEchRequestCancellationIsBoundToItsHandle` and run the packaged JNI handshake against a controlled ECH peer. Check negotiated acceptance, authenticated rejection, and handle release.
- In a release-equivalent build, confirm TCP16 cleartext shows as unsupported and a fixed-peer TLS run retains its peer. With an active VPN, verify which network carries Java/OkHttp and native ECH with an independent capture.
- With permission for external checks, verify the configured Telegram download, upload and DC endpoints. Run `EchReadinessProbeInstrumentedTest.cloudflareEchNegotiatesWhenNetworkTestsEnabled` with `ripdpi.runNetworkTests=1` and confirm the DNS source and peer used.
