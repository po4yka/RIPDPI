# etherparse Integration Audit

Date: 2026-03-24
Crate: etherparse 0.19
Scope: ripdpi-tunnel-core

## Packet Parsing Map

### L3/L4 Parsing (etherparse scope)

| Location | Function | Layer | Before | After | Allocations |
|----------|----------|-------|--------|-------|-------------|
| `classify.rs` | `classify_ip_packet` | L3+L4 | Manual byte indexing + `to_vec()` per UDP payload | `SlicedPacket::from_ip` + zero-copy `&[u8]` payload | Eliminated hot-path `Vec<u8>` alloc |
| `packet.rs` | `tcp_packet_endpoints` | L3+L4 | Manual IP version dispatch + byte indexing | `SlicedPacket::from_ip` -> `NetSlice` + `TcpSlice` | None (was already zero-copy) |
| `packet.rs` | `is_tcp_syn` | L4 | Manual offset calc + flag byte masking | `TcpSlice::syn()` + `TcpSlice::ack()` | None |
| `packet.rs` | `is_injected_rst` | L3+L4 | Manual IHL, RST flag, IP ID extraction | `Ipv4Slice::header().identification()` + `TcpSlice::rst()` | None |
| `packet.rs` | `build_udp_response` | L3+L4 | Manual header byte assembly + checksum | `Ipv4Header::new` / `Ipv6Header` + `UdpHeader::with_*_checksum` + `write()` | Same (`Vec<u8>` for output packet) |

### L7 Parsing (NOT etherparse scope -- unchanged)

| Location | What | Method |
|----------|------|--------|
| `ripdpi-packets/tls.rs` | TLS ClientHello, SNI extraction | Offset-based walk of extensions |
| `ripdpi-packets/http.rs` | HTTP method, Host header | Case-insensitive search + offset tracking |
| `ripdpi-packets/quic.rs` | QUIC Initial, version, CID | Varint decoding + offset tracking |
| `dns_intercept.rs` | DNS wire format (QNAME) | Label-by-label manual parsing |
| `ripdpi-monitor/dns.rs` | DNS query/response | Manual u16/u8 extraction |
| `ripdpi-monitor/http.rs` | HTTP status, headers | UTF-8 split + streaming read |

### smoltcp (TCP state machine -- unchanged)

| Location | What |
|----------|------|
| `device.rs` | `TunDevice` implements smoltcp `Device` trait |
| `tunnel_api.rs` | smoltcp `Interface` + `SocketSet` setup |
| `io_loop.rs` Phase 2 | `iface.poll()` advances TCP state machines |

## Coexistence Model

```
TUN fd read -> buf[..n]
  |
  +-> classify_ip_packet (etherparse: L3+L4 parse)
  |     |
  |     +-> TCP/Other -> is_injected_rst (etherparse) -> smoltcp rx_queue
  |     +-> UDP+DNS   -> dns_query_name (L7 manual) -> DNS worker
  |     +-> UDP       -> forward_udp_payload -> SOCKS5
  |
  +-> smoltcp poll (owns TCP reassembly, retransmission, congestion)
  |
  +-> smoltcp tx_queue -> TUN fd write
```

etherparse handles the pre-routing parse (one pass over L3+L4 headers).
smoltcp handles the full TCP protocol state machine.
No overlap -- etherparse never touches packets after classification.

## Removed Code

- `ipv4_transport_offset`, `ipv6_transport_offset`, `tcp_header_offset`: replaced by `SlicedPacket::from_ip`
- `classify_ipv4_udp`, `classify_ipv6_udp`: unified into `classify_ip_packet` via etherparse
- `udp_checksum_ipv4`, `udp_checksum_ipv6`, `normalize_udp_checksum`: replaced by `UdpHeader::with_*_checksum`
- `checksum_sum`, `finalize_checksum`: moved to `#[cfg(test)]` (only used by ICMP and test TCP packet builders)

## Binary Size

etherparse 0.19: pure Rust, zero transitive dependencies, ~3K SLOC.
With workspace release profile (`lto = "thin"`, `strip = "symbols"`, `codegen-units = 1`), unused code is eliminated.
Expected impact: <10 KB on the release `.so`.

## Performance

- **Hot-path allocation removed**: `classify_ip_packet` no longer calls `.to_vec()` on every UDP packet payload. Only DNS packets entering the worker queue pay for the clone.
- **Single-pass validation**: `SlicedPacket::from_ip` validates and parses L3+L4 in one pass with a single bounds-check sequence, vs the previous approach of checking bounds at each field access.
- **Equivalent overhead**: etherparse's zero-copy slice types add no measurable overhead vs manual byte indexing.

## Remaining Manual Byte Operations

| Location | What | Why not etherparse |
|----------|------|--------------------|
| `build_udp_port_unreachable` | ICMP/ICMPv6 header construction | etherparse has no ICMP builder; test-only code |
| `build_ipv4_tcp_syn_packet` | TCP SYN construction for tests | Test helper; etherparse `PacketBuilder` could work but unnecessary |
| `device.rs` test helpers | TCP SYN construction | Same as above; separate `#[cfg(test)]` module |
| `ripdpi-packets/*` | TLS/HTTP/QUIC L7 parsing | L7 protocols, outside etherparse scope |
