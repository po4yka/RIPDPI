# Outbound TLS ECH Step 0 Plan

## Normative Inputs

- RFC 9849, "TLS Encrypted Client Hello", is the published successor of `draft-ietf-tls-esni`; Datatracker redirects the draft page to RFC 9849 as of 2026-05-27. Relevant requirements: ECH uses HPKE and defines the length-prefixed `ECHConfigList` in Section 4; rejected ECH handshakes authenticate `ECHConfig.contents.public_name` and must fail rather than disable ECH in Section 6.1.6; HPKE mandatory suite requirements are in Section 9. Source: https://datatracker.ietf.org/doc/rfc9849/
- RFC 9848, "Bootstrapping TLS Encrypted ClientHello with DNS Service Bindings", defines the DNS `ech` SvcParam. Relevant requirements: Section 3 says the `ech` SvcParam value is an `ECHConfigList`; Section 5.1 disables fallback behavior when ECH-bearing SVCB succeeds; Section 5.3 says clients supporting ECH must not issue TLS ClientHello before SVCB resolution completes; Section 8 warns plaintext DNS reveals the server name. Source: https://www.rfc-editor.org/rfc/rfc9848
- RFC 9460 defines SVCB/HTTPS RR structure and client handling. The existing parser in `ripdpi-dns-resolver::https_service_binding` is the implementation point to reuse, not duplicate. Source: https://www.rfc-editor.org/rfc/rfc9460
- RFC 9180 defines HPKE. RIPDPI must not implement HPKE for this work; `boring`/Rustls backend ECH APIs own HPKE internals. Source: https://www.rfc-editor.org/rfc/rfc9180
- `boring` 5.1.0 exposes the client-ECH API needed here: `ConnectConfiguration::set_ech_config_list`, `get_ech_retry_configs`, `get_ech_name_override`, `ech_accepted`, and `set_enable_ech_grease`. The docs explicitly say clients should verify the server certificate against the ECH name override on rejection, retry with server retry configs, and fail if retry also fails. Source: https://docs.rs/boring/5.1.0/boring/ssl/struct.ConnectConfiguration.html

## Current Workspace Inventory

| Site | Classification | Evidence | Reason |
|------|----------------|----------|--------|
| `ripdpi-masque` H2 fallback | IN | `native/rust/crates/ripdpi-masque/src/h2.rs` calls `configure_boring_ech` from an optional `MasqueConfig.ech_config`. | MASQUE is explicitly in scope and already has the storage slot/backend hook, but no facade population or retry flow. |
| `ripdpi-masque` H3 | IN | `native/rust/crates/ripdpi-masque/src/h3/transport.rs` maps optional `MasqueConfig.ech_config` into Rustls `EchMode::Enable`. | MASQUE QUIC is explicitly in scope and needs the same resolved ECH material as H2, with backend capability handling. |
| `ripdpi-naiveproxy` upstream H2 CONNECT | IN | `native/rust/crates/ripdpi-naiveproxy/src/connect_tunnel.rs` uses `tokio_rustls::TlsConnector` with `config.tls_config`. | NaiveProxy `connect_tunnel` is explicitly in scope and currently has no ECH input or GREASE policy. |
| DoH client, reqwest path | IN | `native/rust/crates/ripdpi-dns-resolver/src/transport/client.rs` builds reqwest clients with a Rustls config. | DoH outbound TLS is explicitly in scope; resolver HTTPS RR lookups for ECH must not recurse into plaintext DNS and the DoH TLS connection itself should use real ECH or GREASE where policy enables it. |
| DoH client, manual HTTP/1.1 path | IN | `native/rust/crates/ripdpi-dns-resolver/src/resolver/doh/manual_exchange.rs` connects `tokio_rustls::TlsConnector` directly. | The manual DoH path is the other outbound DoH TLS path and needs the same facade policy or an explicit tested opt-out to avoid split behavior. |
| Google Apps Script domain fronter | IN | `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter/tls.rs` opens TLS to `front_domain` via `tokio_rustls`. | Apps Script domain-fronter is explicitly in scope; the facade must treat the front domain as the public outbound TLS authority and avoid plaintext ClientHello by default. |
| `ripdpi-xhttp` TLS mode | REVIEW | `native/rust/crates/ripdpi-xhttp/src/connect.rs` already calls `configure_boring_ech` from `config.ech_config`; Reality mode calls a separate Reality TLS path. | This is a current-state divergence from the prompt statement that other outbound TLS sites do not touch ECH. Plain TLS xHTTP is an outbound TLS site but was not listed in the mandatory IN set; Reality is a non-goal. I propose leaving xHTTP TLS unchanged in this goal unless the review expands scope. |
| VLESS Reality | OUT | `native/rust/crates/ripdpi-xhttp/src/connect.rs` delegates Reality to `ripdpi_vless::reality::connect_reality_tls_over`. | Explicit non-goal because Reality has its own SNI-cover scheme and needs separate analysis. |
| ShadowTLS | OUT | `native/rust/crates/ripdpi-relay-tls-transports/src/shadowtls.rs` owns the ShadowTLS outbound. | Explicit non-goal because ShadowTLS has its own SNI-cover scheme and needs separate analysis. |
| Trojan outbound | REVIEW | `native/rust/crates/ripdpi-trojan/src/lib.rs` uses `ripdpi_tls_profiles::configure_builder` and `tokio_boring::connect`. | This is a conventional outbound TLS transport but not named in the IN list. I propose excluding it from this goal unless the review changes the scope. |
| Android owned-stack fetch ECH probe | OUT | `native/rust/crates/ripdpi-android-fetch-adapter/src/native_ech.rs` is a request-driven ECH handshake adapter. | It is already an explicit ECH diagnostic/fetch path, not a generic outbound TLS site needing facade population. |
| Diagnostics TLS ECH spike/probe | OUT | `native/rust/crates/ripdpi-diagnostics-tls/src/ech_spike.rs` is diagnostics surface. | Diagnostics probing is evidence/oracle surface, not a production outbound transport to wire in this goal. |

