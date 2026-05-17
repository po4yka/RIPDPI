# SPEC — `ripdpi-naiveproxy`

## Scope

A subprocess helper that exposes a local SOCKS5 listener and tunnels TCP through an upstream HTTPS proxy via `CONNECT`. Distributed alongside the klzgrad/naiveproxy binary, but the helper itself runs as a separate process under the Android service supervisor.

See `docs/native/relay-naiveproxy-runtime.md` for the operational model.

## Upstream

- klzgrad/naiveproxy (https://github.com/klzgrad/naiveproxy)
- Pin recorded in `SPEC_VERSION.md`

## Standards used

- SOCKS5 (RFC 1928, RFC 1929)
- HTTPS / HTTP CONNECT (RFC 7231)
- TLS (rustls + webpki-roots)

## Helper contract

Helper emits two well-known stdout markers:

- `RIPDPI-READY` — listener is bound and handshake completed
- `RIPDPI-ERROR <code>` — structured failure with classification text

The Android side (`NaiveProxyManager`, `SubprocessSocksRelayManager`) classifies failures into DNS / TLS / HTTP CONNECT / auth categories.

A planned structured `RIPDPI-PROBE` JSON contract is tracked in `docs/tasks/issues/make-naiveproxy-helper-probe-return-structured-version-json.md`.

## Known divergences from upstream

- This helper is not a full naiveproxy port; it shells SOCKS5↔CONNECT. Browser-engine features are explicitly out of scope.

## Non-goals

- Embedding NaiveProxy as a JNI library.
- WebView / Chromium-derived functionality.
