---
task_id: TRN-1794384538458949
change: repair-tun02-warp-service
commit_sha: c2c69e0c04d07b74aa3b6f5f97adc281102d1be8
local: passed
local_evidence: Affected JVM suites, static analysis, lint, Rust workspace, Miri and source gates passed; exact counts and limits are below.
remote_ci: required
remote_ci_evidence: Hosted CI for the final main integration has not been observed.
device: passed
device_evidence: API 37 arm64 4 KiB emulator ran exactly two minified JNI tests, both passed; owned cleanup was observed.
artifact: passed
artifact_evidence: Native-on minified app and test APK build, signatures, production DEX descriptors and approved fourteen-symbol ELF inventory all passed on code commit c2c69e0c.
deployment: not_applicable
deployment_evidence: This change does not own a deployment or release.
---

# Verification

Audit baseline: ec4f13320a272b945bf2a0497d02fcea545d73c9.
The checked code commit is c2c69e0c04d07b74aa3b6f5f97adc281102d1be8,
based on fe82ae70b8dbe73eb7e94ebbca9f350dcf82dd92. The clean source has
106 changed-file hashes in /tmp/tun02-final-code-manifest.json. Source stayed
frozen through the final gates and artifact run. Evidence-only descendants
must not be described as the APK build commit.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WARP-F1 | TRN-1794384922559287 | Authenticated readiness, expiry, bounded stop, real IPv4-to-IPv6 fallback; native 206 and workspace gates | passed |
| REQ-WARP-F2 | TRN-1794384940808206 | Inactive/required policy, owner leases, old ABI and request-local JNI controller; v9 minified JNI 2/2 passed | passed |
| REQ-WARP-F3 | TRN-1794384955046919 | Current-network selection, credential snapshots, stale cache and real-store mutation races | passed |
| REQ-WARP-F4 | TRN-1794384974171806 | Numeric/socket forms, service port, both-family resolver and invalid input regressions | passed |
| REQ-WARP-F5 | TRN-1794384989760526 | Local license preserves account kind; server metadata refresh and selector ownership regressions | passed |
| REQ-WARP-F6 | TRN-1794385010176922 | Decoded GET/POST results and redacted error chains; recording bindings are not an HTTP wire oracle | passed |
| REQ-WARP-F7 | TRN-1794385033249122 | Complete imports before writes, replacement endpoints, superseded rejection, no consumer refresh | passed |
| REQ-WARP-F8 | TRN-1794385053055273 | Setup facade, ViewModel, five previews, ten locales and key vectors; v9 minified JNI 2/2 passed | passed |
| REQ-WARP-F9 | TRN-1794395789446963 | Real 256 KiB worker-stack loopback regression, unchanged packet limit | passed |
| REQ-WARP-F10 | TRN-1794424694799487 | Candidate pool and selected IPv6 endpoint regressions: two RED failures, ten GREEN passes | passed |

## Observed local checks

- Focused native format and three-package gate: 206 tests passed, no skips:
  WARP core 191, WARP Android 12, AmneziaWG Android 3. The real four-second
  fallback regression failed (101); native 12 seconds/API 25 seconds passed.
  Logs: /tmp/tun02-rebase-fallback-red4s.log and
  /tmp/tun02-rebase-native-green12s.log. Source handoff and nine fix SHAs:
  /tmp/tun02-rebase-source-release.json.
- Pinned Rust 1.98.1 workspace format, Clippy with warnings denied, canonical
  nextest (7345 passes, 66 canonical skips) and all-feature docs passed.
  The untouched Tor bootstrap retry test emitted one LEAK warning. Docs
  retain warnings; this is not a warning-free claim.
- Miri passed the selected peer-proof state test for seeds 0 through 15.
  This covers only the executed Rust path, not JNI or protocol conformance.
  Exact native commands, filter and exits:
  /tmp/tun02-shared-integration-native.sh and .results. Rust, lockfile and
  engine API inputs did not change in F10 or the later test harness commit.
- Final service 2183 and app GithubFullDebug 3474 tests passed, with no
  failures, errors or skips. Engine boundary, ktlint, staticAnalysis and
  app/service lint passed. Earlier unchanged-module results remain valid:
  engine API 76, engine 314, data 989; settings 85 was UP-TO-DATE, not rerun.
  Exact commands, fresh/reused XML and exits:
  /tmp/tun02-jvm-f10-final-20261111T192937Z/results.json and
  /tmp/tun02-jvm-shared-integration-20261111T190315Z/results.json.
- Lint has zero errors. Full: 4100 warnings/four hints; Simple: 4103/four;
  service: 17 warnings. These warnings are not all established as pre-existing.
- just ci-gates, the required pre-integration architecture command, locked
  metadata and just task-check passed. Exact commands and exits:
  /tmp/tun02-final-source-gates.sh and .results. The Linux-owned runtime
  platform snapshot follows its canonical Darwin skip. No quality baseline
  was changed. JNI inventory has only the two explicit user-approved adds.
- F10 has two observed RED failures and ten GREEN passes in its owning
  scanner classes; independent review and normal hooks passed. Receipt:
  /tmp/tun02-f10-receipt.json. Its fake health response proves selection
  behavior only; it is not a wire compatibility oracle.
