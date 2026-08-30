# SPEC — `ripdpi-naiveproxy`

## Scope

A subprocess helper that exposes a local SOCKS5 listener and tunnels TCP through an upstream HTTPS proxy via `CONNECT`. Distributed alongside the klzgrad/naiveproxy binary, but the helper itself runs as a separate process under the Android service supervisor.

See `docs/native/relay-naiveproxy-runtime.md` for the operational model.

The completion target is documented in `README.md`. The architecture remains subprocess-based through `NaiveProxyManager.kt`; this crate is not an in-process relay backend.

## Upstream

- klzgrad/naiveproxy (https://github.com/klzgrad/naiveproxy)
- Pin recorded in `SPEC_VERSION.md`

## Standards used

- SOCKS5 (RFC 1928, RFC 1929)
- HTTPS / HTTP CONNECT (RFC 7231) plus HTTP/2 CONNECT over `h2` ALPN
- TLS (rustls + webpki-roots)
- NaiveProxy Variant1 payload padding, as documented by upstream klzgrad/naiveproxy

## Helper contract

Helper stdout markers:

- `RIPDPI-READY` — listener is bound and handshake completed
- `RIPDPI-ERROR <code>` — structured failure with classification text
- `RIPDPI-PROBE <json>` — `--probe` capability report including `socks5-listener`, `http-front-listener`, `h2-connect-upstream`, `naive-padding`, `structured-error`, and `ready-signal`

The Android side (`NaiveProxyManager`, `SubprocessSocksRelayManager`) requires a valid schema-1 `--probe` result before every launch and classifies compatibility refusal separately from DNS / TLS / HTTP CONNECT / auth runtime failures. Existing readiness and structured-error processing begins only after that preflight succeeds.

## Known divergences from upstream

- This helper is not a full naiveproxy port; it shells SOCKS5↔CONNECT. Browser-engine features are explicitly out of scope.
- The helper uses the workspace Rust TLS/HTTP stack rather than Chromium networking, so Chromium-identical TLS/H2 fingerprinting is explicitly out of scope.
- Upstream connections use HTTP/2 CONNECT with request/response padding negotiation; payload padding is active only when the upstream response opts in with NaiveProxy padding headers.

## Non-goals

- Embedding NaiveProxy as a JNI library.
- WebView / Chromium-derived functionality.
