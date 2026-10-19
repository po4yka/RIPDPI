---
task_id: UIX-1792294575616838
change: latency-detail-reconnect
commit_sha: 3892c80727e7cbaa1e06b348ea4f95123a251fe5
local: passed
local_evidence: Fresh eleven-phase candidate build and post-rebase architecture, locked Cargo metadata, static analysis, locale lint and Full/Simple golden checks passed. See candidate-build-3892c80727e7-attempt2 and post-rebase-gates-3892-attempt1 under the local artifact root.
remote_ci: passed
remote_ci_evidence: Exact published main source 3892c80727e7cbaa1e06b348ea4f95123a251fe5 passed all seven push workflows, 53 active checks and 17 expected specialized skips. CI https://github.com/po4yka/RIPDPI/actions/runs/39227195065 completed successfully on attempt 1. Independent receipt root-source-ci-3892-final-v1/summary.json SHA256 d4c812aebc8132bb3d8f274aad96d67e8f2e05427ffa141c25dcf7cc07a6307a.
device: passed
device_evidence: Matrix12 passed all 23 cases with no failures or skips. Both TalkBack modes, all six real local-network lanes, restoration and official temporary-device cleanup passed. See matrix12-device-owner-handoff.json and matrix12-independent-audit-v1/final-audit.json.
artifact: passed
artifact_evidence: Strict source/APK/DEX/native verification passed for source 3892c80727e7cbaa1e06b348ea4f95123a251fe5, with 25 fresh JUnit suites and 248 executions. See candidate-3892c80727e7-independent-verification-strict-v1/summary.json.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LDR-DETAIL | UIX-1792300268446369 | Home latency host regressions and all 13 actual latency scenarios passed | passed |
| REQ-LDR-SNAPSHOT | UIX-1792300268446369 | Selected snapshot retention, clear, disconnect and reopen tests passed | passed |
| REQ-LDR-ACCESS | UIX-1792300269982685 | Normal/reduced/off motion, real TalkBack speech and focus, Back and RTL/font2 passed | passed |
| REQ-LDR-RECONNECT | UIX-1792300269490435 | Six real local-network lanes passed with native session, command generation, DNS, payload, TUN and packet proof | passed |
| REQ-LDR-VERIFY | UIX-1792300270507626 | Combined gates, APK hashes and all seven exact published push workflows passed; CI run 39227195065 | passed |

## Final observed implementation gates

The tested implementation is `3892c80727e7cbaa1e06b348ea4f95123a251fe5`.
The sections below keep the earlier failed runs as history. The final receipts
in this section supersede their pending local, artifact and device gates.
All seven exact published push workflows completed successfully on attempt 1.
The independent GitHub API check matched all 53 active checks and 17 expected
specialized skips for the published main SHA. CI run:
https://github.com/po4yka/RIPDPI/actions/runs/39227195065.
The receipt is `root-source-ci-3892-final-v1/summary.json`, SHA256
`d4c812aebc8132bb3d8f274aad96d67e8f2e05427ffa141c25dcf7cc07a6307a`.

- The fresh eleven-phase build passed. Its strict independent verifier checked
  25 fresh XML suites and 248 executions, with no failures, errors or skips.
  It also checked 26 source files, six helpers, the two approved PNGs, 42
  consumed native files, all 89 native input files, AGP metadata and actual DEX.
- App SHA256: `156d1679eae10e6cf41dce1ad1f0dca8d6af71faf0810bee72ddc65ec91187d5`.
  Test APK SHA256: `30ccec7b4e3827549663d8c57907ab95322fcd4792daafe262379f23d6856bbb`.
  Build receipt SHA256: `4d22c0ec0a43b9244e65a8037b22b42b2bb9c44f747ee6036471f970722d8a82`.
  Strict summary SHA256: `fe2fb6eacde54845073c85ebf47decf843f6cf388221bb969eb5cfb072be3be3`.
- Native bytes came from the verified CI inputs for unchanged source `0c9a5f83`.
  This was a fresh Kotlin/APK build; it was not a new local Rust build.
