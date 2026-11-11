---
task_id: TRN-1794384538458949
change: repair-tun02-warp-service
commit_sha: d8059f8ba04a33cbcb39c11921f507ba374f385b
local: required
local_evidence: Frozen candidate checks passed; post-rebase checks are pending.
remote_ci: required
remote_ci_evidence: Repair commits have not been pushed.
device: required
device_evidence: v8 keypair JNI passed; guard test failed at a test-only dispatcher method. Rebased acceptance is pending.
artifact: required
artifact_evidence: Minified candidate build passed; the rebased artifact is pending.
deployment: not_applicable
deployment_evidence: This change does not own a deployment or release.
---

# Verification

Audit baseline: `ec4f13320a272b945bf2a0497d02fcea545d73c9`.
The first tested repair candidate was a dirty tree on plan commit
`48c61343889d4d7e96cfa1ce298ffef433326e07`, upstream `032bec1f`.
Its immutable v8 hashes are in `/tmp/tun02-artifact-v8-source-manifest.json`.
Nine fix commits now end at d8059f8ba04a33cbcb39c11921f507ba374f385b.
Their source matches `/tmp/tun02-frozen-source-manifest.json` after the
test-only executor and three synthetic fixture labels were updated.
Origin advanced to `fe82ae70b8`; shared WARP integration and its affected
checks must be recorded before final acceptance.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WARP-F1 | TRN-1794384922559287 | Pre-rebase readiness, timeout, expiry and shutdown passed; shared fallback integration is pending | required |
| REQ-WARP-F2 | TRN-1794384940808206 | Explicit policy, owner lease and pause races; additive legacy JNI ABI; minified run pending | required |
| REQ-WARP-F3 | TRN-1794384955046919 | Current-network selection, stale cache rejection, credential preservation and registration atomicity | passed |
| REQ-WARP-F4 | TRN-1794384974171806 | Numeric IPv4/IPv6 parser and native loopback resolution regressions | passed |
| REQ-WARP-F5 | TRN-1794384989760526 | Local license preserves server account kind; metadata and selector ownership regressions | passed |
| REQ-WARP-F6 | TRN-1794385010176922 | Loopback HTTP GET without token, POST missing token, malformed/error responses and redaction | passed |
| REQ-WARP-F7 | TRN-1794385033249122 | Invalid complete imports rejected before writes; stale endpoint replacement and superseded activation | passed |
| REQ-WARP-F8 | TRN-1794385053055273 | Facade/ViewModel/UI tests, key vectors, five previews and ten-locale gates; minified run pending | required |
| REQ-WARP-F9 | TRN-1794395789446963 | Original stack overflow reproduced; real 256 KiB worker-stack loopback regression passes | passed |

## Observed checks before the final upstream integration

- Rust 1.98.1: format, 190 package tests (175 core, 12 WARP Android,
  3 AWG Android), workspace Clippy with warnings denied, canonical workspace
  nextest and all-feature docs passed. Nextest passed 7278 tests; 66 canonical
  exclusions/ignored tests are not passes. One untouched Tor bootstrap test
  emitted a LEAK warning. Docs retain warnings outside this change.
  Exact commands and phase exits: `/tmp/tun02-after-additive-jni-native.sh`
  and `.results`; logs have the same prefix. Resolving Cargo commands use
  `--locked`, four jobs and an explicit worktree target directory.
- Miri passed the peer-proof state regression for seeds 0 through 15:
  `/tmp/tun02-miri-root-final.log`. This covers only that executed state path.
- Kotlin suites passed with no skips: engine API 75, engine 314, data 988,
  settings 85, service 2178 and app GithubFullDebug 3472. Engine-boundary
  classpath passed. Final service/app commands and XML totals:
  `/tmp/tun02-jvm-service-quality-20261111T154907Z/results.json`.
- `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug` passed
  after the latest harness edit. Lint: zero errors; Full 4100 warnings/four
  hints, Simple 4103 warnings/four hints, service 17 warnings. Those warnings
  are not all established as pre-existing. Exact commands and outcomes:
  `/tmp/tun02-jvm-harness-final-20261111T175704Z/results.json`.
