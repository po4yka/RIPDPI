---
title: Add DPI-Aware Error Classifier Sealed Hierarchy
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: [add-dns-integrity-checker, add-domain-reachability-scanner, add-tcp16-fat-header-dpi-probe, add-whitelist-sni-finder, add-telegram-speed-test]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add DPI-Aware Error Classifier Sealed Hierarchy #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add a `DpiErrorClassifier` that maps OkHttp / JSSE exception chains to labelled DPI blocking patterns, producing a sealed `DpiProbeError` class hierarchy used by all active DPI probes.

## Context

dpi-detector's `error_classifier.py` is the engine that turns raw Python exceptions into actionable DPI labels. Every probe (TLS, TCP16, DNS) uses it. The Android port needs an equivalent that walks the OkHttp / `javax.net.ssl.SSLException` / `java.io.IOException` cause chain.

**DPI error labels to cover:**

| Label | Trigger pattern | DPI behaviour |
|---|---|---|
| `TLS_SPOOF` | `SSLException("wrong version number")`, `record overflow`, `decode error`, `illegal parameter` | DPI injected garbage bytes into TLS stream |
| `TLS_ALERT_SNI` | `unrecognized_name` TLS alert | SNI not on whitelist, block via TLS alert |
| `TLS_ALERT_HANDSHAKE` | `handshake_failure` alert | DPI rejects handshake |
| `TLS_BLOCK_VERSION` | `protocol_version` alert | TLS version specifically blocked |
| `TLS_RST` | EOF at TLS handshake stage | TCP RST masked as EOF by OS |
| `TLS_EOF` | EOF during data transfer (post-handshake) | Connection interrupted after handshake |
| `TLS_DROP` | `ConnectTimeoutException` at TLS stage | SYN accepted, TLS dropped |
| `TLS_MITM` | Cert expired / self-signed / hostname mismatch | ISP MITM proxy |
| `SYN_DROP` | `ConnectTimeoutException` at TCP stage | SYN packets dropped |
| `TCP_RST` | `ConnectionResetException` at TCP stage | RST injected at TCP level |
| `TLS_RST_TLS` | `ConnectionResetException` at TLS stage | RST injected during handshake |
| `TCP_ABORT` | `ConnectionAbortedException` at TCP | |
| `DNS_FAIL` | `UnknownHostException` / `gaierror` | DNS resolution failed |
| `REFUSED` | `ConnectException(ECONNREFUSED)` | Port closed |
| `NET_UNREACH` | `ENETUNREACH` errno | ICMP network unreachable |
| `HOST_UNREACH` | `EHOSTUNREACH` errno | ICMP host unreachable |

**Connection stage enum** (mirrors dpi-detector's trace hook):
`TCP_CONNECT` → `TLS_HANDSHAKE` → `SENDING_DATA` → `READING_DATA`

**Reference**: `/Users/po4yka/GitRep/dpi-detector/utils/error_classifier.py`

**RIPDPI placement:**
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/DpiProbeError.kt` — sealed class hierarchy
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/DpiErrorClassifier.kt` — classifier functions
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/ProbeStage.kt` — stage enum

OkHttp `EventListener` subclass to track connection stage; classifier reads current stage at exception time.

## Acceptance criteria

- [ ] `DpiProbeError` sealed class covers all 16 labels above
- [ ] `DpiErrorClassifier.classify(exception, stage)` returns correct `DpiProbeError` for each pattern
- [ ] Cause chain traversal: walks `cause` up to depth 10; checks `SSLException` message substrings, errno values via `getCause()?.message`
- [ ] `ProbeStage` enum: `TCP_CONNECT`, `TLS_HANDSHAKE`, `SENDING_DATA`, `READING_DATA`
- [ ] `OkHttpProbeEventListener` updates current stage via `connectStart`, `secureConnectStart`, `requestHeadersStart`, `responseHeadersStart` callbacks
- [ ] `TLS_MITM` detected from `SSLPeerUnverifiedException` (cert chain) and hostname mismatch in `SSLHandshakeException`
- [ ] All 16 label paths covered by unit tests

## TDD workflow

1. **Write tests first**:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/dpi/DpiErrorClassifierTest.kt`:
     - `tls_wrong_version_number_classified_as_tls_spoof()` — wrap `SSLException("wrong version number")` in an `IOException`; assert `DpiProbeError.TlsSpoof`; fails until classifier exists
     - `eof_at_tls_handshake_stage_classified_as_tls_rst()` — inject `EOFException` with stage `TLS_HANDSHAKE`; assert `TlsRst`
     - `connection_reset_at_tcp_stage_classified_as_tcp_rst()` — inject `SocketException("Connection reset")` at `TCP_CONNECT`; assert `TcpRst`
     - `connect_timeout_at_tcp_stage_classified_as_syn_drop()` — inject `SocketTimeoutException` at `TCP_CONNECT`; assert `SynDrop`
     - `unknown_host_exception_classified_as_dns_fail()` — inject `UnknownHostException`; assert `DnsFail`
     - `unrecognized_name_alert_classified_as_tls_alert_sni()` — inject `SSLHandshakeException("unrecognized_name")` at `TLS_HANDSHAKE`; assert `TlsAlertSni`
     - `cert_expired_classified_as_tls_mitm()` — inject `SSLPeerUnverifiedException("Certificate expired")` at `TLS_HANDSHAKE`; assert `TlsMitm`
     - `cause_chain_traversal_depth_10()` — wrap target exception 9 levels deep; assert correct classification
2. **Confirm red** — `./gradlew :core:detection:test` — all 8 fail
3. **Implement** — `DpiProbeError`, `DpiErrorClassifier`, `ProbeStage`, `OkHttpProbeEventListener`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract message-substring constants; unify cause-chain walker

## Definition of done

All 8 unit tests green. `DpiErrorClassifier` used by at least one other probe task as a dependency. All 16 label paths covered.