- The leased API37 arm64 emulator passed 23 cases: 13 latency, six network,
  three journal guards and one genuine VPN consent case. No case was skipped.
  Normal and system-off TalkBack each read seven selected fragments and returned
  latency focus twice. Each network TalkBack mode read seven selected fragments.
- Independent packet replay passed all six lanes: 744 frames, 24 unique payload
  tokens and 24 DNS nonces. Encrypted DoT names use matching fixture DNS events
  plus actual TLS packets. Payload echoes are present in both TCP directions.
  The tests use a local fixture and make no public Internet or physical-device claim.
- Matrix10 and matrix11 exposed driver speech ordering defects. The final V5
  driver waits before scroll and before navigation acknowledgement. An exact
  owned interruption is recorded separately from playback success. It keeps all
  matcher history and waits strictly longer than the existing 12-second window.
  In the real system-off lane the audit stayed 14 requests, 13 matches and one
  unmatched interrupted request; the observed wait was 12.417 seconds. All seven
  selected fragments still had real strict success events. The matcher was unchanged.
- All six capture processes ended, all lane and outer settings were restored,
  the fixture ended, and the official pool removed the four owned temporary
  device paths. Pool and cleanup observer exits were both zero.
- Final matrix summary SHA256: `87c13591dad37e6bb71aaf06521bfd9f2dfafec123b83676c354d75d3a34989a`.
  Final execution receipt SHA256: `a403cc8e6d50023b6301c78222ab88cb9934bdff858d79dd02d5275ecc60d83f`.
  The independent final audit found no actionable defect.
- `git fetch origin` and `git rebase origin/main` left the tested source unchanged.
  The post-rebase architecture, locked Cargo metadata, static analysis, locale
  and Full/Simple golden gates passed. The clean main checkout was fast-forwarded
  to the tested source and the ordinary main push passed.

## Historical investigations

## Planned gates

Focused meaningful RED/GREEN; affected Home/health/theme host suites; unchanged
screenshot fixtures except the two user-approved Home measurement updates;
app and service locale lint; staticAnalysis; independent
code review; actual API37 leased emulator acceptance; architecture health;
locked Cargo metadata; exact published CI. Production telemetry, test-only
presentation snapshots and real reconnect network receipts remain distinct.

## Emulator environment readiness

The official pool lease completed on API 37, arm64-v8a, Google APIs image r6
(`RIPDPI_Pool_1`, `emulator-5558`). The image contains Google TalkBack and
Google TTS with a bundled English voice model. With no active network, the
selected TalkBack utterance had a matching AOSP TTS success event. The final
audit confirmed that system settings and TalkBack developer preferences matched
their initial values. The lease ended after 214 seconds with no restoration errors.

Evidence is in `prerequisite-readiness-attempt1` under the local artifact root:
`/Users/npochaev/.codex/visualizations/2026/10/17/01a148c8-a09d-7332-a66f-75ad83a10c88/ripdpi-latency-detail-reconnect`.
The key receipts are `summary.json`, `post-run-audit.json` and
`offline-voice-proof.json`. This check did not install the new app or run a
latency or reconnect acceptance scenario. The feature gates above remain open.

## Observed local gates

The unchanged production baseline failed the measured-RTT action regression.
After the reviewed UI change, the same test passed. The focused host suites
passed all 17 tests with no failures or skips: eight Home measurement tests
and nine latency detail tests. Full and Simple Kotlin app/test compilation
passed. `staticAnalysis` passed (759 tasks), as did app Full and service locale
lint, architecture health and DESIGN.md validation. Independent combined
source review found no confirmed defect.

The first Home measurement screenshot run passed six of eight cases.
After explicit user approval, the owning record task updated exactly the two
measured-RTT PNGs. Both files match the approved actual images byte for byte.
The next Full verification passed all eight cases with no failures or skips.
Simple verification stopped before test execution because the required local
embedded relay asset was absent; it is not counted as passing. The source
snapshot and all other expected PNGs stayed unchanged. Evidence is in
`golden-bless-attempt1` and `golden-root-applied.json`.

The isolated local network fixture build passed with a worktree-local target
and an unchanged staged binary. Its SHA256 is
`fac3c4801a0a92c02a9227970169fae73e22f183c736032685f6625e5643c9ea`.
The receipts are in `local-batch-attempt1` and
`fixture-build-isolated-receipt.json` under the local artifact root.

