---
name: encrypted-dns
description: >
  Encrypted DNS resolver stack: DoH (RFC 8484), DoT (RFC 7858), DNSCrypt,
  DoQ (RFC 9250). Covers ripdpi-dns-resolver crate architecture,
  EncryptedDnsResolver, ResolverPool, HealthRegistry, health-weighted
  endpoint rotation, DNS bootstrap problem, SOCKS5 proxy transport,
  connection pooling, DNSCrypt certificate lifecycle, and DNS tamper
  detection in ripdpi-monitor. Use when adding or modifying encrypted DNS
  protocols, debugging resolver failures, tuning health scoring, working
  with bootstrap IPs, investigating DNS tampering diagnostics, or
  understanding why a DoH/DoT/DNSCrypt/DoQ exchange fails.
---

## Supported protocols

| Protocol | RFC/Spec | Transport | Default port | TLS required |
|----------|----------|-----------|--------------|--------------|
| DoH      | RFC 8484 | HTTPS POST with `application/dns-message` | 443 | Yes (HTTPS) |
| DoT      | RFC 7858 | TCP + TLS, length-prefixed DNS wire format | 853 | Yes |
| DNSCrypt | dnscrypt.info spec | TCP, XSalsa20Poly1305 (ChaChaBox) | 443 | No (own crypto) |
| DoQ      | RFC 9250 | QUIC bidirectional streams, length-prefixed | 853 | Yes (QUIC TLS) |

All protocols use standard DNS wire format (RFC 1035) for the query and
response payloads. The protocol layer only differs in how the wire bytes
are transported and encrypted.

DoQ does not support SOCKS5 transport because SOCKS5 is TCP-only; the
resolver returns `EncryptedDnsError::Request` if you try.

## Architecture

```
ResolverPool (pool.rs)
  |-- HealthRegistry (health.rs)      -- EWMA scoring per endpoint
  |-- FallbackCache (LRU, pool.rs)    -- cold-start memory
  |-- Vec<EncryptedDnsResolver>        -- one per configured endpoint
        |
        EncryptedDnsResolver (resolver.rs)
          |-- protocol dispatch: Doh | Dot | DnsCrypt | Doq
          |-- ConnectionPool           -- single idle DoT/DNSCrypt session
          |-- reqwest::Client          -- DoH HTTP client (when not using hooks)
          |-- quinn::Endpoint          -- DoQ QUIC client
          |-- DnsCryptCachedCertificate -- cached cert with validity window
          |
          transport.rs                 -- shared helpers
          |-- normalize_endpoint()     -- fills defaults, validates fields
          |-- build_doh_client()       -- configures reqwest with bootstrap IPs
          |-- build_client_config()    -- rustls TLS config with webpki roots
          |-- build_dns_query()        -- hickory-proto Message construction
          |-- extract_ip_answers()     -- parses A/AAAA from response bytes
          |
          dnscrypt.rs                  -- DNSCrypt-specific crypto
          hickory_backend.rs           -- optional hickory-resolver backend (feature-gated)
```

### Key files

| File | Purpose |
|------|---------|
| `src/lib.rs` | Public API re-exports |
| `src/types.rs` | `EncryptedDnsProtocol`, `EncryptedDnsEndpoint`, `EncryptedDnsTransport`, `EncryptedDnsError`, `EncryptedDnsConnectHooks` |
| `src/resolver.rs` | `EncryptedDnsResolver` -- per-endpoint resolver with protocol dispatch |
| `src/pool.rs` | `ResolverPool`, `ResolverPoolBuilder` -- multi-endpoint pool with health rotation |
| `src/health.rs` | `HealthRegistry`, `HealthScoreSnapshot` -- EWMA health tracking |
| `src/transport.rs` | Shared TLS config, DNS query building, SOCKS5 helpers |
| `src/dnscrypt.rs` | Certificate parsing, encryption/decryption, padding |
| `src/hickory_backend.rs` | Feature-gated `hickory-resolver` backend for DoH/DoT |
| `src/tests.rs` | Integration tests with local TLS/DNSCrypt servers |

## Health scoring

`HealthRegistry` tracks per-endpoint and per-bootstrap-IP health using
exponentially weighted moving averages (EWMA).

**Score model** (`health.rs:HealthScore`):
- `ewma_success_rate`: decays toward 0.5 (neutral prior) with configurable
  half-life (default 60s via `DEFAULT_HEALTH_HALF_LIFE` in pool.rs)
- `ewma_latency_ms`: decays toward 200ms initial prior
- Composite: `success_rate * 0.7 + (1 - latency/2000) * 0.3`

**How scores influence pool selection** (`pool.rs:try_order()`):
1. `HealthRegistry::rank_indices()` sorts endpoints by composite score (best first)
2. Cold-start override: if the top-ranked endpoint has zero observations, the
   `FallbackCache` (LRU) promotes the most recently successful endpoint to rank 0
3. Round-robin injection: every N-th call, the rotation counter promotes a
   non-top-2 endpoint to position 1, preventing starvation of lower-ranked
   endpoints that may have recovered

