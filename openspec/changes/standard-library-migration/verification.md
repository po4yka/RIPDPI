---
task_id: EPC-1794036260770217
change: standard-library-migration
commit_sha: null
local: passed
local_evidence: "All 15 confirmed child tasks are done. Each archived verification record has local: passed and an implementation commit on main. Git ancestry confirms the epic execution order and every declared prerequisite. No conditional WorkManager, PCAP, or QUIC varint work was added.\n\n| Task | Implementation commit | Change |\n|---|---|---|\n| DAT-1794036514216544 | `4cbb4f47f64e1150ade42c96941fdcbf19f485d0` | Decode profile URI fragments exactly once |\n| AND-1794036523065646 | `0d99822abbf6e579925e2e7e06f60330e3935778` | Parse import deep links with standard URI components |\n| AND-1794036563799026 | `d04032b88f86cbccde8fa464cbe3766a0731042e` | Parse client certificates with CertificateFactory |\n| DGN-1794036571954184 | `4d980024f2cd0ed226b2de65e528a1e830b35ebe` | Use the official OkHttp coroutine adapter |\n| RLY-1794036580419313 | `2fb2483c25d37b88e0ee40288472fef43ec8e496` | Use library constant-time SOCKS credential comparison |\n| DNS-1794036588840800 | `596ce660e8031c20c8a752dfdd1463eeb8e7b338` | Resolve native redirects and DNS endpoints with url |\n| RLY-1794036597359685 | `873d4a3457a164fef502096e62a2f7ab07b5328a` | Separate CORS sites with the Public Suffix List |\n| RLY-1794036606000501 | `61f6a62b637357a6513291741fdca58346bba3ff` | Parse relay UUIDs with the uuid crate |\n| PRX-1794036614727167 | `f5c537e1900223994cc338a10008d07c57146a8a` | Parse NaiveProxy CONNECT heads with httparse |\n| DNS-1794036623422123 | `b6957913bfd6cd9e89c0478a683489f92078d145` | Use Hickory for ECH DNS wire messages |\n| RST-1794036632883567 | `e3d4d710e961087a4b169705a6da88ecfbd59d1b` | Replace BoundedHeap with the standard BinaryHeap |\n| DGN-1794036642254618 | `ab392ea0cb52712b535d1154d6db47ad772d152c` | Persist the probe cache with AtomicFile |\n| RST-1794036651029797 | `419cf6fd66a6286e54af51f7ae7f2368f03417e6` | Use tempfile for native atomic file replacement |\n| DGN-1794036659395595 | `057140af317b62ff480471a0626d6bf3fadcad53` | Use a bounded flume queue for WorkerReaper |\n| DAT-1794036667822479 | `f1043178de18f4676d68d105a4437d4c6966a4ad` | Replace custom hex codecs with standard library functions |\n\nFinal combined local evidence: 5081 Kotlin tests (data: 967, runtime-state: 371, service: 2066, diagnostics: 1677), zero failures/errors/skips; 35 shared-priors tests; owning Clippy; staticAnalysis; locked main and fuzz metadata and cargo-deny; just ci-gates. Final native inputs also built through the arm64 Android unit-test dependency tasks. Independent review was obtained for each substantial change, with cancellation reviews where applicable. The heap step also passed full-workspace Clippy and reduced the workspace from 113 to 112 members. ECH DNS had 217 passing owner tests, property tests, and 98276 ASan fuzz executions without crashes. Each child records its own complete evidence and prior baseline failures.\n\nMeasured arm64 android-jni-dev normalized ELF changes: CORS/PSL task relay library +1218616 bytes (3.29%); tempfile task main library +33720 bytes (0.130%). These whole-task comparisons do not establish release or APK size. New PSL and tempfile production dependency choices and the CORS policy were approved by the user. Hex consolidation also removed a duplicate manifest-hash codec in the same owning crate; key and hash validation rules remain distinct.\n\nEvidence limits: Darwin source gates skip the Linux-owned runtime-platform API snapshot. The SOCKS task full-suite repeat passed serially after an existing Tor fixture shared-temp race; this is not parallel acceptance. Existing opt-in relay/proxy and monitor soak exclusions remain recorded in the owning receipts. Robolectric covers API 27/35 cache behavior and startWrite rejection, not process-kill or physical-device storage acceptance. No release build, release APK-size comparison, full ABI matrix, device run, remote CI, push, publication, or deployment was performed. Golden and quality/performance baselines were not extended.\n"
remote_ci: not_applicable
remote_ci_evidence: This scope authorizes local work and main commits; remote publication is not owned by this task.
device: not_applicable
device_evidence: No Android lifecycle, JNI, or device socket ownership change is planned. Use the child host or Robolectric gates; revisit this category if implementation expands those contracts.
artifact: not_applicable
artifact_evidence: No release APK, native binary publication, or golden blessing is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STDLIB-SCOPE | EPC-1794036499861073 | Observed local checks and independent review below | Passed |
| REQ-STDLIB-EVIDENCE | EPC-1794036499861073 | Observed local checks and independent review below | Passed |

