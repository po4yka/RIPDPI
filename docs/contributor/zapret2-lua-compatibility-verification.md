# zapret2 Lua compatibility verification

The root backend reuses upstream `ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b`
with Lua ABI 6. The existing non-root planner keeps ABI 5 assets at `fd4716da`.
See [backend setup](../../native/zapret2/README.md) for activation and source,
dependency, license, and socket protection contracts.

## Local checks

Observed on 2026-09-29 in the combined implementation tree:

- The eight affected Rust packages passed 546 tests. Thirteen existing tests
  are assigned to the separate relay interoperability lane; none were added
  or disabled by this change. New Lua position, detector-to-Lua, root lifecycle,
  IPC, and firewall timeout/collision tests passed.
- All-targets Clippy with `-D warnings` and Cargo formatting passed.
- Engine unit tests: 310 passed. Service unit tests: 2001 passed. No skipped
  tests. Both modules passed Detekt and ktlint. Unit tests use the repository's
  native-less test wiring; these tests do not prove APK native packaging.
- Architecture health: 23 current and 23 baseline indicators; no new,
  worsened, or stale indicator. Locked Cargo metadata passed.
- Source integrity: all six tamper/inventory checks passed. The bridge passed
  nine real SCM_RIGHTS/ACK/timeout checks under ASan and UBSan in Linux.
- The native Gradle producers built the actual JNI libraries, root helper,
  and nfqws2. The nfqws2 standalone and Gradle four-ABI outputs matched byte
  for byte. Both 64-bit ELF binaries use `0x4000` LOAD alignment and depend
  only on Android platform shared libraries.
- Linux and Android loaded all six upstream libraries. Each checked all 101
  C functions, 125 typed Linux constants, 235 named Lua functions, ABI 6
  constants, and ten upstream offline test groups. All three process checks
  passed on each platform.

Commands:

```sh
cd native/rust
cargo test --locked --offline -p ripdpi-strategy-lua -p ripdpi-strategy-registry \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-tunnel-intercept \
  -p ripdpi-proxy-runtime -p ripdpi-protocol-detect \
  -p ripdpi-root-helper -p ripdpi-root-helper-protocol
cargo clippy --locked --offline -p ripdpi-strategy-lua \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-protocol-detect \
  -p ripdpi-root-helper -p ripdpi-root-helper-protocol --all-targets -- -D warnings
cargo fmt --all --check
cd ../..
./gradlew -Pripdpi.skipNativeBuild=true :core:service:testDebugUnitTest \
  :core:engine:testDebugUnitTest :core:service:detekt :core:engine:detekt \
  :core:service:ktlintCheck :core:engine:ktlintCheck
./gradlew :core:engine:buildRustNativeLibs :core:engine:buildRustRootHelper \
  :core:engine:buildNfqws2
python3 scripts/ci/check_architecture_health.py
cargo +1.98.1 metadata --manifest-path native/rust/Cargo.toml --locked --offline
python3 native/zapret2/tests/test_source_lock.py
./taskctl validate
```

| nfqws2 ABI | Bytes | SHA256 |
|---|---:|---|
| armeabi-v7a | 333740 | `40e781d13edb7f8eda7afbe102286b802119ac9ae080aa6acbf3bea80ddbd622` |
| arm64-v8a | 534528 | `313584782bc218b5465ee918f74091ea17777bbbbb2fa7d1fcfccbe48d6df645` |
| x86 | 508080 | `ec0221dc014e7039fd00c8695759ba7e02adc6015134fc9fea8e8e93016b0426` |
| x86_64 | 515544 | `1d0a060b33d4879acc6ff0fe7782e647089a3d0a4bfbf15436565f910ce7c5e0` |

## Android NFQUEUE checks

Target: `emulator-5556`, API 37, ARM64, 16384-byte pages. The shell UID was
2000; AOSP `su 0` supplied the root helper. IPC used `su 2000` with an actual
UID 2000 owner PID. The run changed no SELinux policy and performed no global
firewall flush. The local fixture was `10.0.2.2:46171`.

Observed packet and lifecycle checks passed:

- `http_domcase` changed the Host value to `MiXeD.ExAmPlE`.
- `multisplit` emitted 2-, 21-, and 35-byte TCP payload segments with
  consecutive sequence numbers; the receiver obtained the original request.
- `fake` emitted `FAKE`, TTL 1, and sequence offset -10000. The receiver
  obtained the original request. The emulator host alias does not enforce
  router TTL expiry; this check proves header generation and TCP rejection.
- Existing empty IPv4/IPv6 chains survived activation refusal. Wrong nonce
  and protocol version were rejected before rule changes.
- Idempotent start, normal stop, child SIGKILL, invalid Lua startup,
  app-owner death, helper SIGTERM, and authenticated shutdown passed.
- Final IPv4/IPv6 rules matched the initial baseline byte for byte; queue
  bindings and helper/backend processes were absent.

Final start: 0.244 s. Stop: 0.273 s. SIGTERM cleanup and exit: 0.664 s.
The tested helper SHA256 was
`35922f3c5eda2af664e73332b94d8097d931501ffd2fd7141d2ba8056b94bb3c`.
The 3181-byte PCAP SHA256 was
`9e396e424dca7b901cb226df9a4f4c04e92844081e4934c2c8183fe788df3a73`.

Session artifacts remain at `/tmp/nfqws2-device-evidence.md`,
`/tmp/nfqws2-device-test.log`, `/tmp/nfqws2-device-ipc.json`,
`/tmp/nfqws2-device-capture.pcap`, and `/tmp/nfqws2-device-packets.json`.
The complete device driver and PCAP check are
`/tmp/nfqws2_device_test.py` and `/tmp/nfqws2_device_pcap_verify.py`.

## Remaining evidence

The shell/su domain proves the native queue, packets, and supervisor behavior.
It does not prove APK app-domain root launch or actual `VpnService.protect`.
This AOSP target rejects su from an application UID. A target that grants root
to the application is required for that gate. The C bridge handshake tests do
not replace this Android gate.

APK assembly and hosted CI evidence are recorded separately after their
observed results. The implementation task remains open until its remaining
verification is recorded. SIGKILL of the helper bypasses its cleanup handler;
see the backend setup for that explicit operational limit.
