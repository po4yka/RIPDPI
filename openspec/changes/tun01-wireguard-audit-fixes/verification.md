---
task_id: TRN-1794383863211672
change: tun01-wireguard-audit-fixes
commit_sha: null
local: required
local_evidence: Functional tests, strict Clippy and source gates passed; the fixed-source reference run is pending.
remote_ci: required
remote_ci_evidence: Awaiting the authorized push and hosted checks for its exact SHA.
device: blocked
device_evidence: No Android acceptance run was made. Host loopback tests do not prove VpnService routes, protection, CheckJNI, PMTU or network handover.
artifact: not_applicable
artifact_evidence: This change commits source repairs; no release artifact is requested.
deployment: not_applicable
deployment_evidence: No service or production deployment is owned by this change.
---

# Verification

The audit baseline is ec4f13320a272b945bf2a0497d02fcea545d73c9. The working source contains the repairs below. Separate fix commits and the final source SHA are recorded after local checks finish.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1794383863211672-F1 | TRN-1794384081332121 | Editor/parser/activation regressions: a repeated import matches a fresh import, omitted policy selects family-aware defaults, and explicit empty policy stays invalid. App/data run: 89 passed. | C, V: passed locally |
| REQ-TRN-1794383863211672-F2 | TRN-1794384110942936 | Backend tests assert the independent 20-byte IP to 64-byte WG message length, exact payload recovery and queued-message behavior. The wrapper test asserts literal buckets at a non-aligned MTU of 1330. AWG retains its padding policy. | C, V, checked S: passed locally; new reference E pending |
| REQ-TRN-1794383863211672-F3 | TRN-1794384117975110 | Real encrypted local-peer tests cover IPv4/IPv6/port changes and reject replay, malformed, forged and provisional-cookie endpoint learning. Cookie replies use the actual source. Protection failure cannot select an unprotected fallback. | C, V: passed locally; no independent roaming E claim |
| REQ-TRN-1794383863211672-F4 | TRN-1794384124102378 | CIDR boundary tests, real encrypted source/destination denial, SOCKS denial, legacy/empty JSON tests, required Kotlin serialization and DNS policy tests. New producers supply exact policy; old absent native policy keeps its documented default. | C, V: passed locally |
| REQ-TRN-1794383863211672-F5 | TRN-1794384130805227 | The real smoltcp burst/backpressure test includes empty datagrams and checks exact payloads and boundaries. The old audit reproduced the UDP0 loss against wireguard-go. | C, V: local regression passed; fixed reference E pending |
| REQ-TRN-1794383863211672-F6 | TRN-1794384138628907 | Candidate selection accepts either family and interleaves A/AAAA results. A real timer test reaches a live IPv6 peer after several silent IPv4 candidates within an 8-second deadline. Native owners explicitly release detached system lookups on shutdown. | C, V: local resolution/fallback passed; blocked-resolver Android stop remains unverified |
| REQ-TRN-1794383863211672-F7 | TRN-1794384148575819 | Synthetic message thresholds test rekey and rejection without nonce wrapping or replay-state mutation. Replay arithmetic passed Miri seeds 0 through 15. | C, V, checked S: passed locally; no threshold wire E claim |
| REQ-TRN-1794383863211672-F8 | TRN-1794384157128350 | Timer tests check one bounded 0–333 ms jitter sample per initiation, retry deadlines and the existing AWG timer policy. | C, V, checked S: passed locally |
| REQ-TRN-1794383863211672-F9 | TRN-1794384161932447 | Cookie rotation is 120 seconds; boundary tests pass. The mock cookie retry advances the same monotonic clock as its timestamps. Debug tests verify PSK redaction. | C, V, checked S: passed locally |
| REQ-TRN-1794383863211672-F10 | TRN-1794397765858185 | Default decapsulation now retains the complete CookieSource. Tests-only RED compiled and failed all three intended cases; reviewed fix passed 44 default and 47 mock-instant tests. Original-source proof remains valid after a changed-source challenge. | C, V, checked S: passed; under-load reference E remains open |

C means reviewed code. V means an observed local run. S covers only the checked requirements of WireGuard paper revision e2da747; it is not a claim of full conformance. E applies only to a stated independent peer and case. Source review does not raise V or E.

## Observed local checks

All compiler-backed commands use the reserved RIPDPI build lane. Cargo resolves with `--locked`; CMake and Cargo use at most four jobs. Gradle uses four workers and `ripdpi.nativeCpuBudget=4`.

The Kotlin filters below use class-name globs to reproduce the observed selection. The retained XML reports identify all executed cases.

From the job worktree:

