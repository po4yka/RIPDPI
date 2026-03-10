# Coverage Map

This file documents the current Rust-owned Linux-facing contract.

| Surface | Feature | Owner |
| --- | --- | --- |
| CLI | `--help`, `--version`, invalid args | `crates/ciadpi-bin/tests/cli.rs`, `make test` |
| Env startup | `SS_LOCAL_PORT`, `SS_PLUGIN_OPTIONS`, Linux `protect_path` auto-detect | `crates/ciadpi-config/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/config_oracle.json` |
| Config parsing | grouped `--auto`, `--split`, `--to-socks5`, cache flags, proto/pf/rounds/tlsminor/oob/mod-http/udp-fake/fake-data/fake-offset | `crates/ciadpi-config/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/config_oracle.json` |
| Config parsing | Linux-only `md5sig`, `drop-sack`, `fake-sni` flags | `crates/ciadpi-config/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/config_oracle.json` |
| Filters | hosts suffix matching, ipset CIDR matching | `crates/ciadpi-config/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/config_oracle.json` |
| Cache format | dump/load round-trip | `crates/ciadpi-config/tests/oracle_diff.rs`, `tests/corpus/rust-fixtures/config_oracle.json` |
| Protocol parsing | SOCKS4, SOCKS4a domain, SOCKS5 connect, SOCKS5 domain, SOCKS5 IPv6, SOCKS5 UDP associate, HTTP CONNECT, invalid requests | `crates/ciadpi-session/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/session_oracle.json` |
| Packets | HTTP/TLS parsing, redirects, TLS SID mismatch | `crates/ciadpi-packets/tests/packet_regression.rs`, `crates/ciadpi-packets/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/packets_oracle.json` |
| Packet mutations | HTTP header mutation, TLS split, TLS SNI rewrite (grow and shrink), deterministic TLS randomization, no-panic corpus exercise | `crates/ciadpi-packets/tests/packet_regression.rs`, `crates/ciadpi-packets/tests/packet_exercise.rs`, `crates/ciadpi-packets/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/packets_oracle.json` |
| Desync planning | deterministic split/mod-http/tlsrec/tlsminor planning, host/SNI offsets, split/disorder/oob/disoob mode selection | `crates/ciadpi-desync/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/desync_oracle.json` |
| Desync fake generation | deterministic fake packet builder for custom HTTP payloads and TLS SNI rewrites on `FAKE_SUPPORT` platforms | `crates/ciadpi-desync/tests/oracle_diff.rs` via `tests/corpus/rust-fixtures/desync_oracle.json` |
| Desync runtime | stream-visible `mod-http`, `tlsrec`, `tlsminor`, `oob`, and staged `--wait-send` behavior matches the committed Rust-owned desync fixtures and preserves upstream payload contracts | `tests/test_desync_runtime.py::DesyncRuntimeTests`, `tests/corpus/rust-fixtures/desync_oracle.json` |
| Linux wire behavior | loopback packet-capture assertions for `split`, `oob`, `disorder`, `disoob`, and `fake` send-side payload chunking, TTL, URG flags, and staged-send delay timing | `tests/test_desync_runtime.py::LinuxWireCaptureTests` |
| Auto/runtime parity | `AUTO_NOPOST` / `AUTO_SORT`, cache promotion and reuse, cache-backed connect fallback, `redirect`, `ssl_err`, `torst`, and partial-TLS timeout count/byte-limit behavior match the supported runtime contract | `tests/test_auto_runtime.py::AutoRuntimeTests` |
| Linux routed behavior | multi-namespace end-to-end assertions for `fake`, `drop-sack`, and capability-gated `md5sig` confirm original payload delivery across routed loss/rejection paths; the `md5sig` subcase runs only when the kernel/runtime exposes `TCP_MD5SIG` | `tests/test_linux_routed_runtime.py::RoutedLinuxRuntimeTests` |
| Linux socket features | `--protect-path` passes outbound FDs to the helper socket and `--transparent` relays redirected traffic to the original destination | `tests/test_linux_runtime_features.py` |
| Rust desync runtime | default `ciadpi` (Rust) passes the same desync runtime and Linux loopback wire suite with committed desync fixtures for the stream-visible `mod-http`/`tlsminor`/`tlsrec` expectations, while keeping the `oob` stream and tcpdump wire checks explicit | `make test-desync-runtime`, `tests/test_desync_runtime.py`, `tests/corpus/rust-fixtures/desync_oracle.json` |
| Rust auto/runtime parity | default `ciadpi` (Rust) owns the cache, auto-trigger replay, and partial-timeout policy suite | `make test-auto-runtime`, `tests/test_auto_runtime.py` |
| Rust routed desync runtime | default `ciadpi` (Rust) owns the routed Linux fake/drop-sack suite, the environment-sensitive routed preflight, and an explicit `TCP_MD5SIG` capability probe for the `md5sig` subcase | `make test-linux-routed-runtime`, `tests/test_linux_routed_runtime.py` |
| Rust Linux socket features | `make test-linux-runtime-features` exercises the same transparent/protect-path runtime suite against default `ciadpi` (Rust); the phase-7 closeout stabilized the 2026-03-09 protect-path flake by matching the protected socket's bound local port against the echo server's observed client port while still asserting the peer port when the kernel exposes it | `make test-linux-runtime-features`, `tests/test_linux_runtime_features.py`, `make test` |
| Rust runtime migration | default `ciadpi` (Rust) covers pidfile handling, TCP Fast Open, delayed connect, cache stdout dumping, max-conn admission, and Shadowsocks env startup | `make test-rust-runtime-migration`, `tests/test_rust_runtime_migration.py` |
| Rust CLI contract | default `ciadpi` (Rust) owns the `--help`, `--version`, invalid-argument, and invalid-value parse-failure surfaces | `crates/ciadpi-bin/tests/cli.rs`, `make test` |
| Rust binary integration | default `ciadpi` (Rust) owns a Rust-native live binary lane for SOCKS4, SOCKS5, Rustls-backed TLS tunnel relay, HTTP CONNECT success/failure, SOCKS5 UDP associate, UDP fake bursts, delayed upstream connect, upstream SOCKS chaining, connection churn, IPv6 where loopback is available, `--max-conn`, Shadowsocks env startup, `--no-domain`, `--no-udp`, and failed upstream connect handling | `crates/ciadpi-bin/tests/runtime_integration.rs`, `make test` |
| Rust runtime subset | default `ciadpi` (Rust) covers SOCKS4, SOCKS5 CONNECT, HTTP CONNECT, SOCKS5 UDP associate, UDP fake bursts, TLS tunneling, churn, no-domain rejection, no-udp rejection, connect failure handling, SOCKS chaining, and IPv6 echo where available | `make test-rust-runtime`, `tests/test_rust_runtime_subset.py` |
| Install/package cutover | `make install` ships the Rust `ciadpi` binary | `make test-install-cutover`, `tests/test_install_cutover.py` |
| Windows service/runtime parity | `make test-windows-cross-check` compiles Windows-only Rust tests for `x86_64-pc-windows-gnu`, covering the `ByeDPI` service name, stop/shutdown/interrogate control handling, saved CLI argument reuse, executable-directory working-directory reset, and runner exit-code propagation | `make test-windows-cross-check`, `crates/ciadpi-bin/src/platform/windows.rs`, `.github/workflows/ci.yml` |
| Proxy behavior | SOCKS4, SOCKS5, HTTP CONNECT, UDP associate, TLS tunneling, external SOCKS chaining, upstream connect failure handling, IPv6, `--no-domain`, `--no-udp`, `--udp-fake` bursts | `tests/test_proxy_integration.py` |
| Stress | connection churn over repeated proxied SOCKS5 sessions | `tests/test_proxy_integration.py::ProxyIntegrationTests.test_connection_churn_echo` |
| Safety | Rust-owned packet parser/property coverage plus proxy integration, desync runtime smoke, and packet-corpus mutation smoke | `make transition-safety-gates`, `make fuzz-packets`, `crates/ciadpi-packets/src/lib.rs`, `crates/ciadpi-packets/tests/packet_fuzz_smoke.rs` |
| Performance smoke | packet hot-path benchmark smoke | `make bench-smoke`, `crates/ciadpi-packets/tests/benchmark_smoke.rs` |

