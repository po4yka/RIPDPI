# Fake Profile Provenance

Binary blobs used by `TlsFakeProfile`, `HttpFakeProfile`, and `UdpFakeProfile` to impersonate legitimate traffic during DPI evasion.

## TLS ClientHello Profiles

| File | Enum variant | SNI | Est. browser | GREASE | Post-quantum | ECH | TLS versions | Cipher suites | Size (B) | Capture date |
|------|-------------|-----|--------------|--------|-------------|-----|-------------|---------------|----------|-------------|
| `tls_clienthello_iana_org.bin` | `IanaFirefox` | `iana.org` | Firefox ~115-125 | No | No | No | 1.3, 1.2, 1.1, 1.0 | 31 (incl. TLS_EMPTY_RENEGOTIATION_INFO_SCSV) | 517 | Unknown |
| `tls_clienthello_iana_org_bigsize.bin` | `BigsizeIana` | `iana.org` | Synthetic (see note) | No | No | No | 1.3, 1.2, 1.1, 1.0 | 31 | 517 | Unknown |
| `tls_clienthello_www_google_com.bin` | `GoogleChrome` | `www.google.com` | Chrome ~115-120 | No | No | Yes | 1.3, 1.2 | 17 | 681 | Unknown |
| `tls_clienthello_vk_com.bin` | `VkChrome` | `vk.com` | Chrome ~110-124 | Yes | No | No | 1.3, 1.2 | 16 (incl. GREASE) | 517 | Unknown |
| `tls_clienthello_sberbank_ru.bin` | `SberbankChrome` | `online.sberbank.ru` | Chrome ~110-124 | Yes | No | No | 1.3, 1.2 | 16 (incl. GREASE) | 517 | Unknown |
| `tls_clienthello_rutracker_org_kyber.bin` | `RutrackerKyber` | `rutracker.org` | Chrome >= 124 | Yes | Yes (ML-KEM-768) | Yes | 1.3, 1.2 | 16 (incl. GREASE) | 1787 | Unknown |

### JA3 pre-hash strings

The canonical JA3 pre-hash string (`version,ciphers,extensions,groups,ec_point_formats`,
decimal IDs, GREASE filtered out) computed by the **production** JA3 parser
(`ripdpi-diagnostics-tls/src/ja3/`) for each profile. These are pinned by the
drift guard in `ja3_drift_guard.rs`; this table and that test MUST agree. We pin
the string, not the MD5 hash, so any drift is human-diffable. (`CompatDefault` /
`DEFAULT_FAKE_TLS` is a truncated synthetic blob and does not parse as a
ClientHello, so it has no JA3 string.)

| Enum variant | JA3 pre-hash string |
|--------------|---------------------|
| `IanaFirefox` | `771,4866-4867-4865-49196-49200-159-52393-52392-52394-49195-49199-158-49188-49192-107-49187-49191-103-49162-49172-57-49161-49171-51-157-156-61-60-53-47-255,0-11-10-13172-16-22-23-49-13-43-45-51-21,29-23-30-25-24,0-1-2` |
| `GoogleChrome` / `GoogleChromeHrr` | `771,4865-4867-4866-49195-49199-52393-52392-49196-49200-49162-49161-49171-49172-156-157-47-53,0-23-65281-10-11-35-16-5-34-18-51-43-13-45-28-27-65037,29-23-24-25-256-257,0` |
| `VkChrome` | `771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,13-17513-43-0-23-5-10-16-45-27-65281-35-11-51-18-21,29-23-24,0` |
| `SberbankChrome` | `771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,16-0-51-35-27-17513-11-18-5-10-43-45-65281-23-13-21,29-23-24,0` |
| `RutrackerKyber` | `771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,11-35-45-13-65037-10-5-0-65281-51-23-43-18-17513-27-16,25497-29-23-24,0` |
| `BigsizeIana` | identical to `IanaFirefox` (payload after byte 9 is byte-identical) |

`GoogleChrome` and `GoogleChromeHrr` map to the same blob, so their JA3 strings
are byte-identical; the drift guard asserts this explicitly. The `RutrackerKyber`
supported-groups field leads with `25497` (ML-KEM-768, `0x6399`), consistent with
the post-quantum attribution above.

### Identification methodology