```sh
CMAKE_BUILD_PARALLEL_LEVEL=4 build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew :core:engine-api:testDebugUnitTest :core:service:testDebugUnitTest -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4 --tests '*RipDpiAmneziaWgConfigSerializationTest' --tests '*AmneziaWgRuntimeConfigResolverTest' --tests '*VpnTunnelRuntimePolicyTest'
CMAKE_BUILD_PARALLEL_LEVEL=4 build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew :app:testGithubFullDebugUnitTest --tests '*AmneziaWgEditorStateTest' :core:data:testDebugUnitTest --tests '*WireGuard*' --tests '*AwgActivationRequestTest' -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4
CMAKE_BUILD_PARALLEL_LEVEL=4 build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4
CMAKE_BUILD_PARALLEL_LEVEL=4 CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=/tmp/ripdpi-tun01-fixes-target RUSTC_WRAPPER=sccache build-gate --lane ripdpi -- just ci-gates
```

From `native/rust`:

```sh
CMAKE_BUILD_PARALLEL_LEVEL=4 CARGO_TARGET_DIR=/tmp/ripdpi-tun01-fixes-target RUSTC_WRAPPER=sccache build-gate --lane ripdpi -- cargo test -p ripdpi-warp-core --lib --locked --jobs 4
CMAKE_BUILD_PARALLEL_LEVEL=4 CARGO_TARGET_DIR=/tmp/ripdpi-tun01-fixes-target RUSTC_WRAPPER=sccache build-gate --lane ripdpi -- cargo clippy -p ripdpi-warp-core -p ripdpi-warp-android -p ripdpi-amneziawg-android --all-targets --locked --jobs 4 -- -D warnings
```

| Check | Observed result |
|---|---|
| Engine-api/service tests | Exit 0; 74 + 33 tests passed. No failed, errored or skipped test cases. |
| App/data tests | Exit 0; 35 + 54 tests passed after the final dual-stack import correction. No failed, errored or skipped test cases. |
| Warp-core library tests | Exit 0; 175 passed, 0 failed or ignored. The final rerun after two mechanical Clippy corrections also reported 175 passed, 0 failed or ignored (5.83s). A later formatting change affected only that test statement. |
| Vendored backend default / mock-instant suites | Agent-observed exits 0; 41 / 44 passed. Exact commands and source hashes are retained in the private history receipt. The 40 vendor source files match the checked copy. These are earlier observed runs, not new reruns. |
| Miri replay counter boundary | Seeds 0–15 passed. The tested session.rs still matches SHA256 5063639fe482c7c1ee7c4616a4a49b6939b887287a9234f2e72430b427c2fd54. Sender-side Miri stopped before the test body at ring's ARM sysctlbyname; no stub or skip was added. |
| Strict Clippy for the three owning crates | Initial exit 101: two introduced style diagnostics. Both were corrected; exact retry exited 0 for all three crates and all targets (6.02s). |
| staticAnalysis | Exit 0; BUILD SUCCESSFUL in 28m 53s; 826 tasks. Native compilation was explicitly excluded from this Kotlin source gate. |
| just ci-gates | Exit 0. Architecture, LoC, hotspot, runtime/fixture contracts, diagnostics boundary, UI coverage, locales, translations and API snapshots passed. The canonical Linux-only runtime-platform API snapshot is excluded on Darwin by the existing gate. |
| Commit hooks | All eleven fix commits passed their own snapshot checks. An initial partially staged source snapshot failed to compile; lossless isolation of all remaining edits corrected the commit workflow. The F6 test statement also received the formatter-required layout. No hook was bypassed. |
| Independent reviews | General Rust/Kotlin, async cancellation and JNI reviews found no remaining defect in their final reviewed changes. This is source evidence only. |

The earlier standalone vendor Clippy attempt reported nine pre-existing style diagnostics: five documentation continuations, two unnecessary map_or calls, one needless borrow and one manual is_multiple_of. Its terminal exit code was no longer available. No baseline or lint suppression was added.

## Independent reference peer

The fixed-source run is pending. The retained fixture uses wireguard-go commit b3bc704ef0ee21a36298601a9e1e5ff90377ac52, module v0.0.0-20261024220952-b3bc704ef0ee. The public module zip SHA256 is e370e340c37f7b35d35f56d06c8a489613f2b02bf52146bbda6a55336c8b05f6.

The fixture uses a memory TUN and the upstream userspace netstack. It has no kernel TUN, root activation or external target. An outbound guard permits loopback only. Keys are generated for this run and kept in private files. The source input manifest and root comparison cover 5064 copied files and the two outside fixtures.

