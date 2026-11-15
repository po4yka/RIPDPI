---
task_id: ENG-1794755247754263
change: aux03-geo-integrity
commit_sha: f0c17dd24cc1d89142730d44553e75c674657b7e
local: blocked
local_evidence: Owning tests and ci-gates pass. Full staticAnalysis is blocked by one unchanged VpnTunnelRuntime LargeClass issue. The autoreview helper rejects binary fixtures before model review; independent source and JNI reviews are clear.
remote_ci: required
remote_ci_evidence: Hosted exact-commit CI has not been observed.
device: blocked
device_evidence: No emulator or physical device was started. Release APK assembly failed in the unrelated PT producer on public Go source access, so the minified JNI test could not run.
artifact: blocked
artifact_evidence: The real ARM64 android-jni ELF and its 42 JNI exports pass. APK, R8 mapping and DEX were not produced because the required PT producer could not fetch existing Go dependencies.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

Results refer to the stated code and component checks. They do not imply whole-APK or device acceptance.

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-GEO-REGEX | ENG-1794755542399344 | Geo suite 33; independent Go quoting and character-class corpora; explicit invalid-pattern errors and last-good reload | passed |
| REQ-GEO-MMDB | ENG-1794755555565647 | Geo suite 33; actual 24/28/32-bit record fixtures; complete referenced graph and UTF-8 map keys; independent Go reader control; failed load/reload tests | passed |
| REQ-GEO-ADMISSION | ENG-1794755556913013 | Mandatory native validator in DI; 29 publication/download tests and real-JNIEnv native validation tests; minified Kotlin-to-JNI invocation remains blocked | partial |
| REQ-GEO-CASE | ENG-1794755557602006 | Geo matching tests preserve explicit flags and normalized candidate behavior for both containers | passed |
| REQ-GEO-SUFFIX | ENG-1794755559424097 | Geo tests preserve leading-dot, apex, dot-only and trailing-dot cases | passed |
| REQ-GEO-PROVENANCE | ENG-1794755560643175 | Repository and publication tests cover local import, missing/changed bytes, source switch and unchanged receipt | passed |

## Observed checks

- Rust 1.100.0, Cargo `--locked`: Geo 33, proxy adapter 41, Android facade 11 and runtime Geo 3 tests pass. The runtime command selects only `runtime::geo::tests`.
- Four-package Clippy with `--all-targets -- -D warnings` and Cargo deny advisories/bans/licenses/sources pass.
- The host checks ran on `6dea9e14626097a48a063bf3f18c228dcd31a866`. Rebase input checks confirm no change to these Rust package inputs, manifests, lockfile or toolchain.
- `just ci-gates` passes on `f0c17dd24cc1d89142730d44553e75c674657b7e`. No architecture, LoC or API snapshot baseline was extended.
- On the final main base, service tests pass: repository 21, publication 7, download 1. AssetProviderViewModel 18 tests pass. Failures, errors and skipped tests are zero. This run uses Robolectric 4.18.
- `staticAnalysis --continue` plus the tests exits 1: `core/service/.../VpnTunnelRuntime.kt:58`, `LargeClass`. The file is identical to the base. Earlier PCAP findings were fixed by their upstream owner and do not recur in this final run.
- Independent final source review and the separate JNI review found no actionable issue. A further independent review covered the map-key correction and fixture provenance.
- The structured autoreview helper exits 1 before model review because it does not accept binary MMDB fixture additions. TruffleHog passes. The helper result is not a clean review, and its guard was not bypassed.

## Native artifact

The actual producer task `:core:engine:buildRustNativeLibs` passes with Rust 1.100.0, `--locked`, ABI `arm64-v8a`, profile `android-jni`, and two compiler jobs.

- Producer source: `857f9bd10dd6a0edada382fcb0d9fbcc0c4db209`.
- Native source and build configuration are byte-identical after the last main rebase. This includes `native/rust`, `build-logic`, the version catalog, `gradle.properties` and `core/engine`.
- ELF: stripped AArch64, 11,812,784 bytes.
- SHA-256: `445ead6dc02ae6ba3004984191498357275ad6ba8881d0be7f2db9c1f598a5ae`.
- Owning JNI checker: 42 exports, exact inventory match. The admission commit adds one export. The inventory was generated from an actual Android ELF, not from source names.

## Checks that could not run

Release and AndroidTest assembly stopped in `:core:engine:buildPluggableTransportAssets`, not in Geo Rust code. Existing pinned `compress` packages timed out through the Go proxy and Google Storage. An official direct fetch succeeded for one existing pin with SUMDB enabled and unchanged module files. The full official-direct continuation then timed out at `go.googlesource.com/exp` for an existing transitive pin.

No genuine local PT bundle with the required provenance manifest was found. No dependency version, stub mode, source or integrity check was changed to force the build through. APK, R8 mapping, DEX and ART results do not exist; instrumented tests executed: zero.

Remaining acceptance is the same minified `GeoDatabaseValidationInstrumentedTest` under the documented API 35 ARM64 emulator-pool lease after the genuine PT producer can complete. No physical transport, root mode, real secret, external traffic probe or four-ABI runtime acceptance was used.

## Evidence boundaries and scope

C is current implementation and reachability. V is the named observed run. S is a checked specification requirement. E is a concrete external compatibility case. Source inspection does not increase V, S or E.

Publication tests use a substitute validator to test file and cancellation state. Real Rust/JNIEnv tests check parser and bridge behavior separately. They do not prove a whole repository-to-parser Android path. The MMDB map-key case has a valid control and an independent maxminddb-golang 1.13.1 result. The C reader rejected both cases at metadata validation and is not counted as the key-type oracle.

No SRS, arbitrary GeoIP schema, attribute filtering, live file watcher or full Go regexp language was added. Unsupported expressions fail explicitly. The receipt binds source, tag and installed bytes; it does not authenticate the publisher. Display versions remain filename/length labels. Aggregate regex memory, pair-file durability and other audit hypotheses remain separate from confirmed fixes.

The historical audit is `/Users/npochaev/Downloads/RIPDPI_network_audits/AUX-03.md`. Exact commands, source/fixture hashes, separate attempts and logs are in `/Users/npochaev/Downloads/RIPDPI_network_audits/AUX-03-repair-evidence/`, including `post-mapkey-host-*`, `final-mainbase-*`, `final-mainbase-inputs.json` and `aux03-final-857f9bd10dd6-*`.
