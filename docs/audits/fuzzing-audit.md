# Fuzzing Audit

Audited: 2026-03-24 against the current `native/rust` tree.

## Summary

RIPDPI's highest-value native parser surfaces are:

- DNS wire parsing and DNS rewrite paths, because they accept remote attacker-controlled bytes.
- TLS parsing, because it handles remote record boundaries and protocol sniffing on hostile networks.
- JNI JSON config parsing, because it is the main app-to-native trust boundary and feeds multiple secondary parsers.
- Strategy DSL and packet classifiers, because they sit on hot paths and are only lightly stressed today.

The good news is that most parser entry points already return `Result` or `Option` instead of panicking. The main gaps are not pervasive `unsafe`; they are missing coverage-guided fuzz harnesses for wire formats, stateful parser composition, and config-string edge cases.

## Parser Attack Surface Matrix

| Parser | Input source | Trust level | Current test coverage | Fuzz priority |
|----------|------------------|-------------|-----------------------|---------------|
| Proxy config JSON and UI-to-runtime mapping in `ripdpi-proxy-config` via `ripdpi-android` | Android UI JSON over JNI | Local untrusted, boundary crossing into native | Unit tests plus proptests for JSON parse, CLI mapping, and UI mapping | P1 |
| Tunnel config JSON in `ripdpi-tunnel-android` | Android UI JSON over JNI | Local untrusted, boundary crossing into native | Unit tests plus proptests for JSON parse and payload mapping | P3 |
| Diagnostics request JSON in `ripdpi-android` and `ripdpi-monitor` | Android UI diagnostics JSON over JNI | Local untrusted | Unit tests only, no proptest, no fuzzing | P3 |
| Network snapshot JSON in `ripdpi-android` | Android network snapshot JSON over JNI | Local untrusted | No dedicated property test found | P4 |
| Strategy DSL and config strings in `ripdpi-config` and `ripdpi-proxy-config` | CLI args, hosts specs, marker expressions, fake-profile ids, mode strings | Local untrusted | Good unit coverage, indirect proptest through Android wrapper, no direct cargo-fuzz | P2 |
| DNS wire parsing in `ripdpi-dns-resolver` | Raw DNS queries and responses from upstream resolvers | Remote attacker controlled | Unit and integration tests, no proptest, no fuzzing | P0 |
| DNS rewrite and DNS question parsing in `ripdpi-tunnel-core` | Raw DNS packets from TUN and upstream responses | Local untrusted plus remote-derived | Unit tests only, no proptest, no fuzzing | P1 |
| DNSCrypt certificate and response parsing in `ripdpi-dns-resolver` | Remote DNSCrypt certs and encrypted DNS responses | Remote attacker controlled | Focused unit tests, no proptest, no fuzzing | P1 |
| TLS ClientHello parsing in `ripdpi-packets` | TLS handshake bytes inspected from traffic | Includes hostile network input | Strong unit coverage plus proptests, no cargo-fuzz | P1 |
| TLS first-record boundary tracking in `ripdpi-runtime` | Remote TLS response record bytes | Remote attacker controlled | Unit coverage only, no proptest, no fuzzing | P1 |
| QUIC Initial parsing in `ripdpi-packets` | UDP payloads carrying QUIC Initial packets | Remote attacker controlled | Unit tests only, no proptest, no fuzzing | P1 follow-on |
| HTTP request parser in `ripdpi-packets` | HTTP request bytes seen by classifier | Local or upstream traffic, low privilege impact | Unit tests plus never-panic proptest | P3 |
| HTTP response parsing in `ripdpi-monitor` and `ripdpi-failure-classifier` | Remote diagnostic and blockpage HTTP responses | Remote attacker controlled | Unit tests only, no proptest, no fuzzing | P2 |
| IP, TCP, and UDP header parsing in `ripdpi-tunnel-core` | Packets read from TUN fd | Local untrusted but very high frequency | Unit tests only, no proptest, no fuzzing | P2 robustness |
| Telemetry JSON assembly in Android and tunnel telemetry paths | Internal telemetry snapshots serialized to JSON | Trusted internal data, correctness-focused | Ordinary tests only | P4 |

