---
task_id: "EPC-1786264762917329"
change: "epc-1786264762917329-epic-xray-provider-mode"
commit_sha: "9b18e5122d3a9d99d1946ec2701f147aa67ad80d"
local: "passed"
local_evidence: "Observed telemetry regression RED then GREEN. Full real-AAR-linked service1918 + engine313 unit tests (2231 total), zero failures/errors/skips; full staticAnalysis passed and passed again after no-op rebase. Architecture23 existing/0new/0worsened, locked metadata114members and taskctl46/221 passed. Independent code and evidence review passed. Stable logs: /Users/po4yka/GitRep/RIPDPI-xray-provider-evidence-20260828/combined-unit-static.log and telemetry-{red,green}.xml."
remote_ci: "passed"
remote_ci_evidence: "https://github.com/po4yka/RIPDPI/actions/runs/33199013272 succeeded on 9b18e5122d3a9d99d1946ec2701f147aa67ad80d:44 successful jobs,17 intentional skips, no failures/cancellations. API27/33/35, linked Xray tests, Roborazzi, native ABIs and all debug/release variants passed; API35 real Xray TUN acceptance passed. All67 latest PR checks:50success+17skip; CodeQL and Secret Scan passed. PR455 protected rebase merge baeaf98cab8e1da646527d262fb0bc4184555269 has a byte-identical source tree to the tested commit."
device: "passed"
device_evidence: "Owned API35 ARM64 emulator: full24 lifecycle tests, no failures/errors/skips; telemetry regression exactly once. Subsequent real libXray26.3.27 TCP/REALITY and XHTTP TUN tests both passed in51.067s. Peer receipts HTTP0-to-2,DNS0-to-2 with owned.test.,direct0-to-4 before/after bad-identity sessions, no direct fallback while active. Explicit Diagnostics action asserted live typed snapshots and cached observations. Peer and owned emulator stopped. /Users/po4yka/GitRep/RIPDPI-xray-provider-evidence-20260828/lifecycle-results and xray-acceptance.json. Hosted API27/33/35 is a separate successful evidence boundary."
artifact: "passed"
artifact_evidence: "Fresh ARM64 debug app166492411bytes SHA256001fb599a7bdf4895ec821bf4ff6fb97a2851c763583d932705fd73e274d8cb2; test1908197bytes SHA256506873709a23f0b8e8196fe49057b59083775002485730db7693beaf9f749e91. Signatures and embedded BuildConfig9b18e5122 verified; six required native ELF payloads and16KiB alignment verified. Packaged gojni equals verified four-ABI CI AAR SHA256ca7b03fce7a6a447a40956435950aca4912427a6e2a2b02d545a8ac8609f8f1b. Copies: /Users/po4yka/GitRep/RIPDPI-xray-provider-evidence-20260828/verified-apks-9b18e5122; report: /Users/po4yka/GitRep/RIPDPI-xray-provider-evidence-20260828/apk-verification.json."
deployment: "not_applicable"
deployment_evidence: "No server deployment, physical-device installation, production changes, store publication or paid infrastructure is part of this change."
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-EPC-1786264762917329-001 | EPC-1786264762918691 | Real Android VPN start and distinct-UID TUN traffic through TCP/REALITY and XHTTP; both exact XrayProviderE2ETest methods pass locally and in hosted API35 acceptance. | passed |
| REQ-EPC-1786264762917329-002 | EPC-1786264762918648 | Exact-SHA parser, validator, renderer, redactor and import suites; authorized renderer and screenshot goldens; both real transports accepted by libXray26.3.27. | passed |
| REQ-EPC-1786264762917329-003 | EPC-1786264762918646 | Protect-first and DNS ownership contract tests; real distinct-UID TCP and UDP DNS through TUN/Xray with exact owned answer and peer receipts. Live MapDNS and provider-hostname bootstrap are not claimed. | passed |
| REQ-EPC-1786264762917329-004 | EPC-1786264762918997 | Live typed version/listener snapshots and explicit Diagnostics action in XrayProviderE2ETest; Home/Diagnostics/Settings presentation and durable import/restoration unit and screenshot coverage in the exact-SHA CI. | passed |
| REQ-EPC-1786264762917329-005 | EPC-1786264762918562 | Full hosted CI, linked engine/service suites, native artifacts, APK/ELF/signatures, all three Android API jobs and real Xray TUN acceptance passed. | passed |

## Evidence boundaries

The exact tested source is the commit in this record. PR455 integrated it through the normal protected rebase merge after successful checks, without bypass; the merge tree equals that tested tree. Closure records do not claim a rebuilt APK from their documentation-only commit.

The local result combines the successful full24-case lifecycle run and a subsequent successful2-case real Xray run. Between them, the local runner stopped because Gradle had removed AndroidX Test Services; installing the exact cached test utilities resolved that harness precondition. The earlier failed harness report remains preserved and is not reported as a product failure or a single uninterrupted26-case run.

Emulator and owned loopback-peer results are not physical-device, VPS, release-signing or store-publication proof. Restart means stop and fresh start, not live handover. DNS acceptance covers plain UDP; encrypted MapDNS and provider-hostname bootstrap retain contract coverage only. The Diagnostics action reads native-worker observations; it does not claim a fresh JNI or remote ping, and StatApi remains explicitly not applicable.

Earlier revisionless saved provider records require reimport; no compatibility shim is added. Historical missing-toolchain and pending-CI annotations in migrated execution descriptions are superseded by this record. Earlier temporary artifact directories were removed by external cleanup; the current verified artifacts and logs are retained at the stable paths above.
