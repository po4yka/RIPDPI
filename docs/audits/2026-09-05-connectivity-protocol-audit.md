# Connectivity and protocol audit — 2026-09-05

Task: DGN-1788582590436769. Base: `2e583bf650a62fae9d41147b0967b35a8d186aa8`.

## Result and scope

The audit found defects in active measurements, protocol framing, socket protection, cancellation, settings application, and capture retention. The changes below include regression checks. Independent reviewers examined packet cryptography, socket protection, the Shadowsocks stream destructor, and both Android change sets.

This is a broad source and test audit, not proof that every defect has been found. The workspace contains 117 Rust crates and 17 Android/Java modules. Protocol tests against local fixtures do not prove interoperability with every upstream server. Tests on a host do not prove Android VPN routing, network handover, radio behavior, or performance on a device.

## Corrected findings

Severity describes the failure path, not a claim that every user can trigger it.

| ID | Severity | Failure and effect | Root correction and evidence |
|---|---|---|---|
| A01 | High | MASQUE ECH bootstrap received a source IP but lost `VpnRequired`. A source bind does not apply `VpnService.protect`; bootstrap traffic could enter the TUN. | Carry the existing policy through required DNS connect hooks. TCP protection precedes bind/connect; UDP protection precedes outbound use. Missing and rejecting callback tests fail closed. See `ripdpi-masque/src/ech.rs`, `h2.rs`, ECH DNS facade, and relay MASQUE builder. |
| A02 | High, latent API | The pinned-certificate verifier accepted TLS handshake signatures without checking them. Certificate pin comparison alone does not prove possession of the private key. Default app exploitation was not established. | Use the rustls provider's TLS 1.2/1.3 signature checks. A valid signature passes; a changed transcript fails. See `ripdpi-dns-resolver/src/types/tls_pin.rs`. |
| A03 | High | Direct UDP diagnostics accepted a datagram from an unrelated sender. Route experiments had the same weakness. | Connect the protected UDP socket to its intended peer. Tests send a forged datagram before the valid reply. See diagnostics transport `udp.rs` and `route_experiment/udp.rs`. |
| A04 | High | SOCKS UDP diagnostics did not require the encapsulated source to match a resolved target. | Check the SOCKS source address and port against all candidate peers. Preserve candidate fallback. See diagnostics transport `udp.rs` and `socks5.rs`. |
| A05 | High | A reflected client QUIC Initial or arbitrary response bytes could produce a positive connectivity result. | Use fresh probe connection IDs and one response validator across runner and monitor paths. Check server Initial AEAD, Retry integrity and original destination ID, or valid Version Negotiation correlation. RFC v1/v2 vectors and negative cases cover corruption, truncation and replay. This proves a correlated protocol response, not a completed TLS handshake or authenticated peer; Initial and Retry keys are public. |
| A06 | High | Domain and QUIC probes used only the first configured address; SOCKS UDP also lost resolved alternatives. A hostname DNS error discarded valid pinned IPs. | Retain ordered candidates and SNI. Keep valid pinned addresses when fallback DNS fails. See diagnostics runner `probes/domain.rs`, `probes/quic.rs` and shared transport `tcp.rs`. |
| A07 | High | A process-wide 30-second DNS cache reused successful and failed active measurements across scans and paths. | Remove the measurement cache. Consecutive scans now query the current path. Regression changes the server answer between queries. See diagnostics DNS `dns/udp.rs`. |
| A08 | High | Shadowsocks TCP waited for response salt before the caller could send its request. Client-first protocols could stall. | Start the downstream reader as a pump. The caller can write immediately. Client-first loopback regression passes. See relay TLS transports `shadowsocks.rs`. |
| A09 | High | Shadowsocks 2022 TCP used incomplete request/response headers, incompatible with SIP022 authentication and framing. | Implement fixed and variable headers, bounded timestamp validation, request-salt echo validation, required initial padding, and coalesced initial write. Validate local fixture round trips for all three 2022 methods. |
| A10 | High | Shadowsocks 2022 UDP accepted packets for another recipient session, and replay-window arithmetic overflowed near `u64::MAX`. | Validate recipient before replay insertion; compare replay distance by subtraction. Cover AES and ChaCha methods, wrong recipient and counter boundary. |
| A11 | Medium | TCP codec offsets could exceed an output buffer; legacy payloads above the protocol maximum were not rejected. | Bound offsets and the legacy `0x3fff` payload length. See Shadowsocks TCP round-trip tests. |
| A12 | Medium | Three SOCKS negotiation writes used `write`, which can return after a short write. | Use `write_all` for complete frames. Short-writer regression tests cover client and server negotiation. |
| A13 | Medium | DoQ sent the caller's nonzero DNS ID and accepted malformed stream framing. | Set wire ID to zero, restore caller ID locally, require a complete DNS header and FIN with no trailing bytes. Local Quinn tests cover caller ID, malformed ID/header and trailing data. |
| A14 | Medium | HTTP parsing lost IPv6 authority, query-only paths and non-default Host ports, or accepted malformed authority. | Parse the authority and path explicitly; format bracketed IPv6 and port in Host while keeping the TLS name separate. See runner `endpoint/target_parse.rs` and diagnostics HTTP `probe_execution.rs`. |
| A15 | High | Support settings were staged against a snapshot outside the repository update. Concurrent edits could be lost. Malformed activation filters could escape validation. | Stage inside the atomic repository update; return the existing invalid-value outcome for parse errors. Preserve unrelated concurrent fields. |
| A16 | Medium | A support-settings storage I/O error left the UI without a terminal, retryable state. | Catch storage I/O failure in the ViewModel, clear busy state, and show existing localized error resources with retry. No locale keys added. |
| A17 | Medium | Readiness timeout handling could consume outer cancellation or nested timeouts. WARP duplicated the same behavior. | Use the shared readiness helper with an owned `withTimeoutOrNull` scope. Outer and nested cancellation propagate. WARP uses the same helper. |
| A18 | Medium | Imported WARP obfuscation settings could exceed native bounds or use colliding headers. | Normalize JMIN to 0–1024, padding to 0–1280, and headers to distinct nonzero unsigned 32-bit values; avoid the reserved S2 collision. |
| A19 | High | Cancellation after native PCAP start but before publishing ownership could leave a capture running without a controller owner. | Native start and ownership publication complete in one non-cancellable section under the existing lifecycle mutex. Tests cancel both start paths after the native side effect. |
| A20 | High | Capture retention cleaned `cacheDir/diagnostics`, while native captures were in `filesDir/pcap`. Stale active markers could exempt old files indefinitely. | Inject the actual capture directory and serialize cleanup with the runtime lease. Preserve the current set, including failed-stop ownership; expire stale inactive markers. The provider stays in the app layer, which already owns both dependencies. |
| A21 | Medium | Proxy detection read the HTTP status in a single read. TCP fragmentation could turn a valid proxy into a false negative. | Read through CRLF with a size bound and a total read deadline. Cover fragmented input, EOF, 407, oversized/trickling input and invalid `2000` status. |
| A22 | Medium | The initial half-close fix exposed a full-drop resource leak when the peer stayed silent. | Return a stream that owns the supervisor abort handle. Full drop releases both pumps and transport; explicit shutdown preserves the response half. A silent-peer transport destructor test covers this reviewer finding. |
| A23 | Low | Two tooling tests depended on an old catalog date or repeated Python startup within a short deadline. | Derive the consistency-test date from the current catalog; retain the separate stale-date test. Use a small shell mock for the curl harness without changing assertions or deadlines. |
| A24 | Low | The runtime boundary scanner called Cargo metadata without `--locked`. | Add `--locked`; preserve the dependency lockfile during the check. |
| A25 | Low | Architecture prose still called implemented SSH and Mieru transports stubs. | Align the configuration contract and descriptor comment with current TCP factories and UDP capability gates. No protocol capability was enabled. The proxy JNI panic-sentinel comment now agrees with the exported `-1`. |
| A26 | Low | TCP and UDP ephemeral port spaces are independent. A fixture could select a TCP port already in use by UDP and panic at startup. | Reserve both sockets before runtime startup, retry only UDP `AddrInUse` up to 32 attempts, and propagate other errors. An occupied-port regression covers the collision. |
| A27 | High, root-helper path | Truncated SCM_RIGHTS receipt could leak installed descriptors before nonce validation. A larger buffer alone does not cover Linux resource-limit truncation. | Receive the supported descriptor envelope, adopt the returned prefix even on `MSG_CTRUNC`, and reject after cleanup. Host protocol suite: 39 unit and 3 integration tests passed; independent reviewer: 10 SCM tests passed. Android aarch64 all-target clippy compiled the isolated RLIMIT regression. Actual Linux RLIMIT execution remains a CI check. |

