# Offset System Reference

The offset system is defined under `native/rust/crates/ripdpi-config/src/model/offset.rs`
(`OffsetBase`, `OffsetExpr`) and resolved in
`native/rust/crates/ripdpi-desync/src/offset.rs` (`gen_offset()`,
`resolve_offset()`, `resolve_adaptive_offset()`).

## OffsetExpr structure

```rust
pub struct OffsetExpr {
    pub base: OffsetBase,    // What the offset is relative to
    pub proto: OffsetProto,  // Any or TlsOnly -- filters when offset is evaluated
    pub delta: i64,          // Signed adjustment from base position
    pub repeats: i32,        // For TlsRec: how many times to apply
    pub skip: i32,           // For TlsRec: stride between repeated splits
}
```

`OffsetProto::TlsOnly` means the offset is only evaluated when the payload
is a TLS ClientHello. If the payload is HTTP or unknown, the offset returns
`None`.

## OffsetBase variants

### Absolute and payload-relative

| Variant | Target | Protocol | Resolution logic |
|---------|--------|----------|-----------------|
| `Abs` | Absolute byte position | Any | `delta` if >= 0; `payload_len + delta` if negative (from end) |
| `PayloadEnd` | End of payload | Any | `payload_len + delta` (delta is usually negative) |
| `PayloadMid` | Midpoint of payload | Any | `payload_len / 2 + delta` |
| `PayloadRand` | Random position in remaining payload | Any | `delta + cursor + random(0..remaining)` |

**Example**: `OffsetExpr::absolute(5)` splits at byte 5.
`OffsetExpr { base: Abs, delta: -3, .. }` splits 3 bytes before the end.

### Host/SNI-relative

These require protocol detection. For TLS, the host is the SNI value in the
ClientHello. For HTTP, the host is the Host header value. Resolution calls
`resolve_host_range()` which lazily initializes `ProtoInfo`.

| Variant | Target | Protocol | Resolution logic |
|---------|--------|----------|-----------------|
| `Host` | Start of Host/SNI value | HTTP, TLS | `host_range.start + delta` |
| `EndHost` | End of Host/SNI value | HTTP, TLS | `host_range.end + delta` |
| `HostMid` | Midpoint of Host/SNI value | HTTP, TLS | `host_range.start + host_len/2 + delta` |
| `HostRand` | Random position within Host/SNI | HTTP, TLS | `host_range.start + random(0..host_len) + delta` |

**Example**: `OffsetExpr::host(0)` splits right before the hostname starts.
`OffsetExpr::host(3)` splits 3 bytes into the hostname.

### Second-level domain (SLD)

SLD offsets target the registrable domain within the hostname (e.g., in
`www.example.com`, the SLD is `example`). Uses `second_level_domain_span()`
from `ripdpi-packets`.

| Variant | Target | Protocol | Resolution logic |
|---------|--------|----------|-----------------|
| `Sld` | Start of SLD within host | HTTP, TLS | `host_range.start + sld_start + delta` |
| `MidSld` | Midpoint of SLD | HTTP, TLS | `host_range.start + sld_start + (sld_len/2) + delta` |
| `EndSld` | End of SLD | HTTP, TLS | `host_range.end_offset(sld_end) + delta` |

**Example**: For host `www.example.com`, `MidSld` with delta=0 points to
the middle of "example".

### Protocol marker offsets

These target specific positions in protocol headers, independent of the
hostname.

| Variant | Target | Protocol | Resolution logic |
|---------|--------|----------|-----------------|
| `Method` | Start of HTTP method | HTTP only | `http_info.method_start + delta` |
| `ExtLen` | Start of TLS extensions length field | TLS only | `tls_info.markers.ext_len_start + delta` |
| `EchExt` | Start of ECH extension | TLS only | `tls_info.markers.ech_ext_start + delta` (Optional -- returns None if no ECH) |
| `SniExt` | Start of SNI extension | TLS only | `tls_info.markers.sni_ext_start + delta` |

**Example**: `OffsetExpr::tls_marker(OffsetBase::SniExt, -2)` splits 2 bytes
before the SNI extension starts.

### Adaptive offsets

Adaptive offsets do not map to a single position. Instead,
`resolve_adaptive_offset()` evaluates multiple candidate bases and picks
the best one that fits within a "budget" (typically derived from
`TcpSegmentHint::adaptive_budget()` -- the MSS or PMTU).

The algorithm:
1. Determine `target_end` from MSS budget or payload midpoint.
2. Iterate candidate bases (protocol-appropriate).
3. Prefer candidates at or below `target_end`; fall back to nearest above.
4. If a `preferred_base` is set (from adaptive hints), evaluate it first.