- `just ci-gates` passed in `/tmp/tun02-ci-gates-quality-final.log` before
  the last two harness files and approved JNI inventory changed. Architecture
  had 18 current/baseline indicators, no new, worse or stale entries. No
  quality baseline changed. The Linux-owned platform snapshot follows its
  canonical Darwin skip. Native panic/header/unsafe source checks passed.
- Independent PR, JNI and async source reviews approved the applicable
  changes. These reviews do not prove runtime or external compatibility.
- All heavy runs use user-approved rly14, one admitted run at a time, existing
  limits: host jobs four, release native jobs two, Gradle 5 GiB/Kotlin 3 GiB.
  JVM checks skip native builds; artifact acceptance does not.

## Android and artifact evidence

Five fresh PlayFullDebug previews were rendered and inspected: idle, saved,
error, Arabic busy RTL and cancelling at font scale 1.3. No observed clipping
or overlap; shared spinner; zero preview warnings. Log:
`/tmp/tun02-preview-frozen-v4.log`; hashes in v7 result JSON. No golden blessed.

Real minified GithubFullRelease and release AndroidTest APKs were built for
arm64 with local debug signing, without production signing keys. Strict
Xray provenance passed for existing CI run 43011128768, artifact 13850473839,
base 032bec1f, AAR SHA-256
`f75735c25b6b070f7ccbd4c9b111a4d94e7b4f30c224ec20144448650a5ed7d4`.
The owning fetcher supplied pinned Naive upstream; helper preflight passed.
Receipts: `/tmp/tun02-xray-ci-retrieval.json` and
`/tmp/tun02-naive-and-helper-prerequisites-v6.json`. No workflow was started
and no dependency pin changed.

v7 ran exactly two tests, both failed before JNI because R8 removed test
entry points. This is failed harness evidence. Complete schema JSON and
narrow release-test-only member keeps fix the harness; production R8 stays
enabled. v8 ran two tests: keypair JNI passed; guard test failed before the allow path at the optimized Dispatchers.IO getter. The guard test now uses a bounded JDK executor; its fresh run is pending. Owned v8 cleanup was verified. Exact evidence: /tmp/tun02-artifact-v8-results.json. v7 DEX independently retained both native probe
descriptors, keypair JNI and the real production controller protect(I)Z,
before the new WARP test-only keeps.

Packaged WARP ELF SHA-256:
`e9bc63ebde934946ea6c1c3f38e7e191d034dd868c464877b4f70438ee69c8f7`.
The user approved exactly two additive JNI inventory entries from that ELF,
with no removal. Inventory SHA-256:
`7da81feace978f53695af2956bacc64b6445e08ab37432646f44f1e17c98eece`.
Checker passed; 12 owning host tests passed again. Receipt:
`/tmp/tun02-approved-jni-inventory-receipt.json`. A rebased ELF needs comparison.

Permanent pool slots lacked config.ini. The documented owned temporary
API 37 arm64 emulator lease was used with the exact serial throughout.
v7 owned cleanup was observed. No foreign AVD was repaired or removed.

## Failures and evidence limits

The original native probe aborted with stack overflow (101), producing F9.
A nextest attempt rejected duplicate thread arguments before tests ran.
A source-gate attempt failed at the Cargo shim; the actual pinned Cargo
repeat passed. Cancelled queue/build/render runs are not passing results.
Keep original logs; later checks supersede only the applicable earlier check.

C means source implementation; V means the exact named local or emulator
run; S covers checked requirements only. E for Cloudflare remains unknown.
No real credentials, provider registration/update/activation, organization
login, physical transport or root probe was authorized or run. Local license
storage does not prove remote WARP+ activation. Browser organization
enrollment, admin APIs, MASQUE and Tor PT remain separate scope.

A key-generation-stage Stop/selection ordering case is a test gap, not a
confirmed restart defect. A follow-up must defer key generation in a real
store test, issue Stop/another selection, release generation, then check
desired Stopped state and the defined selection contract.

## Integration

Pending: nine fix SHAs, post-rebase checks and minified JNI acceptance, main
fast-forward, remote push and exact remote CI state. Keep review state while
required remote CI evidence is unresolved; do not archive with a false pass.