All Rust source paths above are under `native/rust/crates/`. Android changes are under `app/`, `core/data/settings/`, `core/engine/`, `core/detection/`, `core/diagnostics/`, and `core/pcap-export/`.

## Coverage ledger

| Area | Evidence inspected or executed | Limit |
|---|---|---|
| App and Compose | Support-settings UI/ViewModel, navigation-facing state, error resource reuse, new screen test source; independent review | Full screen rendering and app tests require Android dependencies |
| Data model/settings/runtime/catalog | Atomic settings update, protobuf activation parsing, WARP normalization, catalog refresh tests, configuration contracts | No schema migration changed; exhaustive database migration testing was not performed |
| Diagnostics and diagnostics-data | Runner endpoints, domain/HTTP/QUIC/DNS probes, transport fallback, result taxonomy, capture export and retention; 726-test six-crate matrix | Device scans and operator-specific results remain separate |
| Detection and Xray protos | Proxy status parser and real-source regression checks; protobuf build began during Gradle attempt | Complete detection JVM suite was blocked by dependency access |
| Engine and engine API | Readiness cancellation, WARP lifecycle reuse, JNI and cross-language contract scans | Full Android/JNI library build and R8 map proof remain separate |
| Service | VPN protection propagation, runtime ownership boundaries, RAW_PATH exception, capture lifecycle integration | No real radio handover or foreground-service lifecycle run |
| SOCKS and Shadowsocks | Short I/O, TCP half-close/drop, legacy and 2022 framing, AEAD recipient and replay checks; loopback runtime tests | The updated repository fixture is not an independent upstream server |
| DoH/DoT/DoQ/DNSCrypt/ECH | Shared socket hook and TLS verification boundary, DoQ framing, active DNS behavior; resolver and ECH tests | DoH/DoT/DNSCrypt did not receive new independent external interoperability tests |
| QUIC and packets | Shared Initial/Retry/VN validation, RFC 9001/9369 vectors, parser tests, runner/monitor integration | No full external QUIC handshake or network impairment matrix |
| Other relay protocols | Registry and builder paths for VLESS, TUIC, Hysteria2, MASQUE, Trojan, AnyTLS, SSH, Mieru and Tor; selected protection/TLS paths | No claim that all server versions, transports or relay chains interoperate |
| WARP/AmneziaWG/root/desync | Configuration and protection boundaries, native architecture/FFI/unsafe scans | WireGuard/AmneziaWG and root packet mutation were not exercised on a device |
| Build logic/quality/harness | Architecture/LoC checks, 643 tooling tests, 33 CI tests, strict harness checks, lockfile discipline | Full Gradle static analysis and release checks depend on unavailable artifacts |
| Baseline profile/socket-bind helper | Module and CI ownership reviewed | No macrobenchmark or unprivileged bind-to-device instrumentation was run |
| Release and dependencies | Advisory waiver checks, attempted live cargo-deny, CI route review | Live cargo-deny passed on retry; release R8 mapping remains a separate gate |