- Independent PR, async and JNI source reviews approved the shared rebase.
  The test helper's cancel-safety rustdoc was corrected. Reviews do not raise
  device, specification or external compatibility evidence.

## Android and artifact evidence

v9 built the real native-on minified GithubFullRelease and release AndroidTest
APKs on the clean checked code commit. Exactly two JNI tests passed, with no
failure, error or skip. APK bytes stayed identical through the device run.
Fresh DEX retains old/new probe and keypair descriptors plus the nonempty
production scoped controller protect(I)Z method. The new packaged ELF matches
the explicitly approved fourteen-symbol inventory, checker exit 0.

- App APK SHA-256: 370b309bc1518696f7cc72d8abc5d5fe889d7f945d792e095230313810cb00fe
- Test APK SHA-256: b9c8359699f853de5592191ef3c31536e5a0fec239d025fe093d82c17ece7758
- WARP ELF SHA-256: d70ce86fae13604baa94e0db232d4e98a7f1d917ba4edc54d70744ebff6dc9c7
- Inventory SHA-256: 7da81feace978f53695af2956bacc64b6445e08ab37432646f44f1e17c98eece

The API 37 arm64 4 KiB emulator was owned under a temporary pool lease;
its AVD, ini, metadata and emulator process were removed after the test.
The APKs use local debug signing, not production signing keys. Xray and
Naive prerequired artifacts passed their source/build provenance checks.
No dependency pin was changed by this repair. Before/after manifests match
all 106 files and show a clean checkout, without documentation drift.
Exact commands, tool versions, signatures and cleanup:
/tmp/tun02-artifact-v9-results.json, /tmp/tun02-release-jni-frozen-v9.log,
/tmp/tun02-release-jni-device-v9.log and
/tmp/tun02-artifact-v9-source-after.json. This is one-ABI loopback/JNI V;
it does not prove physical VPN routing or external provider E.

Historical v7 ran two tests and failed both before JNI because R8 removed
separate-test entry points. v8 passed keypair JNI and failed the probe test
at an optimized Dispatchers.IO getter before its allow branch. The current
harness uses a bounded JDK executor and platform array assertions. It keeps
R8 enabled and adds no broad production keep. Original failed receipts remain
/tmp/tun02-artifact-v7-results.json and /tmp/tun02-artifact-v8-results.json.

Five unchanged WARP previews were rendered and inspected: idle, saved,
error, Arabic busy RTL and cancelling at font scale 1.3. No observed overlap
or clipping; zero preview warnings. /tmp/tun02-preview-frozen-v4.log records
this V-render check. No golden was blessed.

All heavy runs use user-approved rly14, one admitted run at a time. Limits
remain host jobs four, release native jobs two, Gradle 5 GiB/Kotlin 3 GiB.
JVM tests skip native builds; final artifact acceptance builds native code.
Temporary emulator ownership and exact serial must be recorded. No foreign
AVD is repaired or removed, and no physical device or root is used.

## Evidence limits and remaining checks

C is source implementation. V is the exact named local/emulator run. S
covers only checked requirements; the private API has no complete public
normative specification. E for actual Cloudflare, WARP+ and Zero Trust
remains unestablished. Local license storage is not remote paid activation.

No real provider POST/GET, license activation, organization login, real
credentials, physical VPN handover or root probe was authorized or run.
Provider acceptance needs separate authorization and an exact account:
registration, token-preserving refresh, authenticated tunnel, matched TCP/UDP
through Android TUN, current-network reconnect and bounded Stop. Browser or
admin enrollment, MASQUE and Tor PT remain separate scope.

The key-generation-stage Stop/selection ordering case remains a test gap,
not a confirmed restart defect. The follow-up must defer key generation in
a real-store test, issue Stop or another selection, release generation, then
check desired Stopped state and the defined selection contract.

The task remains in review while required hosted CI evidence is unresolved.
Do not archive or close it with a fabricated remote pass. Main/push facts
belong in the final external audit report after the actions are observed.

## Fix commits

| Finding | Commit |
|---|---|
| F1 | 97f2d8403c2bb3e09d1cf5835a4470a73c85cca4 |
| F2 | 5b54bd7628b1f83a6e55a083b907a340f65ee2a8 |
| F3 | 7b93214e65ee985c48412dcda9da3dc3f9603dac |
| F4 | 8631ea135960c30d6d1667d247460d39ae42bbd9 |
| F5 | e4db3d8c767edec965945c4445cfcfeef42a65b0 |
| F6 | 19c528e4e911fc47e38f34ad4d7b0ad635d5253f |
| F7 | aebc9bd4173c93b7250459921a202979cb213110 |
| F8 | 634714e818fd1a48bea6d57c4a09fb349c255e7e |
| F9 | f8f662e05ef7ae34771b7c1037c88dcb39f3130e |
| F10 | df3711cd7b2511ffb5629bb6f2a4bcd81a0241aa |

Shared fallback integration: 48ccad549f19e63e99d757ad4f734a170061afc2.
The later c2c69e0c test-only commit keeps minified probe assertions on platform APIs.
