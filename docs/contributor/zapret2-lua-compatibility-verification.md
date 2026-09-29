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

The implementation task remains open until its root application and socket
transfer contracts pass on a compatible target. SIGKILL of the helper bypasses
its cleanup handler; see the backend setup for that explicit operational limit.

## APK and JNI checks

The actual `:app:assembleGithubFullDebug` build passed in 4 min 19 s on
`2c1697db3bc95ea0df3fe4ded925c64db8aac1b7`. Native build tasks were enabled.
Its managed Xray AAR came from the exact same commit's CI producer. The owning
`verify-libxray-artifacts.sh --release` passed API, provenance, hash, four-ABI,
and ELF alignment checks. AAR SHA256:
`e0fed5714cb8d38e1b305bfc256d88d5d0229ab65418a5a0d688bfe8ce5a8df4`.

The 713155477-byte ARM64 debug APK passed `apksigner` verification. SHA256:
`2c7099d4d12df8f27b75b5ad8a453563031e357a3cd56fd2029cbc59c49f70aa`.
Its nfqws2 matches the four-ABI producer's ARM64 binary. All six Lua files,
14 license files, and source manifest match the source lock byte for byte.
The five Rust JNI libraries and actual Go JNI library are present. The packaged
root helper SHA256 is
`b8396f3ce2d52ffc3ccff158514adc1c12ed50711a1e3933813204f1d0362eb6`.

The actual app and AndroidTest APKs installed on API 37. All eight maintained
`NativeBridgeInstrumentedTest` cases passed. Package data was not cleared.
The root smoke's stale newline JSON transport was also changed to the bounded
big-endian length frame. Its AndroidTest APK compiled successfully; its root
acceptance body still requires app-granting root.

The broad `assembleDebug` command failed because the Simple variant requires
the absent user relay bundle `app/src/simple/assets/embedded-relay-bundle.json`.
No substitute bundle was created. Standard local debug policy uses optional
pluggable-transport stub launchers. Those launchers do not prove production PT
packaging; nfqws2, the helper, Rust JNI, and Xray in this APK are actual builds.

Artifacts: `/tmp/nfqws2-final-app-github-full-debug.apk`,
`/tmp/ripdpi-nfqws2-githubFullDebug.log`,
`/tmp/ripdpi-nfqws2-combined-assembleDebug.log`,
`/tmp/ripdpi-nfqws2-apk-inspection.log`,
`/tmp/ripdpi-nfqws2-apk-signature.log`,
`/tmp/ripdpi-nfqws2-apk-elf.log`, and
`/tmp/ripdpi-nfqws2-nativeBridge-instrumentation.log`.

## Actual VPN socket transfer

The ordinary app UI and actual Android consent dialog started
`RipDpiVpnService`. TUN and the app's protection socket were active. The exact
APK C executable ran through shell root with the actual app UID, 10233.
Raw-socket initialization failed before Lua or raw sends: the app receiver
reported truncated ancillary data (`MSG_CTRUNC`, `Message too long`), and the
C bridge received connection reset and exited 1. The fixture received no
payload; the capture contains only its 24-byte PCAP header.

Two independent native probes used the same UID with `su` and `runas_app`
SELinux domains. Both failed. Read-only policy checks allowed `fd:use` but
denied UDP/raw-IP socket read/write from those domains to `untrusted_app`.
The corresponding own-app domain checks passed. This is a measured AOSP
socket-transfer constraint. Root launch permission alone does not establish
this contract. No SELinux policy, enforcing mode, root grant, or appops entry
was changed. The test VPN stopped through its UI; no queue, child, or capture
process remains.

The successful C-to-`VpnService.protect` runtime gate remains unresolved.
This test does not prove the app-domain root launcher. Full evidence and
runnable drivers are `/tmp/nfqws2-real-vpn-protect/evidence.md`,
`/tmp/nfqws2_real_vpn_protect.py`, and `/tmp/nfqws2_vpn_ui.py`.

## Native size accounting

CI checks only the proxy and tunnel libraries across four ABIs for this size
budget. nfqws2, the root helper, relay, WARP, and AmneziaWG are outside that
size budget. The original baseline total was 76094860 bytes. The published
`617008b14` tree already measured 76636228 bytes, above its 76357004-byte
allowance. The compatibility tree measures 76768004 bytes: a further 131776
bytes, with an original-budget overrun of 411000 bytes.

The actual ARM64 symbol sidecars show proxy growth of 17600 bytes, including
10760 text bytes and 7832 bytes in Lua-named code and callbacks. Root-helper
protocol text did not grow. New positions, `u32add`, and safety checks are
present. The earlier footprint includes dependency changes and non-root Lua;
it cannot all be attributed to Lua.

The user approved a refresh of only `native-size-baseline.json` to the actual
`617008b14` measurements. Per-library 131072-byte, total 2 percent, and total
262144-byte growth limits stay unchanged. This preserves the current change's
131776-byte growth and leaves 130368 bytes of total headroom.

The maintained ELF and size scripts then checked the actual stripped native
shard artifacts from implementation CI `36555670276`. ELF metadata passed for
five libraries across four ABIs. All eight proxy/tunnel size entries passed
against the approved baseline. Binary SHA256 values, artifact IDs, and ABI
provenance are recorded in the isolated build tree under
`build/nfqws-size-actual-ci2c/provenance.json`; the same directory contains
`elf-gate.log`, `size-gate.log`, and `approved-size-report.md`. No substitute
ELF files or rebuilt binaries were used.

Source reports: CI `36545675398` for `617008b14` and CI `36555670276` for
`2c1697db3`. Local comparison artifacts are
`/tmp/ripdpi-native-size-prior-current-table.md`,
`/tmp/ripdpi-native-size-symbol-delta.md`, and
`/tmp/ripdpi-native-size-section-delta.md`.

## Hosted validation and publication

Implementation commit `2c1697db3bc95ea0df3fe4ded925c64db8aac1b7` was integrated
with a fast-forward merge and pushed to `origin/main`. The remote SHA matched.
The framed Android root probe is in
`9aa8633c529d71985fe7317c67c9a1544875f705`; the approved baseline refresh is in
`1dbc5d6d4a501fdbd4437fc171db599a4ea8abee`.

[Implementation CI](https://github.com/po4yka/RIPDPI/actions/runs/36555670276)
finished with 43 successful, four failed, and 17 skipped jobs. The three debug
distribution jobs failed only the old size budget; `ci-required` reported that
aggregate failure. Their APK compilation and ELF checks passed. All three
release shards and actual instrumentation targets API 27, 33, 35, 36, and
37 with 16 KiB pages passed. Static analysis, Rust workspace/lint/coverage,
Miri, relay interoperability, network E2E, JNI API, source asset staging,
Roborazzi, Kotlin coverage, and actual gomobile-linked unit gates passed.
CodeQL, Secret Scan, and fleet fixture runs also passed. Skipped scheduled and
opt-in lanes are not included as passes.

The next push validates the framed smoke and approved size baseline. These
changes do not change the tested runtime binaries. The remaining root target
gate keeps the portfolio task blocked and the OpenSpec change open.
