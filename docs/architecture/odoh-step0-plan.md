# ODoH Step 0 Plan

## Scope

Add Oblivious DNS over HTTPS client mode to `native/rust/crates/ripdpi-dns-resolver` as a fifth encrypted DNS protocol beside DoH, DoT, DNSCrypt, and DoQ. The fixed architecture is ODoH over DoH: RIPDPI HPKE-encrypts a DNS wire query to an Oblivious Target, sends the encrypted `ObliviousDoHMessage` to an Oblivious Proxy over HTTPS, and decrypts the target response returned through that proxy. This is resolver-as-observer privacy, not DPI evasion and not a replacement for DNS-over-relay.

Non-goals are an ODoH server or target role, hand-written HPKE, crypto agility beyond the RFC 9230 default suite, and any product copy or diagnostics verdict that presents ODoH as a DPI-bypass transport. Same-operator proxy and target pairs are forbidden for built-ins and must be refused or explicitly warned for custom configuration because collusion or common operation removes the privacy benefit.

## Normative Inputs

- RFC 9230 Section 3 defines the deployment pieces: client, proxy, target, and target public keys, with proxy and target expected to be non-colluding. Source: https://www.rfc-editor.org/rfc/rfc9230
- RFC 9230 Section 4 defines the HTTP exchange through the proxy and the `targethost`/`targetpath` request parameters. The proxy leg must reuse RIPDPI's DoH HTTPS transport rather than creating a parallel HTTP stack. Source: https://www.rfc-editor.org/rfc/rfc9230
- RFC 9230 Section 5 defines `ObliviousDoHConfigs` and target public-key material. RIPDPI must parse provider configs with `odoh-rs` and must cache retrieved configs with freshness metadata instead of treating arbitrary bytes as a long-lived key. Source: https://www.rfc-editor.org/rfc/rfc9230
- RFC 9230 Section 6 defines `ObliviousDoHMessagePlaintext` as a DNS message plus zero padding and `ObliviousDoHMessage` as message type, key ID, and encrypted message. Query messages use type `0x01`, responses use type `0x02`, and the media type is `application/oblivious-dns-message`. Source: https://www.rfc-editor.org/rfc/rfc9230
- RFC 9230 Section 7 defines client behavior: build plaintext, deserialize target public key, encrypt the query with the derived key ID, send through the proxy, decrypt the response, and validate zero padding before using the DNS answer. Source: https://www.rfc-editor.org/rfc/rfc9230
- RFC 9230 Section 9 requires support for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, and AES-128-GCM absent another application profile. RFC 9180 Section 7 assigns the HPKE algorithm identifiers and X25519 key handling; RIPDPI must rely on `odoh-rs` and its HPKE dependency for these operations. Sources: https://www.rfc-editor.org/rfc/rfc9230 and https://www.rfc-editor.org/rfc/rfc9180
- RFC 9230 Section 11 is the security boundary for this feature: no single non-colluding server should learn both client IP and DNS contents. A proxy and target that share an operator are equivalent to plain DoH for this threat model. Source: https://www.rfc-editor.org/rfc/rfc9230

## Implementation Basis

Use `odoh-rs` directly. It implements RFC 9230, exposes client and target helpers around `ObliviousDoHMessage`, `ObliviousDoHConfigs`, `ObliviousDoHMessagePlaintext`, `encrypt_query`, and `decrypt_response`, uses an HPKE implementation internally, supports the RFC default suite, ships test vectors, and explicitly does not provide full crypto agility. RIPDPI must not call BoringSSL HPKE primitives or reproduce RFC 9230 framing by hand unless a small wrapper is needed around `odoh-rs` serialization APIs. Source: https://github.com/cloudflare/odoh-rs

Add `odoh-rs` as a workspace dependency only when the first TDD slice lands. Keep the wrapper API in a new `ripdpi-dns-resolver/src/odoh/` module so ODoH-specific config parsing, request construction, response decryption, padding validation, and non-collusion policy do not spread through the existing DoH, DoT, DoQ, or DNSCrypt implementations.