**SNI blocking penalty**: `record_sni_blocked()` records failure with a 4000ms
latency penalty, rapidly deprioritizing endpoints whose TLS handshake is being
RST-injected by middlebox DPI equipment.

**Bootstrap IP ranking**: `rank_bootstrap_ips()` uses the same EWMA model on
per-IP health data. Both `build_doh_client()` and `connect_direct_tcp_with()`
consult this ranking to try the healthiest bootstrap IP first.

**Sharing across pool recreations**: `ResolverPoolBuilder::health_registry()`
accepts a pre-existing registry so that dropping and rebuilding a pool preserves
all accumulated health data.

## DNSCrypt specifics

DNSCrypt uses its own crypto layer instead of TLS. The implementation lives
in `dnscrypt.rs` and the exchange logic in `resolver.rs:exchange_dnscrypt()`.

**Certificate lifecycle** (`fetch_dnscrypt_certificate()` / `current_dnscrypt_certificate()`):
1. Query the provider for `2.dnscrypt-cert.<provider_name>` TXT records
2. Parse the 124-byte certificate (`DNSCRYPT_CERT_SIZE`):
   - Bytes 0-3: magic `DNSC` (`DNSCRYPT_CERT_MAGIC`)
   - Bytes 4-5: es_version (must be 2 = XSalsa20Poly1305)
   - Bytes 8-71: Ed25519 signature over the signed portion (bytes 72-123)
   - Bytes 72-103: resolver's X25519 public key
   - Bytes 104-111: client_magic (8 bytes, prefixed to every query)
   - Bytes 116-119: valid_from (Unix timestamp)
   - Bytes 120-123: valid_until (Unix timestamp)
3. Verify Ed25519 signature using the provider's public key (`ring::signature`)
4. Cache in `Mutex<Option<DnsCryptCachedCertificate>>`, reuse until 60s before expiry

**Query encryption** (`exchange_dnscrypt_with_session()`):
1. Generate ephemeral X25519 keypair (`crypto_box::SecretKey`)
2. Build `ChaChaBox` from resolver public key + client secret
3. Generate random 12-byte nonce half (`DNSCRYPT_QUERY_NONCE_HALF`)
4. Pad query to 64-byte blocks (`dnscrypt_pad()`: append 0x80 then 0x00s)
5. Encrypt with ChaChaBox using full 24-byte nonce
6. Wrap: `client_magic || client_public_key || nonce_half || ciphertext`

**Response decryption** (`decrypt_dnscrypt_response()`):
1. Verify 8-byte response magic (`DNSCRYPT_RESPONSE_MAGIC`)
2. Extract full 24-byte nonce; verify first 12 bytes match query nonce half
3. Decrypt with ChaChaBox, then unpad (`dnscrypt_unpad()`: find last 0x80)

## Bootstrap problem

When system DNS is poisoned, you cannot resolve the hostname of your encrypted
DNS server (e.g., `dns.google`) via the poisoned system resolver. The solution
is hardcoded bootstrap IPs in `EncryptedDnsEndpoint::bootstrap_ips`.

**How it works**:
- `normalize_endpoint()` enforces `MissingBootstrapIps` error for `Direct`
  transport if no bootstrap IPs are provided
- For DoH: `build_doh_client()` calls `reqwest::ClientBuilder::resolve_to_addrs()`
  to pin the hostname to bootstrap IPs, bypassing system DNS entirely
- For DoT/DNSCrypt/DoQ: `connect_direct_tcp_with()` iterates bootstrap IPs
  directly, connecting to `SocketAddr::new(ip, port)` without any DNS lookup
- SOCKS5 transport delegates resolution to the proxy (`socks5h://` scheme),
  bypassing the bootstrap problem entirely

**EncryptedDnsConnectHooks**: allows the Android VPN layer to inject a custom
`DirectTcpConnector` (for protect-socket bypass) and `DirectUdpBinder` (for
DoQ). When a TCP connector hook is set, DoH falls back to manual HTTP/1.1
over the hooked TCP stream (`exchange_doh_manually()`) instead of reqwest.

## hickory-resolver backend

The `hickory-backend` feature flag (`Cargo.toml`) enables an alternative code
path that routes DoH and DoT through `hickory-resolver` instead of the manual
reqwest/tokio-rustls implementations.

**Fallback conditions** (`can_use_hickory()` in resolver.rs):
- Custom TLS roots are provided -> manual path (hickory uses its own root store)
- Custom TLS verifier is set -> manual path
- DirectTcpConnector hook is set -> manual path (hickory manages its own sockets)
- SOCKS5 transport with DoT -> manual path (hickory does not support SOCKS5)

DNSCrypt always uses the manual path because hickory-resolver has no DNSCrypt
support. DoQ is not routed through hickory either.

## DNS tamper detection

The `ripdpi-monitor` crate uses encrypted DNS as a ground-truth oracle to
detect DNS tampering by the ISP/middlebox.

