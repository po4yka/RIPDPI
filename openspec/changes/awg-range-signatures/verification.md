---
task_id: TRN-1792776081374051
change: awg-range-signatures
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Native tests, pinned upstream interoperability, 10238 JVM tests, staticAnalysis, and architecture checks passed.
remote_ci: passed
remote_ci_evidence: Client source 90ae34b62d0345fe5685d05389f878f24c033c30 passed 56 checks with 19 planned skips and no failures. Producer source 6bd33544f85a64a05eecffe212ce74995ac15b2a passed 99 checks with one neutral result and no failures. Producer main 0462413b9332830826bef68d849600d6adcbe8b0 has the same accepted tree. Client main b3bdf4263f934c6f8f899e3ca6967010c872f516 adds only a reviewed nfqws2 build compatibility change; protocol, application and instrumentation sources are unchanged.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. AWG passed A/AAAA DNS, five payloads, peer loss and reconnect. Wire validation recorded two fresh handshakes and signature packets, all five tags and 100 H4 values in each direction.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
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

The integrated source passed hosted checks. The acceptance owner recorded the
final APK pair and the physical ARM64 result below.
The physical scope uses a local USB peer path and no external VPN nodes.
S3 and S4 remain zero. The guard for nonzero ARM64 padding is unchanged.

## Producer mirror and fixture provenance

Producer main `0462413b9332830826bef68d849600d6adcbe8b0` owns all 36
non-README contract files. The client mirror matches each file in both directions.
The hopping vector is now canonical, and the existing import test reads it from
`contract/`. The separate import-only copy was removed. This restoration passed
the complete data module suite: 959 tests with no failures, errors or skips,
plus ktlint and detekt on source `90ae34b62d0345fe5685d05389f878f24c033c30`.

Seven fleet bundles were emitted and validated from that exact clean producer
main checkout with frozen synthetic inputs. All bundle and expected-profile
bytes are unchanged. The pin and eight metadata records now use the producer
main commit. The eighth port-hop scenario retains its explicit parser-reference
status; it is not counted as emitted provenance.

The final pin passed emitter regeneration and comparison, 28 Python provenance
tests and all 13 FleetCompat JVM tests. The JVM suite had no failures, errors
or skipped tests. Task contracts and strict OpenSpec validation also passed.

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. AWG passed A/AAAA DNS, five payloads, peer loss and reconnect. Wire validation recorded two fresh handshakes and signature packets, all five tags and 100 H4 values in each direction.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
