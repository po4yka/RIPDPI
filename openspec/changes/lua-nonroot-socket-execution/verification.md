---
task_id: RST-1790669115219534
change: lua-nonroot-socket-execution
commit_sha: null
local: required
local_evidence: Native and Kotlin gates passed; see the observed checks below.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Android API 35 ARM64 shell UID 2000; eight native socket tests passed.
artifact: required
artifact_evidence: Rebuilt ARM64 test ELF checksums and bundled asset checks are listed below.
deployment: not_applicable
deployment_evidence: This change has no deployment service.
---

## Evidence

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-LUA-SOCKET-001 | RST-1790669606918786 | Kotlin codec/service tests, fresh Lua extraction, TUN manifest, engine boundary | passed |
| REQ-LUA-SOCKET-002 | RST-1790669626868340 | Lua/registry/adapter/proxy tests; Android TCP/UDP writes | passed |
| REQ-LUA-SOCKET-003 | RST-1790669627775724 | Unsupported-plan fallback, partial-write accounting, failed TUN replacement | passed |
| REQ-LUA-SOCKET-004 | RST-1790669626868340 | Matcher, SOCKS logical target, 1100 sequential flows, TUN owner tests | passed |

## Observed local checks

All Cargo checks used the pinned Rust 1.98.1 toolchain and `--locked --offline`.

- `cargo test -p ripdpi-strategy-lua -p ripdpi-strategy-registry -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-desync-runtime -p ripdpi-tunnel-intercept -p ripdpi-proxy-runtime --lib`: 440 passed, zero failures or ignored tests.
- `cargo test -p ripdpi-proxy-config -p ripdpi-tunnel-config -p ripdpi-tunnel-core -p ripdpi-tunnel-android -p ripdpi-strategy-lua`: 656 passed, zero failures. Two existing tests remain ignored: startup latency smoke and a timing-sensitive hand-written QUIC relay stub. The bundled Lua asset integration tests passed.
- `cargo check --workspace --all-targets`: passed for the complete workspace.
- `cargo clippy` for the ten packages above, `--all-targets -- -D warnings`: passed.
- `cargo fmt --all -- --check` and full `cargo metadata --format-version 1`: passed.
- `./gradlew :core:engine:testDebugUnitTest :core:service:testDebugUnitTest :app:verifyEngineBoundaryClasspath -Pripdpi.skipNativeBuild=true`: passed; engine 310 tests and service 1994 tests, zero failures.
- App LuaAssetManagerTest and AppStartupInitializerTest: 17 passed. Fresh service extraction matrix: 18 passed. App/service ktlint passed.
- Architecture health: no new or worsened indicators. Native architecture and cross-language runtime contract checks: passed.
- Rust API snapshots: passed, with the Linux-owned runtime-platform snapshot skipped by the owning script on Darwin. Only the two added optional proxy context fields changed the generated API snapshot.
- The user authorized the one-line `luaSocketOwned` TUN field-manifest update. Its unblessed contract check passed. No other golden fixture changed.
- Independent implementation and contract reviews completed. Logical target retention and execution receipt findings were fixed before final validation.

## Android socket evidence

NDK 29.0.14206865 rebuilt ARM64 native test binaries for Android API 27.
The API 35 emulator ran them under shell UID 2000, without root. Five
`lua_nonroot_` proxy tests, one execution-receipt test, and two desync sender
tests passed. These exercise real TCP and UDP sockets, ordered writes, logical
SOCKS targets, fallback, flow-state release, and nonblocking partial writes.

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| proxy-runtime-tests | 31760448 | b16a619699d68fca4a659ecf8b6e4940979054b98c816223339912c3cca477ae |
| desync-runtime-tests | 2384016 | befe35efe596b91c073705445fdcb7485715dc3589f1e9e91d9a3cf2adf5f1c5 |

The binaries ran from `/data/local/tmp/ripdpi-lua-nonroot-20260929` with the
NDK libc++ runtime and a process-local TMPDIR. Local output was recorded in
`/tmp/ripdpi-lua-android-final-lua_nonroot-emulator-5554.log`,
`/tmp/ripdpi-lua-android-final-lua_socket_receipt_reaches_runtime_evidence-emulator-5554.log`,
and `/tmp/ripdpi-lua-android-final-desync-emulator-5554.log`.

The four Lua assets moved from app to core/engine without byte changes.
The service waits for installation before it builds non-root proxy preferences.

## Remaining evidence

Remote CI is pending publication. This record does not claim an APK-level
VpnService.protect callback test or API 37/16 KiB device validation. The change
uses the existing outbound socket and protection path; the Android result above
proves native socket execution only.
