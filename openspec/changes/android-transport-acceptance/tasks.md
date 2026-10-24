# TST-1792776319490914

## Objective

Complete authenticated Android transport payload and migration transactions with real peers.

## Ownership

- This lane: app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/XrayProviderE2ETest.kt, new transport acceptance tests, TestNetworkProbeService.kt, TestNetworkProbeReceiver.kt and related test-only probe helpers.
- This lane: scripts/fixtures/xray-provider-peer/**, scripts/ci/run-android-local-acceptance.py, scripts/tests/test_android_local_acceptance.py, test-lab/acceptance Android adapter/catalog/check files and their tests, generated manifest.json, this task and change.
- Other owners: production AWG Rust/Kotlin and schema-v2 mirrors; independent AWG peer version; libXray build pins and managed artifacts. Coordinate their inputs without editing those files.
- Coordinator: integration and shared CI workflows. No commit before independent review.
- Protocol worker: automatic selector acceptance helper and core/service test-only HTTPS trust, with owned HTTPS peer routing. Security worker: exact Hysteria UDP fixture routing. Coordinator integrates shared Go/Python hunks serially after review. Android acceptance worker: bounded failure diagnostics and sole physical-device ownership.
- Security worker owns the AWG wire observer and its regression tests for the packet-counter classification defect. It preserves the existing I/J and final wire acceptance requirements. Coordinator owns the related records.

## Execution

- [ ] TST-1792776452973210 Implement nginx TLS XHTTP with positive and negative Android payload checks #feature !high @item:TST-1792776319490914
- [ ] TST-1792776459525304 Implement bounded download upload and sustained transactions for all four app VPN transports #feature !high @item:TST-1792776319490914
- [ ] TST-1792776460035152 Verify peer recovery and UDP to TCP reserve traffic with routed faults #feature !high @item:TST-1792776319490914
- [ ] TST-1792776460563922 Verify replacement server traffic before migration retirement #feature !high @item:TST-1792776319490914
- [ ] TST-1792776461086297 Run complete source artifact and authorized physical USB acceptance gates #feature !high @item:TST-1792776319490914

## Verification

- Python adapter and acceptance contract suites; pinned Go fixture tests.
- Android test compilation and staticAnalysis through build-gate with at most four workers.
- Complete the four selected USB scenarios on the authorized physical device with genuine native artifacts, separate-UID traffic, peer receipts, observed sleep/wake and cleanup.
- Generated manifest check, taskctl validate and exact-source artifact verification.