Fresh APK, feature device acceptance, and hosted CI gates remain open.

## Fixture contract preflight

Read-only tracing found an unsupported assumption in the new Proxy test.
The custom TLS root field was introduced for the VPN test-lab path; the
Proxy runtime context does not carry that field. This finding does not
establish a regression in ordinary Proxy DoT with public roots.

The acceptance correction remains test-only. Four VPN lanes retain the
HTTP DoH to verified DoT transition. Both additional Proxy lanes retain
actual HTTP DoH queries and A/B/C listener changes, runtime identity,
cancel/stale review and network assertions. Proxy custom-CA DoT is not
validated or counted as passing. Native trust and TLS validation stay unchanged.
The corrected test and driver passed independent review and 64 offline
guard checks with no failures or skips. The corrected AndroidTest files
require a fresh compile and lint before the APK build. Earlier Full and
Simple compilation results apply to the prior test version. No device result
is claimed here.

The existing reconnect dialog is a partial summary. It renders DNS mode and
protocol, provider/transport and TCP strategy, but no listener port, host or URL.
Proxy can therefore show DoH in both sections. Its UI proof must verify the
Current and Saved headings and separate scoped section identities. Known A/B/C
settings and actual native listeners provide independent port evidence; the
acceptance does not claim that the UI renders those ports.

## Simple UI test input

Simple Robolectric checks use the repository-owned structural CI fixture at
`scripts/fixtures/embedded-relay-bundle/ci-fixture.json`, as used by the
secret-less pull request setup action. Its SHA256 is
`cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c`.
The ignored asset copy is checked before and after each owning test phase.
The Home screenshot tests render their own fixed UI values and do not read
relay credentials or perform network requests. This input establishes UI
test setup only. Actual reconnect acceptance uses the separate Full APK and
local network fixture. No relay connectivity result is inferred from these
Simple host tests.

## Matrix three findings

The actual matrix at code `702ed20878f3293ab02124393cf77b53d61b880e`
passed 11 ordinary latency tests and the initial genuine TalkBack touch
exploration and speech proof. It failed during the physical dialog transition.
The failing native window snapshot was not saved before target validation;
the exact capture and refresh interleaving is not established.

Reconnect passed three ownership guards and genuine Android VPN consent.
The first VPN lane observed real initial DNS and payload traffic, but it did
not complete. The saved panel action was below the visible Home viewport.
The app also applied DNS through a legitimate same-runtime DNS refresh.
Transport listener changes remained pending. The old test incorrectly
required the initial whole applied receipt after that refresh.

The correction must preserve initial DoH, verified DoT, distinct native
listeners, exact owned receipt checks, cancellation, stale review, and the
new runtime and durable command on confirmation. Only an explicit coherent
DNS revision from the known owned settings may enter the ownership journal.
This live DNS change must not be attributed to explicit reconnect.

Both child restorations and the outer restoration reported no errors. The
pool wrapper removed only its owned temporary AVD, and the independent
observer confirmed that its files and processes were absent. Matrix three
is a failed feature acceptance run. No full network lane passed.

## Combined source and matrix five

Source `9fd8fd5a323fb38f55a03eb8d2e6c4c017311a41` includes the upstream
Compose Styles and SDK 37.1 changes. All eleven fresh local producer phases
passed. Full and Simple each passed 109 focused regressions and eight Home
measurement screenshot checks. Settings and backup checks passed fourteen
more tests. The total is 248 executions with no failures, errors or skips.
Both app/test variants compiled. Static analysis, app/service locale lint,
architecture health, locked Cargo metadata, task validation and strict
OpenSpec validation passed. An independent check verified the source,
actual APK contents, native input provenance and fresh JUnit reports.
These results apply to that exact source, before the cache correction below.

Matrix five passed eleven ordinary latency scenarios and real TalkBack with
normal motion. The TalkBack scenario recorded seven successful speech
fragments and two observed returns to the original metric. The system-off
scenario failed its strict speech proof. A pending activation hint and the
selected RTT text each contain 22 UTF-16 code units. One AOSP success event
therefore has two pending candidates. The capture has no per-utterance
cancellation proof for the hint. The matcher must keep rejecting that
ambiguity. The correction waits for positive completion of prior speech
before initial physical exploration; it does not discard pending requests.