## Additional source contracts sampled

The final breadth pass used ten bounded source paths. No further behavioral defect was confirmed in these sampled paths apart from the IPC finding.

- `ripdpi-desync/src/plan_tcp/offset_plan.rs`: offset bounds and the first-range seqovl guard; adjacent planner tests.
- `ripdpi-proxy-runtime-adapter/src/platform.rs`: protection before TCP/UDP connect and propagation of protection errors.
- `ripdpi-root-helper/src/main.rs`: nonce before dispatch, `0600` socket permission and FD ownership. Ancillary receipt occurs before nonce validation and needs the IPC correction.
- `ripdpi-android-proxy-adapter/src/lifecycle.rs` and `registry.rs`: running-session destroy rejection, tombstones under the lock, and panic cleanup.
- The five Android JNI loader crates: panic containment and SIGPIPE initialization. This is source evidence, not an Android binary smoke test.
- `RipDpiVpnService.kt`: foreground promotion before the runtime action. The duration of `onCreate` was not measured.
- `VpnProtectSocketServer.kt`: bounded dispatch, shutdown and negative acknowledgements; existing stalled-reader and backpressure test source.
- `RipDpiDatabaseModule.kt` and `RipDpiDatabaseMigrations.kt`: explicit migration 1→2, no destructive fallback, and existing rule/schema tests.
- `.github/workflows/release.yml` and `release-candidate.yml`: revision/provenance/hash binding, release environments and signing-key cleanup.
- `.github/workflows/harness-checks.yml`: committed strict manifest, link, policy, mirror and hook gates.

## Observed validation

Initial tests reproduced defects before source correction in the three writer lanes. Final source was reviewed independently before integration.