Planned cases: plain/PSK × outer IPv4/IPv6 × MTU1280/1420. Each lane checks both inner families, TCP8192 and UDP19/0/1/19 plus a near-MTU datagram, exact payload equality, stop and join. The original audit's positive normal flows and failed UDP0 control remain baseline evidence; they do not prove the repaired source.

## Remaining acceptance and limits

- Run the exact-SHA Android APK/JNI path through VpnService and TUN to an owned reference peer. Check AllowedIPs/DNS routes, both outer families, protect-before-use, blocked-resolver stop and CheckJNI. No physical-device, root, external-server or real-secret probe was authorized or run.
- Check PMTU/PTB, IPv6 extension headers and fragmentation, sleep/Doze, Wi-Fi/cellular handover and long transfer stress. Host loopback is not evidence for these paths.
- Add independent wire cases for wrong PSK/key, cookie load and rotation, replay edges and the timed 120/165/180/540-second key lifecycle. Synthetic counters do not prove these timed cases.
- WSS and adjacent WARP/AWG behavior keep their existing scope. The local reference matrix does not claim WSS interoperability, arbitrary raw-IP forwarding, multi-peer routing or IPv6-only interface support.
- The audit's unbounded per-association queue suspicion remains a hypothesis. No stress result established a defect, so no speculative queue redesign is included.
- Hosted CI and device acceptance remain open. This task can reach review after local work; it must not be archived as accepted until its required evidence is resolved.

## Atomic fix commits before main rebase

These are the reviewed job-branch commits. A rebase can change their SHAs.

| Commit | Repair |
|---|---|
| `38649065544e5509d2708b6ff59b2d7c09c6b19e` | fix(wireguard): redact preshared keys from debug output |
| `37992885ab2b1ebe989a06a8ebbdbd779231f4bf` | test(wireguard): align cookie retry mock clocks |
| `a620934e424b93ad1f16066c2e7f65698d3eb0ff` | fix(wireguard): rotate cookie secrets every two minutes |
| `10a73c217603c814eb9b28cb679e4fa07b211367` | fix(wireguard): add bounded handshake retry jitter |
| `6b15389a3ec3d8156322ea82c12d588109eba99b` | fix(wireguard): enforce message counter rekey and reject limits |
| `ed1efdb979237da04fc2905b463f9f158b3f1fb1` | fix(wireguard): pad plain transport within the inner MTU |
| `0204d01e3446053f2ac7f350152adce50aa428e4` | fix(wireguard): enforce profile allowed IP permissions |
| `0833bf16ac40da687b90d8f45f002fee8f705a40` | fix(wireguard): preserve empty UDP datagrams |
| `03f5608bce61ca63ce0c7ddfb2d0b4983cb5cc70` | fix(wireguard): replace source fields on manual import |
| `fbf8ddf790ad59d3c13f1998aaaa49ed7a40670c` | fix(wireguard): retry resolved endpoint address families |
| `d26c1354d1d4380f945d4f9e05676cbfe8ef3f55` | fix(wireguard): learn authenticated peer endpoints |

## Additional finding F10

P3 at pre-fix SHA `8abdc3d237d982205827b75758df92f63468e724`: `native/rust/vendor/boringtun/src/noise/mod.rs:461` passed only `address.ip()` to the plain limiter. `rate_limiter.rs:199` therefore cannot bind the cookie to the source port. Under load, a proof from one port has the same cookie input at another port on that IP. Noise peer authentication remains required. The expected contract is source IP plus UDP port in [WireGuard paper section 5.4.7](https://www.wireguard.com/papers/wireguard.pdf). The AWG path already retains CookieSource. This follow-up repairs plain admission and keeps its legacy IP-only API explicit.

F10 regression commands ran from the isolated cookie-endpoint worktree with `CMAKE_BUILD_PARALLEL_LEVEL=4`, four Cargo jobs and its own target directory, under the held RIPDPI lease:

```sh
cargo test --manifest-path vendor/boringtun/Cargo.toml --lib --locked --jobs 4 plain_cookie_binds_ -- --nocapture
cargo test --manifest-path vendor/boringtun/Cargo.toml --lib --locked --jobs 4
cargo test --manifest-path vendor/boringtun/Cargo.toml --lib --locked --jobs 4 --features mock-instant
```

Observed RED: exit 101, 0 passed / 3 failed / 41 filtered. IPv4 and IPv6 returned type 2 instead of type 3 at the changed port; the connection case returned UnderLoad without a cookie. Observed GREEN: exits 0, 44 / 47 passed, no failed or ignored cases. Root source hashes match the reviewed three-file patch. No Cargo.lock changed.