Reconnect passed three ownership guards and genuine Android VPN consent.
The first VPN lane failed after six physical scrolls; the other five lanes
did not run. All six input traces confirm an owned collector attached to
the actual input device. Kernel events and the after-scroll PNG show that
the gesture moved Home and exposed Reconnect. The newly published native
JSON still contains the initial viewport. The test client reused a stale
accessibility node cache. The correction clears that same automation
client cache before a logical tree collection on API 34 and later, and
requires the public cache-clear call to succeed. It keeps the existing
identity, focus, arm and actual network assertions.

Both child restorations and outer restoration reported no errors. The
independent observer confirmed removal of only the owned temporary AVD.
Matrix five remains a failed feature run. Fresh compilation, APK verification
and the complete device matrix are required after the cache correction.
Evidence is under `candidate-build-9fd8fd5a323f`,
`candidate-9fd8fd5a-independent-verification-strict-v2`,
`latency-device-attempt9`, `reconnect-device-attempt7` and
`temporary-voice-matrix-attempt5` at the local artifact root above.


## Matrix six and controlled presentation clock

Source `a42e79d942d84d5c42de12c57b14a7162033a602` passed all eleven
local producer phases. Independent verification confirmed 25 fresh JUnit
suites and 248 executions with no failures, errors or skips. The actual
app and test APKs, all 26 changed app source files, two approved PNGs and
89 native inputs matched their receipts. Fourteen stale results in the
first build were rejected; the repeated owning tasks produced fresh results.

Matrix six passed eleven ordinary latency scenarios, three ownership guards
and genuine Android VPN consent. Offline and online speech readiness passed.
The TalkBack normal lane failed; system-off did not run. Prior speech had
positive unique completions, and physical exploration produced real TalkBack
actor history and matching RTT speech. The fixed measurement source then aged
past its freshness limit. The resulting Home text wrapped and moved the same
metric down by 60 pixels. The strict frozen bounds check correctly rejected
that movement. The manual presentation case now uses the existing explicit
clock input with its controlled source timestamp. Other presentation cases
and production clocks remain unchanged. Snapshot retention and reopening
assertions remain in place.

The first VPN lane passed the former scroll failure, but its driver rejected
the initial pending ReturnHome frame 62 milliseconds after cancellation.
The same saved sequence later contained the real matching Home destination.
The new external driver waits only while the exact pending flags hold.
Completion still requires no dialog, a unique active and focused Home in
another window, a matching transition proof, and the unchanged 15-second
arm budget. Independent saved-data replay accepted the real destination
and rejected identity, state and stale-arm mutations. No network lane
completed; the other five lanes did not run.

Both child restorations and outer restoration had no errors. The observer
confirmed removal of only the owned temporary AVD and its processes. Matrix
six is a failed acceptance run; the corrected source requires compilation,
fresh APK verification and another full device matrix before publication.
Evidence is in `matrix6-device-owner-handoff.json`,
`controlled-latency-clock-candidate-v1` and
`reconnect-return-home-pending-candidate-v1` under the local artifact root.


## Matrix seven and the VPN endpoint contract

Source `f75a8d2359a85c2c03cc3980296bca63403fbe2a` passed all eleven
local producer phases and independent actual APK verification. All 248 JUnit
executions in 25 suites were fresh, with no failures, errors or skips.
Architecture health, task validation and strict OpenSpec validation passed.

Matrix seven passed all thirteen latency scenarios, including real TalkBack
with normal and disabled animation. Each TalkBack lane had seven successful
speech fragments and two observed returns to the metric. Fresh normal, off
and Arabic RTL/font2 PNGs passed visual inspection. Latency restoration
reported no errors. The reconnect journal and genuine VPN consent passed.

