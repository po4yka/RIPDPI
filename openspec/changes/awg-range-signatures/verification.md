---
task_id: TRN-1792776081374051
change: awg-range-signatures
commit_sha: 540fbdb9e93cf957b9042a6bad510f2f3b28bb6b
local: passed
local_evidence: Native tests, pinned upstream interoperability, 10238 JVM tests, staticAnalysis, and architecture checks passed.
remote_ci: required
remote_ci_evidence: Hosted checks must run on the integrated source revision.
device: required
device_evidence: The acceptance owner will test the final APK on the connected ARM64 device through the local USB peer path.
artifact: required
artifact_evidence: The acceptance owner must record the final APK identity and build result.
deployment: not_applicable
deployment_evidence: This client change does not deploy infrastructure.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-RANGE | TRN-1792776231660643 | Authenticated native header and cookie tests; pinned upstream traffic at both MTUs | passed |
| REQ-AWG-SIGNATURE | TRN-1792776231660643 | Bounded parser tests; independent peer observed all five tags and fresh packets in eight sessions | passed |
| REQ-AWG-CONTRACT | TRN-1792776232432318 | Complete client JVM suites; canonical producer fixtures; storage, sharing and activation tests | passed |
| REQ-AWG-SAFETY | TRN-1792776233107642 | Local native and Android source gates passed; final APK, physical acceptance and hosted checks remain open | pending |

## Local validation

The source revision above passed these checks:

- Native AWG core: 107 tests, including scanner packet order, typed errors,
  range membership, cookie authentication and signature limits.
- Native proxy configuration: 145 unit tests and 5 round-trip tests.
- Native Android bridges: 6 AWG tests and 11 WARP tests.
- Locked workspace Clippy, all targets, with warnings denied.
- Go peer helper tests.
- Independent AWG 3.1 interoperability at MTU 1280 and 1420, with four sessions
  each. The peer observed IPv4/IPv6 TCP and UDP, I1-I5 before junk and initiation,
  all five signature tags, fresh signature packets, and varied H4 values in
  both directions.
- AWG evidence Python tests: 14 passed. Fleet fixture Python tests: 28 passed.
- Seven fleet bundles regenerated from the pinned producer and matched the
  checked-in fixtures. The standalone port-hop fixture remains a parser test.
- Architecture health: 21 existing indicators, zero new or worsened indicators.
- Normal commit hooks, including task and native architecture contracts.

The complete JVM and static-analysis gate passed in 12 minutes 38 seconds.
It executed 257 tasks and reused 596 up-to-date tasks. Test reports contain
10,238 tests with zero failures, errors or skipped tests:

| Suite | Tests |
|---|---:|
| Data model | 39 |
| Data settings | 62 |
| Data runtime state | 306 |
| Data | 984 |
| Engine API | 63 |
| Engine | 317 |
| Service | 2277 |
| Full app | 3003 |
| Simple app | 3187 |

The command selected each module's complete `testDebugUnitTest` suite, both
`testGithubFullDebugUnitTest` and `testGithubSimpleDebugUnitTest`, and
`staticAnalysis`. It ran through `build-gate` with at most four Gradle workers.
The unit-test invocation used `ripdpi.skipNativeBuild=true`; native tests ran
separately. Detekt, ktlint and Android lint passed. The Simple variant used the
checked-in synthetic CI bundle as its local embedded asset.

## Remaining acceptance

The task stays in progress until the integrated source passes hosted checks
and the acceptance owner records the final APK and physical ARM64 result.
The physical scope uses a local USB peer path and no external VPN nodes.
S3 and S4 remain zero. The guard for nonzero ARM64 padding is unchanged.
