# rustls TLS Audit

Audited: 2026-03-24 against rustls 0.23.37, tokio-rustls 0.26, ring backend.

## Summary

rustls is the sole TLS library in the workspace. There is no native-tls, openssl,
or boring dependency. No migration is needed. This document maps the TLS landscape,
explains why the path optimization pipeline correctly avoids rustls, and identifies
improvement opportunities.

## TLS Dependency Inventory

All declared in workspace `Cargo.toml` (`native/rust/Cargo.toml`):

| Dependency | Version | Features | Used By |
|------------|---------|----------|---------|
| rustls | 0.23.37 | `ring`, `std`, `tls12` | ripdpi-monitor, ripdpi-dns-resolver |
| tokio-rustls | 0.26 | (no default) | ripdpi-dns-resolver (DoT) |
| webpki-roots | 0.26 | -- | ripdpi-monitor, ripdpi-dns-resolver |
| reqwest | 0.12 | `rustls-tls`, `http2`, `socks` | ripdpi-dns-resolver (DoH) |
| tungstenite | 0.24 | `handshake`, `rustls-tls-webpki-roots` | ripdpi-ws-tunnel (WSS) |

Zero matches for `native-tls`, `openssl`, or `boring` in the workspace.

## TLS Operation Map

### Real TLS Handshakes (rustls)

| Crate | File | Operation | rustls API |
|-------|------|-----------|------------|
| ripdpi-monitor | `src/tls.rs` | TLS 1.2/1.3 split probes | `ClientConnection::new()` + `complete_io()` |
| ripdpi-dns-resolver | `src/transport.rs` | DoH via reqwest | `use_rustls_tls()` / `use_preconfigured_tls()` |
| ripdpi-dns-resolver | `src/resolver.rs` | DoT via tokio-rustls | `TlsConnector::from(config).connect()` |
| ripdpi-ws-tunnel | `src/connect.rs` | WSS to Telegram DCs | `tungstenite::client_tls()` (rustls backend) |

### Raw Byte TLS Manipulation (NOT rustls -- by design)

| Crate | File | Operation |
|-------|------|-----------|
| ripdpi-packets | `src/tls.rs` | ClientHello parsing, SNI extraction, 6 mutation functions |
| ripdpi-packets | `src/fake_profiles.rs` | 7 static ClientHello binary blobs |
| ripdpi-desync | `src/fake.rs` | Fake packet construction (FM_RAND/ORIG/RNDSNI/DUPSID/PADENCAP) |
| ripdpi-desync | `src/tls_prelude.rs` | TLS record splitting (TlsRec/TlsRandRec) |
| ripdpi-runtime | `src/runtime/relay/tls_boundary.rs` | TLS record boundary tracking |

### Why the DPI Bypass Pipeline Correctly Avoids rustls

The proxy is a TCP relay that NEVER terminates TLS. The data path:

```
Client --[TLS]--> RIPDPI proxy (TCP relay) --[same TLS bytes, fragmented]--> Server
```

The proxy only:
1. Inspects the first flight (ClientHello) to extract SNI -- byte parsing, not decryption
2. Constructs FAKE ClientHello packets for DPI evasion -- intentionally non-compliant
3. Splits/fragments/reorders TCP segments containing TLS records

Using rustls for these operations would be architecturally wrong:
- rustls enforces RFC compliance; fake packets are intentionally non-compliant
- rustls abstracts byte layout; mutations need exact byte-offset control over
  extension ordering, padding sizes, and record boundaries
- The static `.bin` blobs contain browser-specific fingerprints (cipher suite order,
  extension lists, GREASE values, key share patterns) that rustls cannot reproduce

## Fake TLS Mutation Pipeline Detail

### Mutation Flags (ripdpi-config constants.rs)

| Flag | Value | Effect |
|------|-------|--------|
| FM_RAND | 1 | Randomize nonce, session ID, key share group/ephemeral keys |
| FM_ORIG | 2 | Use original real ClientHello as fake base |
| FM_RNDSNI | 4 | Replace SNI with random domain-like hostname |
| FM_DUPSID | 8 | Copy session ID from real ClientHello to fake |
| FM_PADENCAP | 16 | Encapsulate payload in TLS padding extension (0x0015) |

### Fake Profile Templates (ripdpi-packets fake_profiles.rs)

| Profile | Source Browser | SNI |
|---------|---------------|-----|
| CompatDefault | Unknown | www.wikipedia.org |
| IanaFirefox | Firefox | iana.org |
| GoogleChrome | Chrome | www.google.com |
| VkChrome | Chrome | vk.com |
| SberbankChrome | Chrome | online.sberbank.ru |
| RutrackerKyber | Chrome (with PQ) | rutracker.org |
| BigsizeIana | Unknown (large padding) | iana.org |