Browser attribution is based on the following TLS fingerprint signals:

- **Firefox**: no GREASE values, `TLS_CHACHA20_POLY1305_SHA256` (`ccaa`) in cipher list, x448 (`0x001e`) in supported groups, four TLS versions in `supported_versions`, no ALPS extension (`0x4469`).
- **Chrome**: GREASE values in ciphers / extensions / supported groups / versions, ALPS extension (`0x4469`), `compress_certificate` (`0x001b`), typically two TLS versions (1.3, 1.2) plus a GREASE value in `supported_versions`.
- **Post-quantum**: ML-KEM-768 (`0x6399`) in `key_share` and `supported_groups`. Present only in Chrome >= 124.
- **ECH**: extension `0xfe0d` (`encrypted_client_hello`). Present in the Google and Rutracker blobs.

### Notes on specific profiles

- **BigsizeIana** is a synthetic variant of `IanaFirefox`. The TLS record length field is set to `0xFFFF` and the handshake length to `0xFFFFFF` (all-ones), while the payload after byte 9 is byte-identical to `tls_clienthello_iana_org.bin`. It exists to test DPI behaviour with oversized length fields.
- **GoogleChrome** has no GREASE in cipher suites but does include ALPS and ECH, suggesting it was captured from a Chrome version that randomised GREASE to absent, or GREASE was stripped during capture.

## HTTP Profiles

| File | Enum variant | Host | Description | Size (B) |
|------|-------------|------|-------------|----------|
| `http_iana_org.bin` | `IanaGet` | `www.iana.org` | HTTP/1.1 GET with typical browser headers | 418 |
| *(inline)* | `CloudflareGet` | `www.cloudflare.com` | Minimal HTTP/1.1 GET (defined in source, not a `.bin` file) | 82 |

## UDP Profiles

| File | Enum variant | Protocol | Description | Size (B) |
|------|-------------|----------|-------------|----------|
| `zero_256.bin` | `Zero256` | None | 256 zero bytes | 256 |
| `zero_512.bin` | `Zero512` | None | 512 zero bytes | 512 |
| `dns.bin` | `DnsQuery` | DNS | A-record query for `update.microsoft.com` | 38 |
| `stun.bin` | `StunBinding` | STUN | Binding request (magic cookie `0x2112a442`) | 100 |
| `wireguard_initiation.bin` | `WireGuardInitiation` | WireGuard | Handshake initiation (type 1) | 148 |
| `dht_get_peers.bin` | `DhtGetPeers` | BitTorrent DHT | Bencoded `get_peers` query | 104 |

## Refreshing a profile

### Capture a new ClientHello

1. Start a TLS packet capture on a clean browser session:
   ```bash
   tshark -i eth0 -f "tcp port 443" -w /tmp/capture.pcapng
   ```
2. Navigate to the target site in the browser you want to fingerprint.
3. Extract the first ClientHello for that connection:
   ```bash
   tshark -r /tmp/capture.pcapng -Y "tls.handshake.type == 1 && tls.handshake.extensions_server_name == \"example.com\"" \
     -T fields -e tls.record.content_type -e tls.record.version -e tls.handshake.type \
     --export-objects tls,/tmp/tls-objects/
   ```
   Alternatively, use the raw TCP payload. The `.bin` file must contain the complete TLS record starting with byte `0x16` (ContentType: Handshake) through the end of the ClientHello message, including padding extensions.
4. Save the raw TLS record bytes (no TCP/IP headers) to a `.bin` file.
5. Record the browser name, version, OS, and capture date in this file.

### Verify a new capture parses correctly

```bash
# Add the new .bin file and wire it into fake_profiles.rs, then:
cargo test -p ripdpi-packets -- fake_profiles::tests::tls_profiles_parse_and_expose_sni
```

The test calls `parse_tls()` on every `TlsFakeProfile` variant and asserts that the SNI extracted from the blob matches the expected hostname. If the new blob is malformed or uses an unexpected TLS structure, the test will fail with a parse error.

For a quick manual sanity check without modifying Rust code:

```bash
# Verify the TLS record header
xxd -l 10 new_profile.bin
# Expected: 16 03 01 XX XX 01 00 XX XX 03 03
#           ^type  ^ver  ^len  ^hs  ^hs_len ^client_ver
```
