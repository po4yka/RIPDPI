---
task_id: "EPC-1786264762917329"
change: "epc-1786264762917329-epic-xray-provider-mode"
commit_sha: "0f6124c663b5bbcedb4514eb5ea631d12e958f58"
local: "passed"
local_evidence: "6227 unit tests, zero failures/errors/skips; full staticAnalysis; four authorized PNG goldens and renderer expectations; architecture 23 existing/0 new/0 worsened; native hotspot budgets and locked Cargo metadata passed. Go fixture full suite repeated three times after opcode RED-to-GREEN. Logs: /private/tmp/ripdpi-xray-rebased-{units,static,goldens}.log, /private/tmp/ripdpi-xray-dns-peer-tests.log; final Android ktlint/detekt/build: /private/tmp/ripdpi-xray-dns-android-build.log."
remote_ci: "required"
remote_ci_evidence: "User authorized epic PR publication and ordinary protected merge after successful checks. Exact-SHA hosted CI remains pending; earlier June-audit CI is not evidence for this epic."
device: "passed"
device_evidence: "Owned emulator-5570, Android API35 ARM64; real VpnService, Keystore, libXray 26.3.27 and TUN, independent loopback peer. Exact two E2E methods passed with zero skips in 53.049s. Receipts: HTTP 2, DNS 2 (owned.test.), direct sentinels 4 before/after VPN, no direct fallback with bad identity. Log and JSON: /private/tmp/ripdpi-xray-provider-artifacts-0f6124c66/ripdpi-xray-dns-emulator{.log,-evidence.json}."
artifact: "passed"
artifact_evidence: "ARM64 debug APK 937877125 bytes, SHA256 c6e1d457a9b6d812a2d29460dfe13cb6b5f77c26442037e3455161bc8d8e736d; test APK SHA256 d4525b774875485c1b0ca9a5989bdd59360c91d82612ee4f3ec472ecb8f852e9. Both signatures verified; packaged five RIPDPI ELF gates passed and Go JNI LOAD alignment >=16KiB. Stable copies and verification.json: /private/tmp/ripdpi-xray-provider-artifacts-0f6124c66/."
deployment: "not_applicable"
deployment_evidence: "No server, production, physical-device, or store publication action is owned by this change."
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-EPC-1786264762917329-001 | EPC-1786264762918691 | Real Android VPN start, distinct-UID TUN traffic and stop/start for TCP/REALITY and XHTTP; final E2E log above. | passed |
| REQ-EPC-1786264762917329-002 | EPC-1786264762918648 | Typed URI/JSON parser, validator, renderer and redactor unit suites; authorized renderer goldens; both real transports accepted by libXray. | passed |
| REQ-EPC-1786264762917329-003 | EPC-1786264762918646 | Protect-first and DNS ownership contract tests; real TCP and UDP DNS through TUN/Xray, exact owned DNS answer and receipts. Encrypted MapDNS and upstream hostname bootstrap are not claimed as live-tested here. | passed |
| REQ-EPC-1786264762917329-004 | EPC-1786264762918997 | Live typed snapshot assertions in E2E; Home/Diagnostics/Settings presentation and stale-probe unit coverage; manual import validation, rejection, save and process-restart restoration on emulator. | passed |
| REQ-EPC-1786264762917329-005 | EPC-1786264762918562 | Full local module suites, static analysis, APK/ELF/signature checks and exact two real Android smoke tests. Hosted CI remains pending separately. | passed |

## Evidence boundaries

The epic is not closed or archived. Required hosted CI and protected integration remain outstanding; execution checkboxes stay open until that acceptance step. Old toolchain-blocker annotations in migrated planning records are historical and superseded by the observations above.

The device result is an owned API35 ARM64 emulator result, not physical-device, VPS, release-signing or store proof. Positive restart means stop then fresh start, not an on-device live handover. Encrypted MapDNS and provider hostname bootstrap retain their existing contract coverage; this run exercises plain UDP DNS to the owned peer through both Xray transports.

The manual UI checks used a synthetic TEST-NET profile without starting it: empty/invalid input was rejected, valid input enabled confirmation, and the selected provider/profile survived process restart without redisplaying the raw credential string. Screenshots: `/private/tmp/ripdpi-xray-import-ui-{selected,error-scrolled,valid,restored}.png`. Previously saved revisionless provider records require reimport; no compatibility shim is added.