- Combined native gate: **1336 passed, zero failed, ten pre-existing ignored**, across 52 suites in the 14 affected crates. This includes the paired TCP/UDP fixture collision test. The separate IPC follow-up passed 42 additional tests on the combined tree.
- Diagnostics: full tests for `ripdpi-diagnostics-dns`, `ripdpi-diagnostics-http`, `ripdpi-diagnostics-runner`, `ripdpi-diagnostics-transport`, `ripdpi-monitor-engine`, and `ripdpi-packets`: **726 passed, zero failed, five pre-existing ignored**. All-target clippy with `-D warnings`: passed.
- Protocols: SOCKS, Shadowsocks and relay TLS suite passed; the stream-drop follow-up passed **26 TLS transport tests** and **180 relay-core tests** with one pre-existing ignored. A TCP/UDP fixture port collision appeared in one run and is tracked below.
- DNS resolver: full DoQ/TLS regression suite passed; all-target clippy passed. MASQUE and ECH: **100 passed**, zero failed.
- Python: **643 tooling tests** and **33 CI tests** passed. No deadlines, quality thresholds, goldens or baselines were relaxed.
- Combined source checks: architecture health has 23 indicators, zero new/worsened entries; 117 crates and 351 internal edges. Native architecture contracts, cross-language runtime contracts and async safety passed. File LoC check found zero new violations or baseline growth.
- Full Rust workspace clippy (`--locked --workspace --no-deps --all-targets -- -D warnings`) passed. Full locked Cargo metadata and the runtime crate boundary guard passed.
- Strict harness checks passed after initializing the pinned Rust-skill submodule. FFI and unsafe inventory checks passed. Four stale unsafe allowances are cleanup candidates, not an added unsafe-code regression.
- Android: ktlint passed. Supplemental checks compiled the actual readiness and proxy source and reproduced red-to-green behavior. These checks do **not** replace pinned Gradle tests.
- Both the app-inclusive Gradle test attempt and the narrower five-core-module attempt failed while downloading dependencies from `dl.google.com` (`No route to host`). Target Android tests were not reached. A temporary, external init script restricted unrelated Maven groups to Maven Central; repository dependency configuration was not changed.

Machine-local logs used during the audit are under `/tmp/ripdpi-audit-*`, `/tmp/ripdpi-diagnostics-*`, `/tmp/ripdpi-full-drop-*`, and `/tmp/ripdpi-protocol-*`. They are transient; committed tests and the remote CI run are the durable checks. Do not treat missing logs after cleanup as a successful rerun.

## Remaining work and growth areas

1. Obtain pinned Android test, Hilt, lint and device evidence for the final revision. Capture a support-settings error/retry view and exercise PCAP start/cancel/export/retention in the app.
2. Test Shadowsocks 2022 against a separately built upstream implementation. The fixture and client were changed together; independent SIP022 text review reduces but does not remove common implementation errors.
3. Exercise VPN-required ECH bootstrap on an Android device, with success and rejection of protection callbacks. Verify RAW_PATH remains callback-free.
4. Add a controlled network matrix: IPv4/IPv6, partial DNS outage, UDP loss/reordering, MTU reduction, proxy authentication, network change and cancellation. Keep protocol reachability separate from full TLS/session success.
5. Avoid eager fallback hostname resolution before the first pinned IP attempt if measurements show that it delays probes. The current fix preserves pinned IPs on DNS failure, but does not remove this latency.
6. Run release-specific R8/JNI verification against an actual release mapping. Live `cargo deny --locked check advisories bans licenses sources` passed on retry; warnings include dependency duplication and stale allowances, with no blocking policy finding.
7. Retention tests can add explicit `StopFailed` and cancellation-while-waiting-for-mutex cases. Source review found the intended behavior, but these additional test cases were not part of the initial slice.
8. Remove stale unsafe allowances in a separate reviewed cleanup. Do not change baselines to hide a regression.

## Protocol references

- [RFC 9250, DNS over QUIC](https://www.rfc-editor.org/rfc/rfc9250.html#section-4.2): zero DNS message ID and stream framing.
- [RFC 9001, QUIC TLS and test vectors](https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A): v1 Initial and Retry cryptography.
- [RFC 9369, QUIC version 2](https://www.rfc-editor.org/rfc/rfc9369.html#appendix-A): v2 labels and test vectors.
- [Shadowsocks SIP022](https://shadowsocks.org/doc/sip022.html): 2022 TCP/UDP framing and authentication.

## Final revision evidence

Native and tooling corrections are committed in separate units. Local Android validation, rebase onto the latest main, remote CI and final integration evidence are pending. The task remains nonterminal until required evidence is recorded.
