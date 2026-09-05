## Context

The audit starts from 2e583bf650a62fae9d41147b0967b35a8d186aa8. The proposal links the portfolio record. Source-level findings need regression tests before source fixes.

## Goals / Non-Goals

- Goal: correct confirmed failures with existing APIs and dependencies.
- Non-goal: introduce new protocols, configuration schemas, services, or baseline allowances.

## Decisions

- Reuse plural target APIs, connected UDP sockets, the existing packet crypto, atomic DataStore update, and coroutine timeout ownership.
- Remove the cross-scan active DNS measurement cache instead of adding network identity state.
- Use RFC9250 message IDs and rustls signature verification; preserve caller-facing DNS messages.
- Implement SIP022 initial headers and validate recipient/session, timestamps, lengths and salt before accepting data.
- Keep existing wire outcome tokens. QUIC response evidence is not a completed application handshake.
- Use existing UI resources for retryable storage errors, preserving cancellation and valid previews.

## Contracts and ownership

- Primary writer: task/OpenSpec/report files, ripdpi-dns-resolver, tooling regression tests and architecture prose. Primary writer alone owns dependency manifests, lockfiles, schemas, locales, baselines, goldens and registry files; no changes to these are planned.
- Diagnostics writer in RIPDPI-audit-diagnostics-20260905: ripdpi-diagnostics-runner, ripdpi-diagnostics-dns, ripdpi-diagnostics-transport, ripdpi-packets and monitor QUIC outcome call sites.
- Protocol writer in RIPDPI-audit-protocols-20260905: ripdpi-socks5-core, ripdpi-shadowsocks, relay-tls-transports Shadowsocks adapter, local-network-fixture Shadowsocks server.
- Android writer in RIPDPI-audit-android-20260905: support settings apply/registry and tests, engine readiness/WARP, WarpSettings normalization and tests, support settings ViewModel/screen and tests.
- Writers do not edit each other's paths. Primary writer integrates intended slices and runs combined-tree checks.

## Risks / Trade-offs

- Removing the measurement cache sends more DNS queries; current-path correctness has priority.
- Stricter QUIC validation can change prior positive results; test Initial, Retry and Version Negotiation separately.
- Protocol codec round trips do not prove upstream interoperability. Record the upstream and device gaps explicitly if unavailable.
- Native and JVM builds require available dependencies. Record blocked gates without substituting weaker proof.

## Migration Plan

No persistent data migration. Revert the scoped fix commit to roll back. Run targeted Rust/JVM regressions, relevant contract checks, architecture health, locked Cargo metadata, formatting, lint and available build checks before integration. Capture hosted CI for the pushed revision. No deployment is owned by this change.

## Additional confirmed audit scope

The Android writer also owns core/pcap-export capture ownership, Kotlin diagnostics PCAP directory/retention wiring and core/detection ProxyProber with regressions. Native diagnostics ownership does not overlap these Kotlin paths. Use existing capture-state contracts to preserve a live set. No PCAP is included in ordinary archives. Probe HTTP status reads must retain bounded size and time. The diagnostics writer also owns minimal diagnostics-http Host authority formatting and probe-only fresh QUIC IDs; desync fake packets stay unchanged.

The protocol writer corrected the existing Shadowsocks runtime matrix in relay-core tests. The fixture remains a local test server; its codec round trips do not certify an independent upstream server.

## Follow-up protection and ownership findings

The primary writer owns the ECH DNS and MASQUE bootstrap fix. Caller-owned encrypted DNS hooks carry the existing socket policy without a new dependency. Source binding remains optional. Shadowsocks streams own a supervisor abort handle; explicit shutdown retains half-close while full drop releases both pumps.

The diagnostics writer owns the bounded follow-up in `ripdpi-root-helper-protocol/src/scm_rights.rs` in its isolated worktree. An independent reviewer must verify descriptor adoption and truncation cleanup before integration. The primary writer owns the combined-tree FFI and unsafe checks.
