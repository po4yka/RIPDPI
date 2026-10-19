---
task_id: UIX-1792294575616838
change: latency-detail-reconnect
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LDR-DETAIL | UIX-1792300268446369 | Focused host and real APK latency detail checks | required |
| REQ-LDR-SNAPSHOT | UIX-1792300268446369 | Selected snapshot retention and reopen regression | required |
| REQ-LDR-ACCESS | UIX-1792300269982685 | Normal/reduced/off, real TalkBack, Back/focus, RTL/font2 | required |
| REQ-LDR-RECONNECT | UIX-1792300269490435 | Actual service/native session and before/after fixture traffic | required |
| REQ-LDR-VERIFY | UIX-1792300270507626 | Combined gates, APK hashes and exact published hosted CI | required |

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
