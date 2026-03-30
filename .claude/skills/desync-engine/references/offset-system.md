# Offset System Reference

The offset system is defined in `native/rust/crates/ripdpi-config/src/model.rs`
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