| Variant | Candidate bases (TLS) | Candidate bases (HTTP) |
|---------|----------------------|----------------------|
| `AutoBalanced` | ExtLen, SniExt, Host, MidSld, EndHost | Method, Host, MidSld, EndHost |
| `AutoHost` | Host, MidSld, EndHost | Host, MidSld, EndHost |
| `AutoMidSld` | MidSld, Host, EndHost | MidSld, Host, EndHost |
| `AutoEndHost` | EndHost, MidSld, Host | EndHost, MidSld, Host |
| `AutoMethod` | Method, Host | Method, Host |
| `AutoSniExt` | SniExt, ExtLen, Host | SniExt, ExtLen, Host |
| `AutoExtLen` | ExtLen, SniExt, Host | ExtLen, SniExt, Host |

Adaptive offsets silently return `None` from `gen_offset()` (they are
handled exclusively by `resolve_adaptive_offset()`). The planner skips
steps with unresolvable adaptive offsets without error.

## Properties

- `OffsetBase::is_adaptive()` -- returns true for all `Auto*` variants.
- `OffsetBase::supports_fake_offset()` -- false for adaptive and `EchExt`.
  Controls whether `fake_offset` in `DesyncGroupActionSettings` can use
  this base.
- `OffsetExpr::needs_tls_record_adjustment()` -- true unless the base is
  `Abs` with non-negative delta. TLS prelude steps subtract 5 (the TLS
  record header size) from the resolved position to convert from record
  coordinates to payload coordinates.
- `OffsetExpr::absolute_positive()` -- returns `Some(delta)` only for
  `Abs` base with non-negative delta. Used by `udp_fake_payload()` to
  slice fake data.

## TLS ClientHello semantic markers

When implementing offset bases like `host`, `endhost`, `midsld`, `sniext`, `extlen`, the parser must locate fields in the ClientHello byte layout per RFC 8446 §4.1.2. The byte structure after the TLS record header (5 bytes) and handshake header (4 bytes) is:

```
ClientHello {
    legacy_version              (2)
    random                     (32)
    legacy_session_id_len       (1)
    legacy_session_id        (0..32)
    cipher_suites_len           (2)
    cipher_suites               (variable)
    legacy_compression_len      (1)
    legacy_compression          (variable)
    extensions_len              (2)    // <- `extlen` marker location
    extensions                  (variable)
}
```

Inside `extensions`, each entry is `ExtensionType (2) + ExtensionData_len (2) + ExtensionData`. The `server_name` extension (`ExtensionType=0`) wraps a `ServerNameList`:

```
server_name_extension {
    list_len                    (2)
    ServerNameList [
        NameType (1)            // 0 for HostName
        HostName_len (2)
        HostName (variable)     // <- `host` marker starts here
                                // <- `endhost` marker ends here
                                // <- `midsld` = host_start + sld_offset + sld_len/2
    ]
}
```

Specific marker offsets:
- `host` — first byte of the `HostName` payload inside the `server_name` extension.
- `endhost` — byte AFTER the last byte of `HostName`.
- `midsld` — middle byte of the second-level domain (the part to the left of the final dot). For `www.google.com`, `sld = "google"`, so `midsld` is at `host_offset + len("www.") + len("goo") = host_offset + 7`.
- `sniext` — first byte of the `server_name` extension header (i.e. the `ExtensionType` byte = 0x00).
- `extlen` — the 2-byte `extensions_length` field that immediately precedes the extensions array.

### Parser edge cases

The parser MUST handle:

- **GREASE extensions** (RFC 8701). Random extension types like `0x0a0a`, `0x1a1a`, ... appear sprinkled across the ClientHello. They contain no useful data but must not cause the parser to abort. Skip unknown ExtensionType values, do not error.
- **Encrypted ClientHello (ECH, RFC 9460).** When the outer ClientHello contains `encrypted_client_hello` extension (type `0xfe0d`), the outer SNI is the ECH config's `public_name` and the real SNI is encrypted inside. Split-position manipulation on the OUTER SNI is bypass-useless (DPI sees only the public_name; no censored domain to match) and breaks 0-RTT. Detection: if `encrypted_client_hello` is present, skip all SNI-based desync strategies and fall back to QUIC Initial manipulation or transport-level desync.
- **ALPN extension (`application_layer_protocol_negotiation`, type 16).** Split positions MUST NOT fall inside this extension; servers reject ClientHello with a malformed ALPN. The planner should compute ALPN's byte range and reject candidate offsets that intersect.
- **TLS 1.3 `pre_shared_key` extension.** Must be the LAST extension per RFC 8446 §4.2.11. A split that crosses its boundary (e.g., splits after the second-to-last extension's end and before `pre_shared_key`'s start) breaks 0-RTT — the server is required to fall back to 1-RTT, losing the early-data savings. Detection: if `pre_shared_key` is the last extension, treat all candidate offsets after its start as 0-RTT-breaking.

### Implementation pointer

For reference parser behavior, use `rustls::internal::msgs::*` (rustls v0.23+) — it's the gold standard for correctness. For RIPDPI's hot-path parser, write a zero-allocation byte parser that returns only the marker offsets, NOT a full struct. The `tls-parser` crate (Rusticata) is a third-party reference.
