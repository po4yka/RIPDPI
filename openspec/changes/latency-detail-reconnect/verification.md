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
