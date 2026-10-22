---
task_id: TRN-1792673614153980
change: device-server-migration
commit_sha: fc8005cb4c67ca78996b68ed9746154ebeedcb69
local: passed
local_evidence: Integrated revision 39b0dafc passed 6462 unfiltered JVM tests with zero failures, errors or skips plus staticAnalysis and app/service lint. Subsequent generated-worker regression and complete worker suites passed 11 tests plus app lint, detekt and ktlint. Emulator-pool package identity tests passed all 34 cases.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Owned API 37 arm64 16-KiB emulator imported the real HTTPS subscription, preserved received on failure, passed a real Hysteria2 full-body request, displayed New path tested and opened the explicit Android JSON share chooser. Server verification accepted both received and path-tested receipt HMACs and exact payload digests.
artifact: passed
artifact_evidence: Native-enabled fc8005cb arm64 debug APK assembled in 4m4s; native ELF and 16-KiB APK alignment checks passed. APK SHA256 c7b7e16feb60026e73145963d0c7e4632fc0443e87166ed55b53a115f3ce8883. Actual native traffic succeeded on the installed APK.
deployment: not_applicable
deployment_evidence: This client change does not deploy or retire a server; live operator migration remains separately authorized.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MIGRATION-DELIVERY | TRN-1792674002992530 | Contract, coordinator, import, payload-limit, sealed-storage and AWG repository tests; real HTTPS import and received receipt | Passed |
| REQ-MIGRATION-TARGET | TRN-1792674004430919 | Candidate tests cover Reality, XHTTP and Hysteria2; active-AWG tests bind exact runtime configuration; real emulator Hysteria2 request completed its full body | Passed within the stated emulator scope |
| REQ-MIGRATION-FENCE | TRN-1792674004430919 | Delivery/profile/network/runtime changes, cancellation, key edits, replacement, session death and cleanup regressions | Passed |
| REQ-MIGRATION-EXPORT | TRN-1792674005207294 | Shared HMAC vectors, strict fields, ten locales, FileProvider read grants, Compose renders and actual Android share chooser; server accepted exported JSON | Passed |

## Local checks

- Integrated revision `39b0dafc` passed the unfiltered runtime-state (287), data
  (922), service (2271) and app (2982) suites: 6462 tests, zero failures, errors
  or skips. The same build-gated invocation passed `staticAnalysis`,
  `:app:lintGithubFullDebug` and `:core:service:lintDebug` in 15m34s.
  `-Pripdpi.skipNativeBuild=true` applied only to these JVM checks.
- Earlier app timeout failures were retained as failures until the combined
  gate passed. The subprocess regression now waits for real-child readiness
  before exercising the unchanged timeout and reap assertions.
- The generated Hilt worker factory regression failed before adding the missing
  AndroidX Hilt KSP processor, then passed with both complete existing worker
  suites: 11 tests, zero failures, errors or skips. App lint, detekt and ktlint
  passed in the same invocation. This follow-up did not rerun all 6462 tests.
- All 34 emulator-pool tests passed after exact SDK package identity support was
  added for the installed 16-KiB image. Existing pool slots were preserved.
- Native-enabled `assembleGithubFullDebug` passed at `fc8005cb` in 4m4s with
  verified pinned native prebuilts; their owning source/build inputs were
  unchanged from their CI source revision. `verify_native_elfs.py --abis
  arm64-v8a` and `zipalign -c -P 16 -v 4` passed.
- Task contracts, strict OpenSpec validation, architecture health with no new
  or worsened indicators, locked Cargo metadata, translation export parity,
  changed Kotlin formatting and strict harness links passed before publication.
- Companion bundle, payload and migration contract resources remain identical
  to the canonical server resources. Atomic subscription refresh journals exact
  AWG after-images and matching receipt identities after checking group ownership.

## Actual emulator interaction

The owned temporary API 37 arm64 emulator used 16-KiB pages. A test CA was
trusted only inside that disposable emulator; production trust configuration was
unchanged and SELinux remained enforcing. HTTPS import used localhost through
one ADB reverse mapping. The payload URL targeted `127.0.0.1:51432`, with no
reverse mapping for that port. Direct use of that exact URL inside the emulator
could not reach the host payload; the real Hysteria2 request reached the peer
loopback. The separate emulator host alias was not the probe URL.
The test selected the supported Firefox fingerprint through the application UI.

1. Importing the actual HTTPS response persisted one replacement profile and
   displayed `Replacement received; path not tested`.
2. Explicit `Share migration receipt` opened Android's chooser for one JSON
   file. The server codec accepted its HMAC and exact response-byte digest.
3. A failed candidate request left the received state intact and displayed that
   no tested receipt had been recorded.
4. The positive request transferred 38912 bytes through the pinned Hysteria2
   peer and changed the UI to `New path tested`. Payload SHA256:
   `a01441c8e417b004d30b946477e1dc5cd6014010f60314a16d40818e3fec873b`.
5. A second explicit export opened the real chooser. The server codec accepted
   `state=path-tested`, the exact target tag, HMAC and original bundle digest.
   Received receipt SHA256:
   `04c55af1607569f21e412f89863982b277abf8f971fbe5e1dd95de90831051b3`.
   Tested receipt SHA256:
   `fb01bb19c32e8768054229347c2fbf2d3e759285898da547707f2ab8a3bb722c`.

The final screenshot was inspected: status, explanatory copy, URL field and
all actions were readable without overlap or clipping. No chooser recipient
was selected. The emulator crash buffer contained zero fatal exceptions. The owned temporary
emulator and peer were stopped, and their test certificate and authority keys
were removed after verification; existing pool slots were preserved.
Earlier received, connect-AWG and tested Compose renders also passed inspection.

## Remaining scope

Hosted CI for the current revision and subsequent main integration remain
required. The task stays open until those results are observed. Actual emulator
traffic acceptance covers Hysteria2; Reality, XHTTP and active AWG migration
branches have automated probe coverage rather than physical-device acceptance.
No production server migration, credential rotation or physical-device result
is claimed. The feature adds no remote telemetry or implicit receipt upload.
