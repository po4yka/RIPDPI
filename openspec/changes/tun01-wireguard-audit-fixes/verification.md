---
task_id: TRN-1794383863211672
change: tun01-wireguard-audit-fixes
commit_sha: eba0f5b1c8fbb03997e65e3f73a4af2641c75eb5
local: passed
local_evidence: Final source passed 176 core tests, 244 Kotlin tests, strict three-crate Clippy, staticAnalysis, ci-gates, locked metadata and independent reference cases. Details below.
remote_ci: required
remote_ci_evidence: Check the authorized pushed main SHA; hosted CI has not yet been observed for that SHA.
device: blocked
device_evidence: No Android acceptance run was made. Physical-device and external-server probes were not authorized. Host loopback does not prove VpnService routes, protection, CheckJNI, PMTU or handover.
artifact: not_applicable
artifact_evidence: Source repairs only; no release artifact requested.
deployment: not_applicable
deployment_evidence: No production deployment requested.
---

# Verification

Audit baseline: ec4f13320a272b945bf2a0497d02fcea545d73c9. Final tested source: eba0f5b1c8fbb03997e65e3f73a4af2641c75eb5, rebased onto main b2f7d1b0e66b1ba05ac7e812ea200214f539b6e4. Thirteen separate repair commits address the eight original findings and verified follow-up defects. The final evidence commit changes task documents only.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Scope |
|---|---|---|---|
| REQ-TRN-1794383863211672-F1 | TRN-1794384081332121 | Repeated import equals a fresh import. Omitted policy uses family-aware defaults; explicit empty policy is invalid. Editor-owned carrier settings are retained. | C, V |
| REQ-TRN-1794383863211672-F2 | TRN-1794384110942936 | Independent message lengths, MTU 1330 buckets and payload recovery pass. Default transport uses standard padding; AWG keeps its own policy. Reference data cases pass. | C, V, checked S, bounded E |
| REQ-TRN-1794383863211672-F3 | TRN-1794384117975110 | Encrypted local-peer IPv4/IPv6/port roaming passes. Replay, malformed traffic, forged packets and provisional cookies cannot learn an endpoint. Cookies use the actual source. | C, V; independent roaming E open |
| REQ-TRN-1794383863211672-F4 | TRN-1794384124102378 | CIDR boundaries, encrypted source/destination denial, SOCKS admission, exact Kotlin JSON policy and DNS policy pass. Legacy absent native policy retains its default; explicit empty policy fails. | C, V; Android route V open |
| REQ-TRN-1794383863211672-F5 | TRN-1794384130805227 | Real smoltcp burst/backpressure preserves datagram boundaries including empty UDP. Reference peer confirms 16 empty datagrams. | C, V, bounded E |
| REQ-TRN-1794383863211672-F6 | TRN-1794384138628907 | Resolution interleaves both families. A timer test reaches IPv6 after silent IPv4 candidates. Native owners release detached system lookups during shutdown. F11 also covers immediate send errors. | C, V; Android resolver-stop V open |
| REQ-TRN-1794383863211672-F7 | TRN-1794384148575819 | Synthetic counter boundaries rekey/reject without nonce wrap or replay-state changes. Replay arithmetic passed Miri seeds 0 through 15. | C, V, checked S; threshold E open |
| REQ-TRN-1794383863211672-F8 | TRN-1794384157128350 | One bounded 0–333 ms jitter sample is used for each initiation. Retry deadlines and AWG timer policy pass. | C, V, checked S |
| REQ-TRN-1794383863211672-F9 | TRN-1794384161932447 | Cookie secret rotation uses 120 seconds. Mock cookie retry uses the correct monotonic clock. PSK Debug output is redacted. | C, V, checked S |
| REQ-TRN-1794383863211672-F10 | TRN-1794397765858185 | Tests-only RED failed three source isolation cases. Full CookieSource repair passed 44 default and 47 mock-instant tests. A changed port cannot reuse the same cookie proof. | C, V, checked S; under-load reference E open |
| REQ-TRN-1794383863211672-F11 | TRN-1794406917242467 | Tests-only RED failed with real errno 49. Plain and AWG startup reach the second owned loopback candidate after an immediate UDP send error. Attempts are bounded; full preludes and protected-fd ownership remain intact. | C, V; independent candidate-failure E open |

C means reviewed code. V means an observed run. S applies only to checked requirements of WireGuard paper revision e2da747. E applies only to the named independent implementation and cases. Source review does not raise V, S or E. This is not full protocol or Android acceptance.

## Final local checks

