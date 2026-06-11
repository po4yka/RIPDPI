---
title: Add Criterion throughput benchmarks for each transport
type: task
status: done
area: testing
priority: medium
owner: unassigned
parent: epic-protocol-conformance-tests
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-11
---

## Summary

Wire one Criterion benchmark per transport (VLESS, xHTTP, MASQUE, Hysteria 2, TUIC, ShadowTLS, WS tunnel) into `ripdpi-bench` so the `regression-detector` agent can gate throughput regressions per release.

## Context

`ripdpi-bench` exists in the workspace. The regression-detector agent expects checked-in Criterion baselines for each transport. Today there is no per-protocol throughput signal in CI, so a 30% bandwidth regression in xHTTP could ship unnoticed.

## Acceptance criteria

- [x] One Criterion benchmark per transport that drives a loopback pair through a representative payload size (1 MiB). **7 of 7 covered**: `protocol_throughput.rs` covers VLESS+Reality, VLESS-over-xHTTP-over-Reality, ShadowTLS v3, MASQUE (H2 CONNECT-TCP), WS-tunnel (WebTunnel), Hysteria2, and TUIC — driven against `VlessRealityLoopback` / `XhttpRealityLoopback` / `ShadowTlsLoopback` / `MasqueH2ConnectUdpFixture` / `WebTunnelFixture` / `Hysteria2Loopback` / `TuicLoopback`. Notes on what each transport needed to be benchable:
  - MASQUE and TUIC were unblocked by adding a `root_certificate_pem` trust-anchor option to their `Config` (cert pinning of the fixture's self-signed cert — verification stays ON) plus a fixture cert getter.
  - WS-tunnel was unblocked by adding an async client `connect_webtunnel_async` to `ripdpi-webtunnel`, since the sync `connect_webtunnel` returns a `std`-I/O boring `SslStream` that cannot feed the async harness.
  - Hysteria2 was unblocked by fixing the client to keep its h3 `SendRequest` alive past auth (it was closing the shared QUIC connection on `SendRequest` drop) and adding `Hysteria2Loopback` (quinn + `h3::server` auth + raw proxy streams).
  - TUIC got its own protocol-server loopback `TuicLoopback` (QUIC ALPN `h3` + drains the keying-material-export auth uni-stream + parses the Connect command framing and echoes) — the generic `QuicLoopback` is an echo, not a TUIC server. Each pinned-cert transport has a `loopback_e2e.rs` regression test (positive round-trip + negative wrong-cert verification).
- [x] Baselines committed in `scripts/ci/rust-bench-baseline.json` (the canonical path — NOT `native/rust/crates/ripdpi-bench/baselines/`, which never existed; that path in the original criterion was a doc error, now corrected here and in the crate README). **Captured 2026-06-11** on the CI reference runner: `workflow_dispatch` of the `CI` workflow with `capture_criterion_baseline = true` (run 27330785225) ran the full bench and uploaded the `rust-bench-baseline-candidate` artifact; the 7 `protocol-throughput/*` mean/median entries were merged into the committed baseline (the existing `maxRegressionPercent: 10.0` and the two non-transport keys preserved; the candidate's other microbench keys intentionally not added, to keep the advisory lane noise-free). Numbers are reference-runner-captured, not dev-box.
- [x] `regression-detector` wired into the nightly CI lane. The existing `rust-criterion-bench` job (`.github/workflows/ci.yml`, nightly `schedule` cron `0 3 * * *`) **enforces** on schedule runs (`--only-prefix 'protocol-throughput/' --max-regression-percent 20`, no `--warn-only`) while PR/dispatch runs stay advisory (`--warn-only`, full suite). **Now armed:** the committed baseline holds the 7 `protocol-throughput/*` keys, so the enforced lane is live (no longer a no-op). `--only-prefix` was added to `check-criterion-regressions.py` so the gate covers exactly the DoD scope and the noisier microbenches can't flap it; the 20% threshold tolerates shared-runner noise while catching the 25% DoD slowdown.

## Definition of done

- [x] A deliberate 25% slowdown in any one transport fails the regression-detector lane.
  - **Mechanism verified locally**: against a populated baseline, injecting +25% on a
    `protocol-throughput/*` key with `--only-prefix 'protocol-throughput/'
    --max-regression-percent 20` yields a `REGRESSION` and exit 1; the unperturbed
    control and the not-yet-populated real baseline both exit 0.
  - **Armed 2026-06-11**: the reference-runner baseline now holds the 7 keys, so the
    nightly `schedule` lane enforces for real.

## Links

- [[Epic - Control-plane hardening]]

## Work log

- 2026-06-11: **Captured + committed the reference-runner baseline; task done.** Dispatched the `CI` workflow with `capture_criterion_baseline=true` (run 27330785225); the `rust-criterion-bench` job's candidate artifact carried all 7 `protocol-throughput/*` keys. Merged those 7 mean/median entries into `scripts/ci/rust-bench-baseline.json` (preserving `maxRegressionPercent: 10.0` and the 2 existing non-transport keys; deliberately excluded the candidate's other microbench keys to keep the advisory lane noise-free). The nightly enforced lane is now armed. All three acceptance criteria + the DoD are satisfied.
- 2026-06-05: `ripdpi-bench` exists with `relay_throughput.rs` but benchmarks only generic tcp-echo (1MiB/64KiB/1KiB), not per-transport (VLESS/xHTTP/MASQUE/Hysteria2/TUIC/ShadowTLS/WS-tunnel). No `baselines/` dir under `ripdpi-bench/`. CI has `rust-criterion-bench` job with `check-criterion-regressions.py` but uses `--warn-only` and `rust-bench-baseline.json` lacks per-transport entries. All three acceptance criteria remain unmet.
- 2026-06-05: added `protocol_throughput.rs` with per-transport 1 MiB full-duplex throughput benches for VLESS+Reality and VLESS-over-xHTTP-over-Reality, each driving the real client against its loopback fixture with a concurrent write/read round-trip (handshake established once, outside the timed loop). Documented the baseline-capture-on-CI-reference-runner requirement (no dev-box baselines committed). Surfaced a ShadowTLS throughput collapse (~0.5 MiB/s) under concurrent split read+write.
- 2026-06-05: landed the Hysteria2 bench (6/7). Root-caused and fixed the earlier blocker: `ripdpi-hysteria2`'s `authenticate_connection` dropped its h3 `SendRequest` after auth, which made the h3 client graceful-close and tear down the shared QUIC connection (`H3_NO_ERROR`) before the first proxy stream — a latent bug. Fix keeps the `SendRequest` alive for the connection lifetime. Added `Hysteria2Loopback` (quinn + `h3::server`), an in-crate regression test (`ripdpi-hysteria2/tests/loopback_e2e.rs`), and the `hysteria2/1MiB` bench case (~147 MiB/s). Closed the investigation task. Only TUIC remains.
- 2026-06-05: attempted the Hysteria2 protocol-server loopback (to reach 6/7). Built a quinn + `h3::server` fixture: QUIC/ALPN-`h3` handshake + the 233 auth succeed, and the h3-auth / raw-`accept_bi` coexistence works (the server receives the proxy stream). But the bench is blocked by a client behavior — `ripdpi-hysteria2`'s `authenticate_connection` drops its h3 `SendRequest` after auth, triggering an h3 graceful shutdown that closes the shared QUIC connection (`H3_NO_ERROR`/256), which races ahead of and kills the proxy stream's TCP request/response (`LocallyClosed`). No server can stop the client closing its own connection, so the WIP fixture was reverted (no broken code shipped) and the finding filed as [[investigate-hysteria2-client-closes-quic-connection-after-auth]]. Bench stays at 5/7; Hysteria2 is gated on that client fix, TUIC on its own loopback.
- 2026-06-05: enabled + landed the WS-tunnel bench case. Added an async WebTunnel client `connect_webtunnel_async` to `ripdpi-webtunnel` (tokio `TcpStream` + `tokio_boring::connect` + a new `perform_http_upgrade_async` that reads the 101 response byte-by-byte so it never swallows tunnel data), yielding a `tokio::io::split`-able `WebTunnelAsyncStream`. Shared the HTTP-upgrade request serialization between the sync and async paths. Added an async e2e round-trip test and the `ws_tunnel/1MiB` bench case (~666 MiB/s). `verify` semantics are unchanged (the bench passes `verify=false` against the loopback fixture, matching the crate's existing sync e2e test). Bench now covers 5 of 7 transports; only Hysteria2/TUIC (QUIC proxy-server loopback) remain.
- 2026-06-05: enabled + landed the MASQUE bench case. Added a `root_certificate_pem: Option<String>` trust-anchor option to `MasqueConfig` (mirrors Trojan/AnyTLS: `cert_store_mut().add_cert` — pins a self-signed/private-CA proxy cert with TLS verification left ON, does NOT relax it), gated the `#[cfg(test)]` loopback-verification relax on `root_certificate_pem.is_none()` so the pin-and-verify path is exercised, and exposed the fixture's cert via `MasqueH2ConnectUdpFixture::certificate_pem()`. Added positive (pinned cert verifies+tunnels) and negative (unrelated cert fails verification) in-crate tests, and the `masque_h2_connect_tcp/1MiB` bench case (~544 MiB/s). Security-reviewed (no production verification weakening). WS-tunnel remains deferred (needs an async client). Bench now covers 4 transports.
- 2026-06-05: investigated extending the bench to MASQUE + WS-tunnel (multi-agent recon → implement → verify). Both are blocked on enabling work, not a fixture gap, so no cases were added (empirically confirmed rather than assumed): MASQUE fails the H2 TLS handshake from an external crate (`CERTIFICATE_VERIFY_FAILED`; cert relaxation is `#[cfg(test)]`-only inside `ripdpi-masque`, fixture cert unexposed) — fix is a `root_certificate_pem` trust option on `MasqueConfig` (mirror Trojan/AnyTLS) + a fixture cert getter; WS-tunnel's client (`ripdpi-webtunnel`, not the Telegram-specific `ripdpi-ws-tunnel`) is synchronous (boring `SslStream<std::net::TcpStream>`, std `Read`/`Write`), so it can't `tokio::io::split` into the async harness — needs an async client variant. Bench module doc updated with both precise reasons.
- 2026-06-05: root-caused and fixed the ShadowTLS collapse — `ShadowTlsHmac` re-hashed its whole accumulated buffer on every `digest()` (O(n²) in frame count + unbounded per-connection memory), now an incremental `ring::hmac::Context`; and `ShadowTlsStream` shared one `pending_frame` between read and write (corrupting data under concurrent split), now separate read/write frame state. Added a full-duplex regression test and re-enabled the ShadowTLS bench case (~0.5 MiB/s → ~315 MiB/s). The throughput task now covers 3 transports; Hysteria2/TUIC still need a QUIC proxy-server loopback; MASQUE/WS-tunnel need their clients wired to the existing fixtures.
- 2026-06-05: audit (source-verified). Criterion 1 confirmed [x]: `native/rust/crates/ripdpi-bench/benches/protocol_throughput.rs` exists with all 7 transport bench functions (VLESS+Reality, VLESS-over-xHTTP, ShadowTLS v3, MASQUE H2 CONNECT-TCP, WS-tunnel, Hysteria2, TUIC), each driving a real loopback fixture with a 1 MiB full-duplex roundtrip. Criteria 2-3 confirmed [~]: `scripts/ci/rust-bench-baseline.json` exists (correct path) and CI wiring is present (`rust-criterion-bench` job in `ci.yml` with `--only-prefix 'protocol-throughput/'` enforced lane on `schedule` and `--only-prefix` implemented in `check-criterion-regressions.py`), but the file contains only 2 non-transport keys (`cli-parse_basic`, `json-config-parse_command-line-payload`) — the 7 `protocol-throughput/*` entries are absent, pending one reference-runner capture run. Status upgraded from `todo` to `doing` (criterion 1 fully done, criteria 2-3 wired-pending-CI-capture).
