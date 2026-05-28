# ADR 0001: VLESS REALITY ECH Policy

> Status: proposed. Decision date: 2026-05-28.

## Context

REALITY deliberately keeps an externally visible cover name in the TLS ClientHello: XTLS documents `serverNames` as the allowed client SNI values and the outbound `serverName` as the client-side value, and describes REALITY as camouflaging a target site's TLS appearance rather than hiding the SNI. [XTLS REALITY README](https://github.com/XTLS/REALITY/blob/main/README.en.md#vless-xtls-utls-reality-example-for-xray-core), [Project X REALITY docs](https://xtls.github.io/en/config/transports/reality.html#realityobject)

REALITY authenticates the proxy by data embedded in the TLS SessionID: Xray's client builds a 32-byte `SessionId`, stores version bytes, timestamp, and `ShortId`, derives an auth key from X25519 ECDH plus HKDF, seals the first 16 bytes with AES-GCM using the ClientHello as AAD, and writes the sealed value back into `hello.Raw[39:]`. [Xray reality.go at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/reality.go#L139-L175), [ObjShadow source analysis](https://objshadow.pages.dev/en/posts/how-reality-works/#server-side-authentication)

REALITY's server-side failure path is also part of the camouflage contract: Project X says failed authentication is directly forwarded to the configured `target`, and ObjShadow's source walk shows the server breaks out and copies bytes to the masquerade target when the ClientHello is not accepted as a valid REALITY client. [Project X REALITY docs](https://xtls.github.io/en/config/transports/reality.html#realityobject), [ObjShadow source analysis](https://objshadow.pages.dev/en/posts/how-reality-works/#server-side-authentication)

ECH has a different purpose and trust model: RFC 9849 says ECH encrypts `ClientHelloInner` under a server public key, uses `ClientHelloOuter` as the public envelope, and protects the SNI and other sensitive fields inside an anonymity set. [RFC 9849 §1](https://www.rfc-editor.org/rfc/rfc9849.html#section-1), [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2)

RFC 9849 is the published March 2026 successor to the `draft-ietf-tls-esni` ECH work, so this ADR treats RFC 9849 as the current ECH specification. [RFC 9849](https://www.rfc-editor.org/rfc/rfc9849.html), [RFC Editor info](https://www.rfc-editor.org/info/rfc9849)

ECH requires an ECH configuration published or otherwise provisioned by the client-facing server: RFC 9849 says a client-facing server enables ECH by publishing an ECH configuration with an HPKE public key and metadata, and RFC 9848 defines the DNS HTTPS/SVCB `ech` SvcParam as an `ECHConfigList`. [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2), [RFC 9849 §4](https://www.rfc-editor.org/rfc/rfc9849.html#section-4), [RFC 9848 §3](https://www.rfc-editor.org/rfc/rfc9848.html#section-3)

In RIPDPI, REALITY already consumes the browser-fingerprint layer: `connect_reality_tls_inner` calls `ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)` before disabling normal certificate verification, creating the `ConnectConfiguration`, and installing the REALITY ClientHello hook. [ripdpi-vless reality.rs](../../native/rust/crates/ripdpi-vless/src/reality.rs#L43-L80)

The REALITY hook is specifically a SessionID mutation hook, not a general ECH hook: it installs a BoringSSL `client_hello_cb`, obtains the active X25519 private key, and delegates the SessionID seal to `reality_seal`. [ripdpi-vless reality_hook.rs](../../native/rust/crates/ripdpi-vless/src/reality_hook.rs#L1-L30), [ripdpi-vless reality_hook.rs](../../native/rust/crates/ripdpi-vless/src/reality_hook.rs#L201-L230)

The existing outbound ECH facade is intentionally a separate `ripdpi-tls-profiles` surface for real ECH setup, encrypted-DNS HTTPS/SVCB lookup, retry handling, and GREASE; its README names VLESS REALITY as out of scope because REALITY has its own SNI-cover scheme. [ripdpi-tls-profiles README](../../native/rust/crates/ripdpi-tls-profiles/README.md#ripdpi-tls-profiles), [Outbound TLS ECH Step 0 Plan](../architecture/tls-ech-step0-plan.md#current-workspace-inventory)

Current Xray state matches that split: PR #3813 was merged on 2025-07-26 as "TLS client & server: Support Encrypted Client Hello (ECH)", current generic TLS config contains `ech_server_keys`, `ech_config_list`, and `ech_socket_settings`, and current REALITY config contains `Fingerprint`, `server_name`, `public_key`, `short_id`, and related REALITY fields but no ECH option. [Xray PR #3813](https://github.com/XTLS/Xray-core/pull/3813), [Xray TLS config at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/tls/config.proto#L80-L87), [Xray REALITY config at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/config.proto#L9-L34)

## Decision

RIPDPI will not emit real ECH for VLESS REALITY. Real ECH is not meaningful for this transport because REALITY routes and authenticates with the visible cover `serverName` plus sealed SessionID auth, while ECH hides an inner SNI for a backend behind a client-facing ECH provider. [Xray reality.go at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/reality.go#L122-L175), [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2)

RIPDPI will only allow ECH GREASE on REALITY, and only as a cover-domain/profile parity decision. If the selected REALITY TLS fingerprint profile and cover-domain evidence say the mimicked browser would carry an `encrypted_client_hello` outer extension for that cover class, REALITY may enable GREASE; if the selected profile is non-ECH or the cover-domain policy says the cover should look non-ECH, REALITY emits no ECH extension. [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2), [ripdpi-tls-profiles profile.rs](../../native/rust/crates/ripdpi-tls-profiles/src/profile.rs#L3-L34), [ripdpi-tls-profiles firefox.rs](../../native/rust/crates/ripdpi-tls-profiles/src/firefox.rs#L37-L67)

RIPDPI will not use the outbound ECH facade for REALITY. The facade remains for real SNI hiding on operator-controlled or otherwise valid ECH endpoints, while REALITY's ECH decision is a TLS-profile parity property owned by `ripdpi-tls-profiles` and invoked from the REALITY connect path after `configure_builder` creates the profile-shaped connector configuration. [ripdpi-tls-profiles README](../../native/rust/crates/ripdpi-tls-profiles/README.md#ripdpi-tls-profiles), [ripdpi-vless reality.rs](../../native/rust/crates/ripdpi-vless/src/reality.rs#L46-L59)

REALITY's fallback state machine remains authoritative. GREASE-only ECH must not create an ECH retry loop, must not consume retry configs, and must not reinterpret `SSL_R_ECH_REJECTED` as a transport recovery path; if GREASE breaks a cover/profile combination, that combination is a profile-parity failure and should disable GREASE for that profile or cover class. [RFC 9849 §6.1.6](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6), [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2), [Project X REALITY docs](https://xtls.github.io/en/config/transports/reality.html#realityobject)

This answers the "what inner SNI would ECH hide?" question directly: for REALITY, the only coherent inner name would be the same cover name already visible in `ClientHelloOuter`, which hides nothing; using a proxy-owned inner name would require an operator-controlled ECHConfig and would no longer be the third-party cover-domain REALITY model. [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2), [RFC 9849 §4](https://www.rfc-editor.org/rfc/rfc9849.html#section-4), [XTLS REALITY README](https://github.com/XTLS/REALITY/blob/main/README.en.md#vless-xtls-utls-reality-example-for-xray-core)

## Alternatives Considered

### Real ECH on REALITY

Rejected. A real ECH attempt requires an ECHConfig for the client-facing cover and encrypts a `ClientHelloInner` for an ECH backend, while REALITY's proxy admission happens before any hidden inner SNI routing decision and is keyed by SessionID auth in the visible ClientHello. [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2), [RFC 9849 §7.1](https://www.rfc-editor.org/rfc/rfc9849.html#section-7.1), [Xray reality.go at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/reality.go#L139-L175)

Rejected also because the cover domain is normally not operator-controlled in the REALITY deployment model: XTLS describes REALITY as being able to point at other people's websites without buying a domain or configuring a TLS server, but ECH's published ECHConfig is controlled by the client-facing server for that domain. [XTLS REALITY README](https://github.com/XTLS/REALITY/blob/main/README.en.md#vless-xtls-utls-reality-example-for-xray-core), [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2), [RFC 9849 §4](https://www.rfc-editor.org/rfc/rfc9849.html#section-4)

Rejected a third time because real ECH rejection has its own retry semantics: RFC 9849 says a rejected ECH handshake proceeds with `ClientHelloOuter` only to obtain retry configs or a secure disable signal, then the client retries on a new transport connection or disables ECH, while REALITY uses a real-cover fallback/crawler path when its own auth does not verify. [RFC 9849 §6.1.6](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6), [Xray reality.go at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/reality.go#L177-L185), [Project X REALITY docs](https://xtls.github.io/en/config/transports/reality.html#realityobject)

### GREASE-only on REALITY

Accepted, but only conditionally. RFC 9849 defines GREASE ECH so an ECH-capable client connecting to a non-ECH server can appear to use ECH and avoid making ECH connections stand out, and that maps to REALITY only as outer ClientHello fingerprint parity rather than SNI privacy. [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2)

Unconditional GREASE is rejected. A cover-domain mimic can be wrong in both directions: a profile that omits ECH for an ECH-heavy cover can stand out, and a profile that always emits an ECH extension for a non-ECH cover can also stand out from the specific cover population being imitated; therefore the decision belongs in cover-domain/profile parity data, not in the transport protocol. [RFC 9849 §10.10.4](https://www.rfc-editor.org/rfc/rfc9849.html#section-10.10.4), [ripdpi-tls-profiles profile.rs](../../native/rust/crates/ripdpi-tls-profiles/src/profile.rs#L65-L87)

### No ECH on REALITY

Rejected as a permanent policy because browser parity can require an `encrypted_client_hello` outer extension for some cover-domain/profile combinations. [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2), [ripdpi-tls-profiles firefox.rs](../../native/rust/crates/ripdpi-tls-profiles/src/firefox.rs#L37-L67)

Accepted as the default for profiles and covers without explicit ECH parity evidence. The current RIPDPI Reality path already produces a profile-shaped BoringSSL ClientHello without invoking ECH facade resolution, and current Xray REALITY has no ECH config field even though Xray generic TLS does. [ripdpi-vless reality.rs](../../native/rust/crates/ripdpi-vless/src/reality.rs#L46-L59), [Xray REALITY config at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/config.proto#L9-L34), [Xray TLS config at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/tls/config.proto#L80-L87)

## Consequences

REALITY keeps one authentication path: the SessionID seal remains the proxy-auth signal, and ECH is never used to choose a hidden REALITY origin. [ripdpi-vless reality_seal.rs](../../native/rust/crates/ripdpi-vless/src/reality_seal.rs#L82-L144), [Xray reality.go at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/reality.go#L139-L175)

The outbound ECH facade remains simpler: it continues to own real ECH, encrypted-DNS-only HTTPS/SVCB lookup, rejection retry handling, and failure semantics for ordinary outbound TLS sites; it does not need REALITY-specific exceptions for third-party cover domains or SessionID authentication. [ripdpi-tls-profiles README](../../native/rust/crates/ripdpi-tls-profiles/README.md#ripdpi-tls-profiles), [Outbound TLS ECH Step 0 Plan](../architecture/tls-ech-step0-plan.md#small-facade-surface)

The TLS profile catalog gains the future responsibility for documenting which browser/cover combinations are ECH-parity candidates, but it must represent that as outer extension parity and must not imply that REALITY is performing real ECH privacy. [ripdpi-tls-profiles profile.rs](../../native/rust/crates/ripdpi-tls-profiles/src/profile.rs#L3-L34), [ripdpi-tls-profiles README](../../native/rust/crates/ripdpi-tls-profiles/README.md#non-goals)

Interop failures from GREASE are treated as profile data bugs or backend capability bugs rather than as reasons to run the ECH facade retry state machine inside REALITY. [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2), [ripdpi-tls-profiles ech.rs](../../native/rust/crates/ripdpi-tls-profiles/src/ech.rs#L215-L222)

## Revisit Trigger

Revisit this ADR if Xray adds an explicit REALITY ECH option, if REALITY changes its authentication/routing away from visible-SNI-plus-SessionID auth, if RIPDPI introduces an operator-controlled REALITY cover mode where it owns the ECHConfig for the public cover name, or if browser behavior makes GREASE-only visibly distinguishable from real cover-domain ECH in a way that cannot be represented as profile/cover parity metadata. [Xray REALITY config at current main `787aa767`](https://github.com/XTLS/Xray-core/blob/787aa7677b47c24f19aae84111d50ef4123072be/transport/internet/reality/config.proto#L9-L34), [RFC 9849 §3.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-3.2), [RFC 9849 §6.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.2)

## Implementation Sketch

Do not change production code in this ADR. A follow-on implementation should add a small `ripdpi-tls-profiles` decision helper that maps `(tls_fingerprint_profile, cover_server_name, cover_ech_evidence)` to `RealityEchParity::{Off, Grease}`, call it from `ripdpi-vless::reality` after `connector.configure()` and before `into_ssl(&config.server_name)`, apply only BoringSSL GREASE when the decision is `Grease`, and leave `configure_ech`, `resolve_outbound_ech`, and `prepare_ech_retry` unused on the REALITY path. [ripdpi-vless reality.rs](../../native/rust/crates/ripdpi-vless/src/reality.rs#L46-L59), [ripdpi-tls-profiles ech.rs](../../native/rust/crates/ripdpi-tls-profiles/src/ech.rs#L215-L222), [ripdpi-tls-profiles ech.rs](../../native/rust/crates/ripdpi-tls-profiles/src/ech.rs#L256-L283)