Compiler-backed commands ran under the reserved RIPDPI build lease. CMake/Cargo used four jobs; Cargo used --locked and a worktree-specific target. Gradle used four workers and nativeCpuBudget=4. No quality gate, baseline, dependency version or lockfile was changed by these repairs. The new main's vendored tungstenite and lock changes are preserved.

| Check | Observed result |
|---|---|
| Core library | 176 passed; 0 failed/ignored. |
| Kotlin | 244 passed: app 36, data 100, engine-api 74, service 34; 0 failures/errors/skips. |
| Backend | F10 final 44 default / 47 mock-instant passed; 0 failed/ignored. Backend source is unchanged by F11 and rebase. |
| Strict Clippy | Three owning crates, all targets, -D warnings: exit 0. |
| staticAnalysis | Exit 0 in the final Gradle command. Native build was explicitly excluded; native checks ran separately. |
| ci-gates | Exit 0. Architecture, contracts, LoC/hotspots, locales/translations and available API snapshots passed. The canonical Linux runtime-platform snapshot is excluded on Darwin by its existing gate. |
| Locked metadata | cargo metadata --locked: exit 0. |
| Reviews | General Rust/Kotlin, JNI and async reviews passed for their owning changes; final F11 general/async review and post-rebase carrier overlap review passed. Source evidence only. |
| Commit hooks | Every repair passed its isolated snapshot hooks; no bypass. An early partially staged snapshot failed, then lossless whole-snapshot isolation resolved the workflow. |

Exact final commands follow. The environment was CMAKE_BUILD_PARALLEL_LEVEL=4, CARGO_BUILD_JOBS=4, CARGO_TARGET_DIR=/tmp/ripdpi-tun01-fixes-target, RUSTC_WRAPPER=sccache. Gradle removes CARGO_BUILD_JOBS and sets its own budget. Reference uses its own target directory.

From `/tmp/ripdpi-tun01-fixes/native/rust`, exit 0 (15.117s):

```sh
cargo test -p ripdpi-warp-core --lib --locked --jobs 4
```

From `/tmp/ripdpi-tun01-fixes/native/rust`, exit 0 (10.847s):

```sh
cargo clippy -p ripdpi-warp-core -p ripdpi-warp-android -p ripdpi-amneziawg-android --all-targets --locked --jobs 4 -- -D warnings
```

From `/tmp/ripdpi-tun01-fixes`, exit 0 (104.798s):

```sh
just ci-gates
```

From `/Users/npochaev/Downloads/RIPDPI_network_audits/TUN-01-fixes-evidence/reference`, exit 0 (22.717s):

```sh
build-gate --lane ripdpi -- python3 run_reference.py
```

From `/tmp/ripdpi-tun01-fixes/native/rust`, exit 0 (1.255s):

```sh
cargo metadata --locked --format-version 1
```

From `/tmp/ripdpi-tun01-fixes`, exit 0 (424.766s):

```sh
env -u CARGO_BUILD_JOBS ./gradlew :app:testGithubFullDebugUnitTest --tests '*AmneziaWgEditorStateTest' :core:data:testDebugUnitTest --tests '*WireGuard*' --tests '*AwgActivationRequestTest' --tests '*AmneziaWgUriCodecTest' --tests '*AwgProfileRepositoryRoomTest' --tests '*AppSettingsJsonTest' --tests '*AmneziaWgV3ParametersTest' :core:engine-api:testDebugUnitTest :core:service:testDebugUnitTest --tests '*AmneziaWgRuntimeConfigResolverTest' --tests '*VpnTunnelRuntimePolicyTest' staticAnalysis -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4
```

Raw commands, logs, XML, source hashes and receipts are retained privately under /Users/npochaev/Downloads/RIPDPI_network_audits/TUN-01-fixes-evidence. This local evidence path is not a build input.

## Regression history and limits