## Entry Points And Findings

### JSON and config entry points

- Proxy config JSON enters native code through `ripdpi-android/src/proxy/lifecycle.rs`, then `ripdpi-proxy-config/src/convert.rs::parse_proxy_config_json`, then the UI or CLI mapping functions.
- Tunnel config JSON enters through `ripdpi-tunnel-android/src/session/lifecycle.rs`, then `ripdpi-tunnel-android/src/config.rs::parse_tunnel_config_json`.
- Diagnostics scan JSON is decoded in `ripdpi-android/src/diagnostics/scan.rs` with a legacy fallback path to the older request shape.
- Network snapshot JSON is decoded in `ripdpi-android/src/proxy/lifecycle.rs`.
- Strategy strings are parsed in `ripdpi-config/src/parse/cli.rs`, `ripdpi-config/src/parse/offsets.rs`, `ripdpi-config/src/parse/fake_profiles.rs`, and `ripdpi-proxy-config/src/convert.rs`.

Production panic sites on these paths:

- `ripdpi-config/src/parse/cli.rs`
  - `groups.last_mut().expect("new group")`
  - `config.groups.get_mut(current_group_index).expect("current group exists")`
  - `config.groups.get(current_group_index).expect("group")`
  - These are bookkeeping invariants inside group construction, not direct malformed-input panics.
- `ripdpi-proxy-config/src/convert.rs`
  - `validate_tcp_chain` uses two `unreachable!()` arms after enum filtering.
  - They are invariant checks, but still worth stressing with coverage-guided fuzzing because they sit close to the JSON and DSL boundary.

### DNS, TLS, HTTP, and packet entry points

- DNS responses are parsed in `ripdpi-dns-resolver/src/transport.rs::extract_ip_answers`.
- Raw DNS queries are parsed before Hickory exchange in `ripdpi-dns-resolver/src/hickory_backend.rs`.
- DNSCrypt certificates and encrypted responses are parsed in `ripdpi-dns-resolver/src/dnscrypt.rs`.
- DNS response rewrite and question-name extraction are parsed in `ripdpi-tunnel-core/src/dns_cache/mod.rs`.
- DNS query names from intercepted TUN traffic are parsed manually in `ripdpi-tunnel-core/src/io_loop/dns_intercept.rs`.
- TLS ClientHello parsing lives in `ripdpi-packets/src/tls.rs` and `ripdpi-packets/src/tls_nom.rs`.
- Remote TLS response record assembly lives in `ripdpi-runtime/src/runtime/relay/tls_boundary.rs`.
- QUIC Initial parsing lives in `ripdpi-packets/src/quic.rs`.
- HTTP request parsing lives in `ripdpi-packets/src/http.rs`.
- HTTP response parsing lives in `ripdpi-monitor/src/http.rs` and `ripdpi-failure-classifier/src/lib.rs`.
- Raw packet header parsing lives in `ripdpi-tunnel-core/src/classify.rs` and `ripdpi-tunnel-core/src/io_loop/packet.rs`.

Production panic sites on these paths:

- `ripdpi-runtime/src/runtime/relay/tls_boundary.rs`
  - `self.tracker.as_mut().expect("tls first-record tracker")`
  - This is an internal state invariant in the assembler, not a direct malformed-record parse panic.
- `ripdpi-tunnel-core/src/dns_cache/mod.rs`
  - `NonZeroUsize::new(max).expect("max must be > 0")`
  - This is a constructor invariant guarded by config validation, not a wire-format panic.
- I did not find direct production `unwrap` or `expect` on malformed DNS, TLS, HTTP, QUIC, or packet bytes themselves. Those parsers mostly fail closed with `Result` or `Option`.

### Unsafe on parsing paths

- I did not find `unsafe` inside the core parser implementations in:
  - `ripdpi-proxy-config`
  - `ripdpi-config`
  - `ripdpi-dns-resolver`
  - `ripdpi-packets`
  - `ripdpi-tunnel-core` parser and classifier modules
  - `ripdpi-failure-classifier`