## Current Workspace Seams

Protocol dispatch belongs in `native/rust/crates/ripdpi-dns-resolver/src/resolver/dispatch.rs`, where `EncryptedDnsResolver::exchange_protocol` already switches over `EncryptedDnsProtocol::{Doh, Dot, DnsCrypt, Doq}`.

The protocol enum and endpoint shape live in `native/rust/crates/ripdpi-dns-resolver/src/types/endpoint.rs`. Add `EncryptedDnsProtocol::Odoh` and ODoH endpoint fields for proxy URL, target host, target path, config source, and operator IDs without overloading `doh_url` as both a target URL and a proxy URL.

The proxy HTTP leg should reuse the DoH transport in `native/rust/crates/ripdpi-dns-resolver/src/resolver/doh/`. The reqwest path in `request_exchange.rs` already posts binary bodies with media-type headers; the manual path in `manual_exchange.rs` is used when protected/direct connector hooks are active. ODoH should factor a small reusable "POST binary body to HTTPS URL with content type and accept type" helper so proxy requests use the same TLS/bootstrap/protected-socket behavior as DoH.

Tunnel runtime config enters native code through `native/rust/crates/ripdpi-tunnel-android/src/config/payload.rs`, maps into `ripdpi-tunnel-config::MapDnsConfig`, and builds an `EncryptedDnsResolver` in `native/rust/crates/ripdpi-tunnel-core/src/io_loop/dns_intercept/config/encrypted_dns.rs`. Kotlin/DataStore work must add ODoH proxy/target/config-source fields there and preserve `route_dns_through_socks5`, because relay-active DNS already forces the encrypted resolver through the local SOCKS5 path.

Local E2E should extend `native/rust/crates/local-network-fixture/src/dns.rs` with an ODoH target and proxy using `odoh-rs` server-side helpers. The fixture must assert proxy and target visibility separately: proxy sees client plus target metadata but not DNS contents; target sees DNS contents but not client IP.

## Config Retrieval Decision

RIPDPI must support three ODoH config sources with explicit privacy semantics: bundled reviewed configs, HTTPS/SVCB `odohconfig`, and custom user-supplied config bytes or config URL. Bundled configs are the safest built-in path because each pair can carry mandatory proxy and target operator IDs and can be reviewed for non-collusion before release.

HTTPS/SVCB retrieval is allowed as a client-config acquisition path, using the existing encrypted resolver machinery and `https_service_binding` parser surface where possible. This can reveal interest in an ODoH target to the resolver used for config lookup and to the config-fetch network path, but it does not reveal protected DNS query contents. The implementation must not silently fall back to system/plain DNS for config retrieval.

Fetching configs "via the proxy" is not the initial default because RFC 9230's proxy request template is for forwarding ODoH messages to a target, not a general-purpose config-discovery mechanism. A future provider-specific proxy-mediated config source can be added only with tests proving the proxy receives no DNS message contents and the target config is authenticated by HTTPS and `odoh-rs` parsing.

Custom config bytes or URLs are allowed for advanced users, but they must pass the same supported-suite selection, key ID derivation, freshness, and non-collusion checks. If custom operator IDs are omitted, the UI and native validation must warn; obvious same-host or same-registrable-domain proxy and target values must be refused.

## Non-Collusion Policy

Built-in ODoH entries require `proxy_operator_id` and `target_operator_id`; equal IDs are an initialization error. Do not ship a default ODoH preset unless the proxy and target are operated independently. Do not derive operator IDs from display labels alone.

Custom ODoH entries should require explicit operator IDs when created through Kotlin settings. If imported legacy or raw config data lacks operator IDs, native validation must emit a structured warning state and refuse pairs where proxy host, target host, or known provider IDs clearly match.