## Small Facade Surface

The facade belongs in `ripdpi-tls-profiles` because the crate already owns TLS profile builders and low-level `ech.rs` config helpers used by MASQUE/xHTTP. Add only enough surface to orchestrate existing components:

```rust
pub struct EchPolicy {
    pub enabled: bool,
    pub grease_when_unavailable: bool,
    pub backend_opt_out: bool,
}

pub struct EchLookupContext {
    pub inner_name: String,
    pub encrypted_dns_endpoint: EncryptedDnsEndpoint,
    pub transport: TransportConfig,
}

pub enum EchSetup {
    Real(OutboundEchConfig),
    Grease,
    OptedOut,
}

pub fn resolve_outbound_ech(context: &EchLookupContext, policy: EchPolicy) -> Result<EchSetup, EchFacadeError>;
pub fn configure_ech(config: &mut boring::ssl::ConnectConfiguration, setup: &EchSetup) -> Result<(), EchFacadeError>;
pub fn classify_ech_rejection(config: &boring::ssl::ConnectConfiguration) -> Result<Option<EchRetry>, EchFacadeError>;
```

Implementation notes for review: `resolve_outbound_ech` must call `ripdpi-diagnostics-dns::dns::resolve_https_ech_configs_via_encrypted_dns_with_endpoint` or move an equivalent encrypted-DNS HTTPS query into a shared crate if dependency direction requires it; it must not introduce any UDP/plain resolver fallback. `configure_ech` initially targets Boring because the prompt names the Boring 5.1.0 API; Rustls consumers need a parallel adapter that maps `EchSetup::Real` into `EchMode::Enable` and `EchSetup::Grease` into the backend-supported GREASE equivalent or a tested opt-out if Rustls cannot GREASE. `classify_ech_rejection` must use Boring retry/name-override accessors rather than matching only error strings.

Dependency concern: `ripdpi-tls-profiles` currently does not depend on `ripdpi-diagnostics-dns` or `ripdpi-dns-resolver`. If the facade imports the encrypted-DNS endpoint types directly, add the smallest workspace dependencies to `ripdpi-tls-profiles`. If that creates an undesirable edge, split only the encrypted HTTPS RR resolver function into a small existing resolver crate; do not copy parser or CDN fallback code.

## Test Plan