**Detection flow** (`strategy.rs:detect_strategy_probe_dns_tampering()`):
1. For each target domain, resolve via system DNS (`resolve_addresses()`)
2. Resolve the same domain via encrypted DNS (`resolve_via_encrypted_dns()`)
3. Compare results:
   - System returns NXDOMAIN but encrypted returns IPs -> `DnsTampering`
   - System returns different IPs than encrypted -> `DnsTampering`
   - System resolves suspiciously fast -> `is_dns_injection_suspected()` heuristic
4. Results feed into `diagnosis.rs` which emits `dns_tampering` diagnostic codes

**Monitor's DNS helper** (`ripdpi-monitor/src/dns.rs`):
- `resolve_via_encrypted_dns()` creates a one-shot `EncryptedDnsResolver` with
  a preconfigured endpoint and calls `exchange_blocking()`
- `extract_ip_answers()` (re-exported from ripdpi-dns-resolver) parses A/AAAA
  records from the response bytes
- `build_fallback_encrypted_dns_endpoints()` in `dns.rs` provides 3-4 fallback
  DoH resolvers when the primary fails. Used in both
  `detect_strategy_probe_dns_tampering()` and `run_dns_probe()`.

## Built-in DNS providers

### Default provider and ordering

Default encrypted DNS is **AdGuard** (changed from Cloudflare). Priority:
AdGuard > DNS.SB > Mullvad > Google IP > Cloudflare IP > Google > Quad9 > Cloudflare.
Defined in `BuiltInDnsProviders` (`DnsResolverConfig.kt`) and `DEFAULT_DOH_*` (`util.rs`).

### DNS.SB TLS server name

`tlsServerName` for DNS.SB is `"dns.sb"` (not `"doh.dns.sb"`). The DoT cert
at port 853 is only valid for `dns.sb`; `doh.dns.sb` causes handshake failures.

### Eager DNS failover

`VpnEncryptedDnsFailoverController` triggers immediately on catastrophic errors
(connection reset, refused, "operation not permitted") via `isCatastrophicDnsError()`,
skipping the 2-failure threshold for bootstrap-phase queries (<=3 queries attempted).

### DNS failure counting

The native tunnel counts bootstrap connection failures in `dnsFailuresTotal`.
Previously only DNS query failures (after successful connection) were counted;
connection-level failures during bootstrap were silently dropped.

## Adding a new DNS protocol

End-to-end walkthrough for adding a hypothetical "DoX" protocol:

1. **types.rs**: Add `Doq`-style variant to `EncryptedDnsProtocol`, implement
   `as_str()` and `default_port()`. Add any protocol-specific fields to
   `EncryptedDnsEndpoint`. Add error variants to `EncryptedDnsError` and
   classify them in `kind()`.

2. **transport.rs**: Add normalization branch in `normalize_endpoint()` to
   validate required fields and fill defaults. If the protocol needs TLS,
   reuse `build_client_config()`.

3. **resolver.rs**: Add `exchange_dox()` method on `EncryptedDnsResolver`.
   Wire it into `exchange_with_metadata()` protocol dispatch match. If the
   protocol uses connection reuse, add a variant to `PooledConnection` and
   implement `take_dox_session()` / `connect_dox_session()` following the
   DoT/DNSCrypt pattern. If it needs a persistent client (like quinn::Endpoint
   for DoQ), add it to `ResolverInner` and initialize in `with_health()`.

4. **lib.rs**: No changes needed unless you add new public types -- the
   protocol enum variant propagates through existing re-exports.

5. **pool.rs**: No changes needed -- `ResolverPool` is protocol-agnostic. It
   delegates to `EncryptedDnsResolver::exchange_with_metadata()`.

6. **hickory_backend.rs** (optional): If hickory-resolver gains support for
   the protocol, add `exchange_dox()` and wire `can_use_hickory()` fallback.

7. **tests.rs**: Add integration tests following the existing patterns:
   - Spawn a local server (see `DnsCryptTestServer`, TLS listener patterns)
   - Create an endpoint pointing to `127.0.0.1` with the local port
   - Test both success path and error conditions

## Testing patterns

**Integration tests** (`tests.rs`, ~72K lines):
- Local TLS servers using `rcgen` self-signed certs + `rustls::ServerConfig`
- Local DNSCrypt servers with test Ed25519 keypairs
- `turmoil` crate for deterministic network simulation (used for `TcpClientStream` trait)
- `local-network-fixture` dev-dependency for network test infrastructure

**Unit tests** (inline in each module):
- `health.rs`: fake clock (`advancing_fake_clock()`) for deterministic EWMA testing
- `dnscrypt.rs`: Ed25519 keypair generation for certificate round-trip tests
- `pool.rs`: manual fallback cache seeding to test cold-start behavior

**Mock resolvers**: Tests create `EncryptedDnsResolver::with_extra_tls_roots()`
pointing to localhost with self-signed certificate DER bytes passed as extra
TLS roots. The `EncryptedDnsConnectHooks` mechanism allows injecting custom
TCP connectors for test isolation.

**Key test helpers**:
- `build_query(name)` -> DNS wire-format query bytes
- `build_response(query, answer_ip)` -> DNS wire-format response bytes
- `DnsCryptTestServer` -> full DNSCrypt server with cert generation + crypto