The README and user-facing text must say ODoH hides query contents from the proxy and client IP from the target only when proxy and target do not collude. It must also say ODoH does not hide traffic from a censor better than ordinary HTTPS to the proxy and is not a DPI-evasion transport.

## TDD Plan

Slice 1, codec and HPKE wrapper: first add failing `ripdpi-dns-resolver` tests that parse `odoh-rs` `tests/test-vectors.json`, verify `ObliviousDoHConfigs`, expected key ID, query message type `0x01`, response message type `0x02`, zero padding, and decrypt known responses with `odoh-rs`. Run the targeted failing `cargo test -p ripdpi-dns-resolver odoh`; implement only the wrapper and dependency wiring; rerun the same test plus `cargo fmt --check` and targeted clippy; commit `feat(odoh): add message codec hpke wrapper`. Normative basis: RFC 9230 Sections 5, 6, 7, and 9; RFC 9180 Section 7.

Slice 2, configs and retrieval: first add failing tests for parsing multiple `ObliviousDoHConfigs`, selecting the supported default suite, deriving `key_id`, rejecting unsupported or stale configs, using bundled bytes, using HTTPS/SVCB `odohconfig`, and refusing plaintext fallback for config retrieval. Implement config-source parsing/cache/freshness without exchange logic; verify targeted tests, fmt, clippy, and `cargo deny check`; commit `feat(odoh): resolve target configs`. Normative basis: RFC 9230 Sections 3 and 5.

Slice 3, ODoH exchange over DoH transport: first add fixture tests where a local ODoH client posts an encrypted body to a fixture proxy with `targethost` and `targetpath`, the proxy forwards to a target, the target decrypts and answers, and the client decrypts the response. The test must prove the proxy request uses `application/oblivious-dns-message` and the same connector path as DoH, including relay/SOCKS routing when configured. Implement `EncryptedDnsProtocol::Odoh`, endpoint validation, and `exchange_odoh`; verify targeted E2E, fmt, clippy, and relevant resolver tests; commit `feat(odoh): exchange queries through proxy`. Normative basis: RFC 9230 Sections 4, 6, 7, and 8.

Slice 4, non-collusion guard: first add tests that built-in same-operator pairs fail initialization, custom missing operator IDs produce a warning state, and obvious same-host or same-domain proxy/target pairs are refused. Implement native guard plus structured errors/warnings and README updates; verify targeted tests, fmt, clippy, and static docs checks; commit `feat(odoh): guard non-colluding pairs`. Normative basis: RFC 9230 Sections 3 and 11.

Slice 5, Kotlin config and relay composition: first add failing JVM/native config contract tests for protocol mode `odoh`, proxy URL, target host/path, config source, operator IDs, config bytes or URL, and relay-active `route_dns_through_socks5`. Add UI tests for warning/refusal states and all locale strings. Implement proto/DataStore/config payload/native mapping and mode selection; verify Gradle unit tests, native targeted tests, `cargo fmt --check`, targeted clippy, `cargo deny check`, and `./gradlew staticAnalysis`; commit `feat(odoh): expose client mode in settings`. Normative basis: RFC 9230 Sections 3, 4, 5, 7, and 11.

## Done Evidence

The feature is not complete until the current checkout proves all of the following: `cargo test -p ripdpi-dns-resolver odoh` passes against non-tautological RFC 9230 vectors from `odoh-rs`; fixture proxy+target E2E passes and shows proxy and target visibility boundaries; non-collusion guard tests cover built-in and custom configuration; relay-active ODoH proxy leg uses the existing relay/SOCKS path and fails closed when relay DNS is required; Kotlin settings persist and map all ODoH fields; README text states resolver privacy, non-collusion, and non-goals; `cargo fmt --check`, relevant clippy, `cargo deny check`, and Gradle static analysis pass for touched slices; and each slice is committed green with Conventional Commits scope `odoh`.