F10 at pre-fix source 8abdc3d237d982205827b75758df92f63468e724: plain decapsulation discarded the source port before cookie admission. [WireGuard paper section 5.4.7](https://www.wireguard.com/papers/wireguard.pdf) binds the proof to IP plus UDP port. Tests-only RED compiled, exit 101:0 passed/3 failed/41 filtered. GREEN:44 / 47 passed. Noise peer authentication was never removed. The public legacy IP-only adapter explicitly uses port 0; native receive supplies the full source.

F11 at pre-fix source d3f522d0584a5a543d7ff99949c8cf1d96d7f6be: the first immediate send error stopped startup before an alternate resolved address. A real dual-stack socket bound to ::1 returned errno 49 for mapped IPv4 but delivered IPv6. The native tests-only RED compiled, exit 101:0 passed/1 failed/175 filtered. GREEN passed plain and AWG cases and all 176 core tests. Only unconfirmed UDP initiations retry. Data, WSS and explicit replies do not replay; each candidate gets the complete AWG prelude.

Exact RED/GREEN commands are in f10/red-results.json, f10/green-results.json and f11/red-results.json, f11/green-results.json. All Cargo commands use --locked. Tests and generated input stayed in isolated worktrees or private evidence copies until each reviewed regression was committed.

Earlier checks remain separate historical evidence: standalone vendor Clippy reported nine pre-existing style diagnostics; its terminal exit code was unavailable. No suppressions were added. Miri replay seeds 0–15 passed with session.rs SHA5063639fe482c7c1ee7c4616a4a49b6939b887287a9234f2e72430b427c2fd54. Sender Miri stopped before its test body at ring's ARM sysctlbyname; no stub or skip was used.

## Independent reference peer

Pinned official wireguard-go: b3bc704ef0ee21a36298601a9e1e5ff90377ac52, module v0.0.0-20261024220952-b3bc704ef0ee. Public module zip SHA256: e370e340c37f7b35d35f56d06c8a489613f2b02bf52146bbda6a55336c8b05f6. The fixture uses upstream memory TUN/netstack, generated private keys, and loopback-only outbound guards. No external server, kernel TUN or root activation was used.

Final copied inputs: 5118; changed/missing: 0. Manifest SHA256: b554e89ddc88f10f724fcb7b7bf8f3267fa4252fd979396ecc3102a032d993d1. Root SHA matches the final tested source.

Eight lanes passed: plain/PSK × outer IPv4/IPv6 × MTU 1280/1420. Both inner families passed 16 exactTCP 8192 exchanges and 80 exactUDP exchanges, including 16 empty replies independently recorded by Go. All eight helpers stopped gracefully with exit 0; no remaining helper was observed. This E claim covers these cases only. It does not cover under-load cookies, roaming, replay edges, long rekey, Android or WSS.

## Atomic repair commits

| Commit | Repair |
|---|---|
| `0df99db03a5920dc287bd24a67c42168c15ec2ff` | fix(wireguard): learn authenticated peer endpoints |
| `1d170e77fa15868be0a8ec4e9cab3c8949dda12f` | fix(wireguard): retry resolved endpoint address families |
| `d3c5802d32b18d4c8db8db5dbcced56de24bb6c8` | fix(wireguard): replace source fields on manual import |
| `2fefb4423aab78789401eb2f77ff9c8a06f98664` | fix(wireguard): preserve empty UDP datagrams |
| `7add3481240adff31e05b53ba5edd96d83a02b81` | fix(wireguard): enforce profile allowed IP permissions |
| `a3c65023137b187dbdc9e354418f23e8645539ca` | fix(wireguard): pad plain transport within the inner MTU |
| `73f83696cd871a7e8404784eebbd8f24c6f260ac` | fix(wireguard): enforce message counter rekey and reject limits |
| `ac3316d1e44dc30e5a1f9482932c4933b47876c6` | fix(wireguard): add bounded handshake retry jitter |
| `901d1ed5d603aeb75ea35a015138f75d357d6ac2` | fix(wireguard): rotate cookie secrets every two minutes |
| `c3de6598c9fbe542905b9ed542df900898a0ef64` | test(wireguard): align cookie retry mock clocks |
| `b94bc1c1490c748892c0f38689015489ab27ccff` | fix(wireguard): redact preshared keys from debug output |
| `f270e005413e0a2f1f085b50f98b88f2839002de` | fix(wireguard): bind plain cookies to full source endpoints |
| `eba0f5b1c8fbb03997e65e3f73a4af2641c75eb5` | fix(wireguard): continue UDP startup after send errors |

## Remaining acceptance

- Exact-SHA Android VpnService/TUN/JNI to an owned reference peer: AllowedIPs/DNS routes, protect-before-use, both outer families, blocked-resolver stop and CheckJNI. No physical-device, root, external-server or real-secret probe was authorized or run.
- PMTU/PTB, IPv6 extension headers/fragmentation, sleep/Doze, Wi-Fi/cellular handover and long stress remain unverified.
- Independent negative key/PSK, cookie load/rotation, replay edges, and 120/165/180/540-second key lifecycle cases remain open. Synthetic boundaries are not timed interoperability.
- Unbounded association queue and low-order-key concerns remain unconfirmed hypotheses. No speculative redesign is included.
- Arbitrary raw-IP/ICMP, multi-peer routing and IPv6-only interfaces remain outside the declared scope. Adjacent WARP/AWG/WSS modes keep their own contracts.
- Keep the task in review; hosted CI and device acceptance remain unresolved. Do not archive it as accepted.