- Unsafe-adjacent code exists at the boundaries:
  - `ripdpi-android/src/lib.rs` and `ripdpi-tunnel-android/src/lib.rs` export JNI entry points with `#[unsafe(no_mangle)]`.
  - `ripdpi-tunnel-core/src/tunnel_api.rs` uses `File::from_raw_fd` when taking ownership of the TUN fd.
  - `ripdpi-runtime/src/platform/linux.rs` contains socket syscalls, but not parser logic.
- Net: parser hardening here is mainly a fuzzing and invariant-coverage problem, not an `unsafe` memory-safety problem.

## Current Proptest Coverage

Existing proptest targets already in-tree:

- `ripdpi-android/src/config.rs`
  - `fuzz_proxy_json_parser_never_panics`
  - `fuzz_command_line_parser_never_panics`
  - `fuzz_ui_payload_mapping_never_panics`
  - one structured-valid round-trip style property for core fields
- `ripdpi-tunnel-android/src/config.rs`
  - `fuzz_tunnel_json_parser_never_panics`
  - `fuzz_tunnel_payload_mapping_never_panics`
- `ripdpi-packets/src/tls.rs`
  - `parse_tls_never_panics`
  - generated valid ClientHello cross-checks against `tls-parser`
- `ripdpi-packets/src/tls_nom.rs`
  - generated valid ClientHello comparison between manual and nom parsers
- `ripdpi-packets/src/http.rs`
  - `parse_http_never_panics`
- `ripdpi-tunnel-config/src/lib.rs`
  - proptests around YAML config parsing and validation

Notably missing from proptest coverage:

- DNS wire parsing
- DNS rewrite parsing
- DNSCrypt certificate and response parsing
- QUIC Initial parsing
- HTTP response parsing
- TUN packet classifiers and TCP header parsing
- Diagnostics request JSON
- Network snapshot JSON
- Direct `ripdpi-config` DSL fuzzing beyond the Android wrapper

## Security-Critical Assessment

- Most security-critical:
  - DNS wire parsing
  - DNSCrypt parsing
  - TLS record and QUIC Initial parsing
- High operational-risk but less exposed:
  - TUN packet parsing and DNS intercept helpers
  - Strategy DSL parsing that can misconfigure active evasion behavior
- Lowest security priority:
  - Telemetry JSON assembly, which is worth correctness fuzzing but not first-wave security time

The user-supplied priority order is correct for the first rollout. I would only add that QUIC Initial parsing and TUN packet classifiers should be the first follow-on targets after the initial five.

## Recommended Fuzz Targets

### 1. Proxy config JSON parsing

Actual owner crate: `ripdpi-proxy-config`, reached through the JNI boundary in `ripdpi-android`.

- Fuzz target file: `native/rust/fuzz/fuzz_targets/fuzz_proxy_config_json.rs`
- Harness scope:
  - `parse_proxy_config_json`
  - `runtime_config_envelope_from_payload`
  - structured coverage for both UI and command-line payload variants
- Seed corpus strategy:
  - minimal valid grouped UI JSON
  - minimal valid command-line payload JSON
  - legacy flat UI JSON that must be rejected
  - malformed JSON fragments
  - valid payloads with boundary values for ports, TTLs, fake profiles, host-autolearn, and chain steps
  - harvested examples from existing unit tests and presets
- Minimum fuzzing time for meaningful coverage:
  - 4 CPU-hours initial bootstrap
  - then nightly 60 to 90 minute runs

### 2. DNS wire format parsing in `ripdpi-dns-resolver`

- Fuzz target file: `native/rust/fuzz/fuzz_targets/fuzz_dns_wire.rs`
- Harness scope:
  - `extract_ip_answers`
  - `ripdpi_tunnel_core` DNS rewrite helpers through a thin fuzz shim that wraps the currently private `dns_cache` module
  - `DnsCache::rewrite_response`
  - `DnsCache::servfail_response`
  - optional split-input mode so the same target exercises query and response bytes separately