## Gate Ownership

- Rust-owned defaults: `cargo test --workspace`, `make test`, `make cutover-gates`, the pull-request Linux CI job in `.github/workflows/ci.yml`, and `make test-windows-cross-check`. `make test-rust-oracle-diff` remains a focused subset target for the same committed-fixture cargo coverage that also runs under `make test`.
- Explicit transition/runtime gates: `make transition-runtime-gates` retains `test-desync-runtime` and `test-linux-routed-runtime`, and `make test-transition-runtime` is the matching direct test target. `test-desync-runtime` and `test-linux-routed-runtime` are both Rust-owned and stay explicit only because they are heavier Linux runtime gates. `make transition-safety-gates` provides the Rust-owned packet/proxy/desync safety smoke. The migration-era alias names (`make test-transition-oracles`, `make transition-oracle-gates`, `make test-sanitize`, and `make transition-c-sanitize-gates`) have been retired.

## Operational Follow-ups

- Promotion thresholds for churn and benchmark regressions now that the Rust binary is the active Linux runtime path.
- Keep `test-desync-runtime` and `test-linux-routed-runtime` explicit unless a separate gate-promotion slice accepts their live-runtime and environment-sensitive cost in the default path.
- Run native Windows validation separately if you need execution evidence beyond the cross-target compile/link coverage.