The first VPN network lane passed physical review and cancellation, then
failed a TCP check against the configured port 42915. The native runtime
actually listened on 45903. Source tracing confirmed the existing VPN
contract: it requests port zero and private session authentication, then
publishes the allocated endpoint. DNS refresh reuses that endpoint and
credentials. The applied configuration receipt does not contain the actual
listener address. Thus the port failure is a test contract mismatch; it
does not establish that Cancel stopped the running VPN.

The correction is limited to acceptance. VPN must use an owned fresh native
listener snapshot for continuity checks and retain the real separate-UID
VPN DNS, payload and TUN-counter proofs. Proxy keeps configured A/B/C ports
and the supported SOCKS handshake. Runtime generation must remain distinct
from a possibly reused ephemeral address. No production port or session
authentication policy changes. Fresh compilation, APK verification and
another complete network acceptance remain required.

The matrix result is 17 passed, one failed, five not run and zero skipped.
All restoration levels reported no errors. The final observer confirmed
removal of only the owned temporary AVD and its processes. Evidence is in
`matrix7-device-owner-handoff.json`, `matrix7-root-visual-review.json` and
`reconnect-attempt9-readonly-triage-v1` under the local artifact root.

## Matrix eight and the capture interface

Source `ed8b78b4fa7467b76cec95a590fcaf0e3ced53aa` passed the eleven
local producer phases and independent APK verification. The 248 executions
in 25 JUnit suites were fresh, with no failures, errors or skips.

Matrix eight passed all thirteen latency scenarios, three ownership guards
and genuine VPN consent. The first VPN instrumentation test passed, but its
external packet gate failed. Thus the matrix result is 17 passed, one failed,
five not run and zero skipped. No full network lane was accepted.

Saved logs show a Wi-Fi-backed VPN on network 105 and address 10.0.2.16.
The console PCAP only contains the legacy 10.0.2.15 interface. Android
Emulator 37.2.12 uses netsim for Wi-Fi; the console capture covers legacy
cellular/WAN traffic. The required DNS and payload events occurred inside
the capture interval, and the owned file copy matched its source. The failure
was the capture interface, not a confirmed product traffic failure.

The new external driver captures only actual local IPv4 TCP on the three
bound fixture ports. It retains the console PCAP as a diagnostic and keeps
the exact packet predicate. The first local capture preflight failed after
an immediate stop: real requests finished, but the PCAP held only its header.
The corrected driver waits for all required actual bytes before stop, then
verifies the final immutable file again. It does not add traffic or construct
packets. The second preflight passed with 48 actual frames, the exact filter,
unique DoH and echo data, verified DoT traffic and no remaining owned process.
This establishes capture operation only, not product acceptance.

All matrix eight restoration levels and owned AVD cleanup passed. Evidence is
in `matrix8-device-owner-handoff.json`, `reconnect-attempt10-packet-triage-v1`,
`host-loopback-capture-candidate-v3` and
`host-loopback-capture-preflight-attempt2-execution`.

## Matrix nine and process animation-policy initialization

The same source passed all thirteen latency scenarios, three ownership guards,
genuine VPN consent and the full VPN normal-motion lane. That network lane
passed cancellation, stale review, new runtime admission, all four unique
traffic probes, the strict packet predicate and exact settings restoration.

The next VPN system-off lane failed in `setUp`, before Hilt injection,
ownership preparation or activity launch. The driver had set and read back
all three global animation scales as zero. The new process still reported
`ValueAnimator.areAnimatorsEnabled()` as true. The assertion ran before the
first window initialized the process animation cache. No network scenario
started in this lane. The recovery check then failed because no ownership
journal existed; this is a separate secondary failure.

The matrix result is 18 passed, one failed, four not run and zero skipped.
The failing lane has no successful private recovery receipt. Outer settings
restoration and the official owned AVD cleanup passed. The failed run and
its secondary recovery result remain unchanged.

The test correction keeps argument validation before setup and checks the
actual process policy after the real activity window starts, before service
dispatch. It uses bounded observation on the main thread and records the
same actual value that satisfies strict equality. It never sets the process
scale. Fresh compilation, APK verification and the remaining full network
acceptance are required after this correction. Main integration and hosted
CI are still open. Evidence is in `matrix9-device-owner-handoff.json` and
`reconnect-device-attempt11` under the local artifact root.