## Observed evidence

All 15 confirmed child tasks are done. Each archived verification record has local: passed and an implementation commit on main. Git ancestry confirms the epic execution order and every declared prerequisite. No conditional WorkManager, PCAP, or QUIC varint work was added.

| Task | Implementation commit | Change |
|---|---|---|
| DAT-1794036514216544 | `4cbb4f47f64e1150ade42c96941fdcbf19f485d0` | Decode profile URI fragments exactly once |
| AND-1794036523065646 | `0d99822abbf6e579925e2e7e06f60330e3935778` | Parse import deep links with standard URI components |
| AND-1794036563799026 | `d04032b88f86cbccde8fa464cbe3766a0731042e` | Parse client certificates with CertificateFactory |
| DGN-1794036571954184 | `4d980024f2cd0ed226b2de65e528a1e830b35ebe` | Use the official OkHttp coroutine adapter |
| RLY-1794036580419313 | `2fb2483c25d37b88e0ee40288472fef43ec8e496` | Use library constant-time SOCKS credential comparison |
| DNS-1794036588840800 | `596ce660e8031c20c8a752dfdd1463eeb8e7b338` | Resolve native redirects and DNS endpoints with url |
| RLY-1794036597359685 | `873d4a3457a164fef502096e62a2f7ab07b5328a` | Separate CORS sites with the Public Suffix List |
| RLY-1794036606000501 | `61f6a62b637357a6513291741fdca58346bba3ff` | Parse relay UUIDs with the uuid crate |
| PRX-1794036614727167 | `f5c537e1900223994cc338a10008d07c57146a8a` | Parse NaiveProxy CONNECT heads with httparse |
| DNS-1794036623422123 | `b6957913bfd6cd9e89c0478a683489f92078d145` | Use Hickory for ECH DNS wire messages |
| RST-1794036632883567 | `e3d4d710e961087a4b169705a6da88ecfbd59d1b` | Replace BoundedHeap with the standard BinaryHeap |
| DGN-1794036642254618 | `ab392ea0cb52712b535d1154d6db47ad772d152c` | Persist the probe cache with AtomicFile |
| RST-1794036651029797 | `419cf6fd66a6286e54af51f7ae7f2368f03417e6` | Use tempfile for native atomic file replacement |
| DGN-1794036659395595 | `057140af317b62ff480471a0626d6bf3fadcad53` | Use a bounded flume queue for WorkerReaper |
| DAT-1794036667822479 | `f1043178de18f4676d68d105a4437d4c6966a4ad` | Replace custom hex codecs with standard library functions |

Final combined local evidence: 5081 Kotlin tests (data: 967, runtime-state: 371, service: 2066, diagnostics: 1677), zero failures/errors/skips; 35 shared-priors tests; owning Clippy; staticAnalysis; locked main and fuzz metadata and cargo-deny; just ci-gates. Final native inputs also built through the arm64 Android unit-test dependency tasks. Independent review was obtained for each substantial change, with cancellation reviews where applicable. The heap step also passed full-workspace Clippy and reduced the workspace from 113 to 112 members. ECH DNS had 217 passing owner tests, property tests, and 98276 ASan fuzz executions without crashes. Each child records its own complete evidence and prior baseline failures.

Measured arm64 android-jni-dev normalized ELF changes: CORS/PSL task relay library +1218616 bytes (3.29%); tempfile task main library +33720 bytes (0.130%). These whole-task comparisons do not establish release or APK size. New PSL and tempfile production dependency choices and the CORS policy were approved by the user. Hex consolidation also removed a duplicate manifest-hash codec in the same owning crate; key and hash validation rules remain distinct.

Evidence limits: Darwin source gates skip the Linux-owned runtime-platform API snapshot. The SOCKS task full-suite repeat passed serially after an existing Tor fixture shared-temp race; this is not parallel acceptance. Existing opt-in relay/proxy and monitor soak exclusions remain recorded in the owning receipts. Robolectric covers API 27/35 cache behavior and startWrite rejection, not process-kill or physical-device storage acceptance. No release build, release APK-size comparison, full ABI matrix, device run, remote CI, push, publication, or deployment was performed. Golden and quality/performance baselines were not extended.


The closure commit records the implementation SHA. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