1. Facade resolver refuses plaintext fallback: add a `ripdpi-tls-profiles` unit test with a fake resolver trait or fixture that has only plaintext DNS available and assert `resolve_outbound_ech` returns a hard failure for an inner-SNI name. Normative basis: RFC 9848 Section 8 says DNS can reveal the server name, and the project requirement forbids plaintext HTTPS RR for ECH.
2. Facade extracts real config from encrypted HTTPS RR: add a fixture HTTPS RR response using the existing `ripdpi-dns-resolver::https_service_binding` parser and assert `resolve_outbound_ech` yields `EchSetup::Real(OutboundEchConfig)` with byte-identical `ECHConfigList`. Normative basis: RFC 9848 Section 3 and RFC 9849 Section 4.
3. Boring real config application: add a failing test around `configure_ech` on `boring::ssl::ConnectConfiguration` and assert `set_ech_config_list` accepts the fixture. Normative/API basis: Boring `set_ech_config_list` and RFC 9849 Section 4.
4. GREASE default: add a failing test where encrypted DNS succeeds without `ech` and policy is enabled, then assert Boring `set_enable_ech_grease(true)` is invoked through an injectable backend/test double or observable first-flight fixture. Normative/API basis: Boring `set_enable_ech_grease`; project requirement says never plain ClientHello on in-scope sites.
5. Rejection retry flow: add a local-network-fixture TLS server that rejects the first ECH attempt with retry configs, assert the client verifies `get_ech_name_override` public name, retries once with `get_ech_retry_configs`, and succeeds only when `ech_accepted` is true. Normative/API basis: RFC 9849 Section 6.1.6 and Boring `get_ech_retry_configs`/`get_ech_name_override`/`ech_accepted`.
6. Rejection does not silently fall back: use the same fixture but make the retry reject or public-name verification fail; assert the facade returns an ECH failure and never opens a non-ECH tunnel. Normative basis: RFC 9849 Section 6.1.6 and Boring docs for retry failure.
7. MASQUE H2/H3 integration: add fixture E2E tests proving `MasqueConfig.ech_config` is populated through the facade for H2 fallback and H3, and that the H2 Boring path exercises the retry handler. Normative basis: RFC 9848 Section 5.3 and Boring retry APIs.
8. NaiveProxy integration: add a local-network-fixture Naive H2 server and assert upstream `connect_tunnel` sends real ECH when HTTPS RR publishes `ech`, otherwise GREASE when enabled. Normative basis: RFC 9848 Sections 3 and 5.3 plus Boring/Rustls backend API used by the selected implementation.
9. DoH integration: add per-path tests for reqwest DoH and manual DoH showing DoH TLS uses real ECH or GREASE while the ECH HTTPS RR lookup itself is encrypted-DNS-only and non-recursive. Normative basis: RFC 9848 Section 8 and project encrypted-DNS requirement.
10. Apps Script integration: add a domain-fronting fixture where TLS authority is the front domain and HTTP `Host` remains `script.google.com`; assert the facade looks up/configures ECH for the TLS front domain and does not leak the configured front via plaintext HTTPS RR. Normative basis: RFC 9848 Sections 5.2 and 8.
11. Per-backend opt-out: add tests proving opt-out is explicit, default-off, logged/returned in `EchSetup::OptedOut`, and never selected for the mandatory in-scope sites unless their backend lacks the required ECH/GREASE API. Project basis: per-backend opt-out is in scope but disabling GREASE by default is forbidden.
12. Goldens and interop: use existing golden harness conventions with `RIPDPI_BLESS_GOLDENS` for facade decisions and run nightly cross-interop against `cloudflare-ech.com` after deterministic local tests pass. Normative basis: RFC 9848/RFC 9849 behavior; external interop is not a replacement for local fixture assertions.

## TDD/Commit Order

1. `feat(tls-ech): configure outbound ech facade` after failing facade tests demonstrate encrypted-DNS-only lookup, real config application, and GREASE default.
2. `feat(tls-ech): retry rejected ech once` after failing rejection/public-name/retry fixture tests are green.
3. `feat(tls-ech): wire masque outbound ech` after MASQUE H2/H3 fixture E2E is green.
4. `feat(tls-ech): wire remaining scoped outbound tls` after NaiveProxy, DoH, and Apps Script fixtures are green.
5. `docs(tls-ech): document backend opt-out policy` after opt-out tests and facade README updates are green.

Each implementation slice must start with the failing test and captured `cargo test` output, then minimal implementation, then green `cargo test`, `cargo clippy --workspace -- -D warnings`, `cargo fmt --check`, and applicable `cargo deny check` before its commit.