### Mutation Functions (ripdpi-packets tls.rs)

| Function | Purpose |
|----------|---------|
| `randomize_tls_seeded_like_c()` | Randomize random[32], session ID, key share using seeded PRNG |
| `randomize_tls_sni_seeded_like_c()` | Replace SNI with random domain (e.g., "qxyz.random.com") |
| `duplicate_tls_session_id_like_c()` | Copy session ID from real to fake ClientHello |
| `tune_tls_padding_size_like_c()` | Adjust padding extension to reach exact byte count |
| `padencap_tls_like_c()` | Embed payload inside padding extension |
| `change_tls_sni_seeded_like_c()` | Replace SNI with specific hostname, reconstruct extensions |

### TLS Record Splitting (ripdpi-desync tls_prelude.rs)

| Step | Behavior |
|------|----------|
| TlsRec | Split single TLS record into two at specified offset |
| TlsRandRec | Fragment tail into N random-sized records (MSS-aware) |

## JA3/JA4 Fingerprint Analysis

**Not a concern for RIPDPI's architecture.**

- **Diagnostic probes:** Fingerprint is irrelevant -- testing DPI presence, not evading
- **Real user traffic:** Browser's fingerprint passes through unmodified (TCP relay)
- **Fake packets:** Static `.bin` blobs already have browser-accurate fingerprints

If a future feature requires the proxy to originate TLS to target servers, then
fingerprint mimicry becomes relevant. Options at that point:
- rustls `dangerous_configuration`: can reorder cipher suites/extensions, cannot
  reproduce GREASE or browser-specific key share patterns
- `boring` (BoringSSL bindings): Chrome-identical fingerprints, but adds ~2MB to
  binary and requires C++ build toolchain

## ring vs aws-lc-rs Backend Comparison

| Aspect | ring (current) | aws-lc-rs |
|--------|---------------|-----------|
| Post-quantum KEM | Not supported | X25519Kyber768 |
| Binary size (arm64) | ~400KB | ~800KB |
| Build complexity | Pure Rust + ASM | C/C++ (CMake) |
| Android NDK compat | Excellent | Good (needs CMake) |
| FIPS | No | 140-3 validated |

**Recommendation:** Stay on `ring`. The proxy never terminates TLS, so PQ key
exchange is between client browser and server. The Kyber fake profile works as a
static blob. Switch to `aws-lc-rs` when DNS servers require PQ for DoT/DoH --
one-line feature flag change in workspace `Cargo.toml`.

## Improvement Opportunities

### Phase 1: DoT Session Resumption (low risk, high value)

Each `connect_dot_session()` rebuilds `Arc<ClientConfig>`, preventing TLS 1.3
session ticket caching. Fix: store a single `Arc<ClientConfig>` per resolver
instance, built once in `new()`.

- File: `ripdpi-dns-resolver/src/resolver.rs`
- Impact: Reduced DoT reconnection latency (0-RTT or 1-RTT vs 2-RTT)

### Phase 2: Fake Profile Freshness (low risk, medium value)

Six static `.bin` ClientHello blobs have unknown capture provenance. Stale profiles
with outdated cipher suites or extensions may be fingerprinted by modern DPI.

- Files: `ripdpi-packets/src/fake_profiles/` directory
- Action: Document capture metadata, establish quarterly refresh cadence

### Phase 3: ECH Monitor Probe (medium risk, strategic value)

ECH (Encrypted Client Hello) hides SNI from DPI entirely. rustls 0.23 has
experimental ECH support behind the `ech` feature flag.

- Files: workspace `Cargo.toml` (feature flag), `ripdpi-monitor/src/tls.rs` (new profile)
- Action: Add `TlsClientProfile::Tls13WithEch` to detect whether ECH is blocked

### Execution Summary

| Phase | Item | Effort | Risk |
|-------|------|--------|------|
| 1 | DoT session resumption | 1-2 hours | Low |
| 2 | Fake profile freshness | 1-2 days | Low |
| 3 | ECH monitor probe | 2-3 days | Medium |

## Non-Goals

- Migrating away from rustls (nothing to migrate from)
- JA3 mimicry in the proxy (proxy doesn't originate TLS to targets)
- Replacing raw byte TLS parsing with rustls (byte-level control is required)
- FIPS compliance (not a requirement)
- Switching to aws-lc-rs backend (premature; PQ gap irrelevant for current architecture)