- Seed corpus strategy:
  - valid A and AAAA response packets
  - truncated headers
  - compressed-name responses
  - answers with mixed RR types
  - packets lifted from `ripdpi-dns-resolver` tests and `ripdpi-tunnel-core` DNS cache tests
  - minimized captures from local test resolvers if available
- Minimum fuzzing time for meaningful coverage:
  - 8 CPU-hours initial bootstrap
  - then nightly 90 to 120 minute runs

### 3. TLS record parsing

- Fuzz target file: `native/rust/fuzz/fuzz_targets/fuzz_tls_records.rs`
- Harness scope:
  - `ripdpi_packets::parse_tls`
  - `ripdpi_packets::is_tls_client_hello`
  - `ripdpi_runtime` first-record assembly logic through a thin fuzz shim around the currently private `tls_boundary` module
  - strongly recommended sibling follow-on: `fuzz_quic_initial.rs` for `parse_quic_initial`
- Seed corpus strategy:
  - golden ClientHello vectors already used in `ripdpi-packets/tests/golden_tls_vectors.rs`
  - built-in fake TLS profiles
  - fragmented first-record samples
  - malformed record headers and truncated handshake bodies
  - QUIC Initial packets generated by `build_realistic_quic_initial` for the sibling target
- Minimum fuzzing time for meaningful coverage:
  - 6 CPU-hours initial bootstrap
  - then nightly 90 to 120 minute runs

### 4. Strategy DSL parsing

- Fuzz target file: `native/rust/fuzz/fuzz_targets/fuzz_strategy_dsl.rs`
- Harness scope:
  - `parse_hosts_spec`
  - `parse_offset_expr`
  - `parse_cli`
  - fake-profile and mode string parsers used by `ripdpi-proxy-config`
- Seed corpus strategy:
  - real CLI examples from docs and presets
  - valid and invalid marker expressions such as `host+1`, `sniext+4`, `auto(host)`, and malformed variants
  - host lists with Unicode, whitespace, invalid separators, and large token counts
  - boundary numeric forms for timeout, port, and TTL options
- Minimum fuzzing time for meaningful coverage:
  - 4 CPU-hours initial bootstrap
  - then nightly 60 minute runs

### 5. Telemetry JSON assembly

- Fuzz target file: `native/rust/fuzz/fuzz_targets/fuzz_telemetry_json.rs`
- Harness scope:
  - serialize telemetry snapshots to JSON
  - immediately parse the result back into `serde_json::Value`
  - assert serialization stays valid and bounded under unusual field values
- Seed corpus strategy:
  - captured telemetry snapshots from existing tests or sample sessions
  - hand-written cases with empty fields, large counters, and non-ASCII strings
  - previously minimized crashers if serializer regressions ever appear
- Minimum fuzzing time for meaningful coverage:
  - 1 CPU-hour initial bootstrap
  - then nightly 15 to 30 minute runs

## Immediate Follow-On Targets After The First Five

- `native/rust/fuzz/fuzz_targets/fuzz_quic_initial.rs`
  - QUIC Initial parsing is remote-controlled and currently has no property-based coverage.
- `native/rust/fuzz/fuzz_targets/fuzz_tun_packet_classify.rs`
  - Packet header parsing is local-only but extremely hot and easy to stress with libFuzzer.
- `native/rust/fuzz/fuzz_targets/fuzz_dnscrypt.rs`
  - DNSCrypt parsing is remote-controlled and cryptographic framing bugs are exactly the kind of issue that coverage-guided fuzzing catches well.
- `native/rust/fuzz/fuzz_targets/fuzz_http_response.rs`
  - Diagnostics and blockpage parsers currently only have unit tests.

## `cargo-fuzz` Setup

Recommended layout under the native workspace root:

```text
native/rust/
  fuzz/
    Cargo.toml
    fuzz_targets/
      fuzz_proxy_config_json.rs
      fuzz_dns_wire.rs
      fuzz_tls_records.rs
      fuzz_strategy_dsl.rs
      fuzz_telemetry_json.rs
      fuzz_quic_initial.rs
      fuzz_tun_packet_classify.rs
      fuzz_dnscrypt.rs
      fuzz_http_response.rs
    corpus/
      fuzz_proxy_config_json/
      fuzz_dns_wire/
      fuzz_tls_records/
      fuzz_strategy_dsl/
      fuzz_telemetry_json/
    artifacts/
    dictionaries/
      json.dict
      dns.dict
      tls.dict
      cli.dict
```

Recommended setup steps:

1. Keep `native/rust/rust-toolchain.toml` on stable for normal builds.
2. Install `cargo-fuzz` separately and run it with `cargo +nightly fuzz ...`.
3. Initialize a single fuzz crate at `native/rust/fuzz/` so pure-Rust library crates can be fuzzed without pulling Android build logic into the harness.
4. Prefer harnesses that call library APIs directly and avoid JNI or network I/O.
5. Check in seed corpus files and minimized regressions.
6. For crate-private parser state machines such as `ripdpi-tunnel-core::dns_cache` and `ripdpi-runtime::runtime::relay::tls_boundary`, add tiny fuzz-only wrapper APIs instead of making the full modules public.

Suggested bootstrap commands:

```bash
cd native/rust
cargo +nightly install cargo-fuzz
cargo +nightly fuzz init fuzz
cargo +nightly fuzz run fuzz_dns_wire
```

## CI Integration

Use a separate scheduled workflow instead of adding fuzzing to the normal PR lane.

Recommended workflow shape:

- New workflow: `.github/workflows/rust-fuzz.yml`
- Triggers:
  - nightly `schedule`
  - `workflow_dispatch`
- Matrix over the top three targets first:
  - `fuzz_proxy_config_json`
  - `fuzz_dns_wire`
  - `fuzz_tls_records`
- Time budget:
  - 60 to 120 minutes per target per night
- Implementation details:
  - install nightly and `cargo-fuzz`
  - cache Cargo registry, git index, and `native/rust/fuzz/corpus`
  - upload `native/rust/fuzz/artifacts/**` on failure
  - fail the workflow on any crash, timeout, or sanitizer finding

Recommended second phase:

- Add a short non-blocking PR smoke job with `-max_total_time=60` for changed parser crates only.
- Keep the longer discovery runs scheduled, not on every pull request.

## Relationship With Proptest

The roles should be:

- `cargo-fuzz` finds parser crashes, pathological slow paths, and weird corpus shapes that no one anticipated.
- Proptest converts those crashers into stable regression tests with explicit invariants.

The expected workflow is:

1. Reproduce the crash from `fuzz/artifacts/<target>/`.
2. Minimize it with `cargo +nightly fuzz tmin`.
3. Add the minimized sample to `fuzz/corpus/<target>/`.
4. Add a deterministic unit test or proptest regression in the owning crate.
5. Keep fuzzing to search for the next edge case.

That split matches the current repo well: proptest already exists for some parsers, but it is strongest when the input structure is already known. Coverage-guided fuzzing is the better first tool for opaque wire formats and hostile mixed-validity inputs.

## OSS-Fuzz Potential

OSS-Fuzz is realistic once the first pure-Rust harnesses exist.

Best candidates:

- `ripdpi-packets`
- `ripdpi-dns-resolver`
- `ripdpi-config`
- selected `ripdpi-tunnel-core` parser modules that do not require Android or real TUN devices

Why it fits:

- the highest-risk parsers are pure Rust libraries
- they do not need the Android SDK, JNI, or device state to exercise parsing behavior
- `cargo-fuzz` harnesses can usually be adapted into OSS-Fuzz with limited extra glue

What to defer until later:

- JNI-bound harnesses
- anything that requires actual sockets, async runtimes with external I/O, or Android-specific setup

A pragmatic rollout is:

1. Land local `cargo-fuzz` harnesses and nightly CI.
2. Stabilize corpora and eliminate flaky or non-deterministic targets.
3. Extract the pure-library targets into an OSS-Fuzz integration if ongoing crash discovery justifies the maintenance cost.
