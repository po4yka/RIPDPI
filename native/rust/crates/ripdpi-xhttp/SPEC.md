# SPEC — `ripdpi-xhttp`

## Scope

XHTTP transport, Finalmask transforms, and gRPC length-prefixed framing for Xray client outbounds. Layered above `ripdpi-vless`.

## Upstream

- xray-core (https://github.com/XTLS/Xray-core)
- Pin recorded in `SPEC_VERSION.md`

## XHTTP transport

Tunnels raw VLESS bytes over HTTP/2 with a configured path, Host override,
default Referer padding and connection pooling. The supported client modes are
`stream-up`, `stream-one` and `packet-up`. REALITY auto selects `stream-one`;
TLS auto selects `packet-up`. Unknown modes are rejected. `stream-down` is the
GET download leg, not a selectable mode. Separate `downloadSettings` endpoints
and arbitrary request headers are not supported.

### Packet-up wire rules

Reference: Xray-core v26.10.31 (commit `a4a3dff5b20c2faae567b742c172c7b95e75c083`), `transport/internet/splithttp`. Implemented in `packet_up.rs`, `relay.rs` (`finish_packet_up`) and `h2_body.rs` (`build_packet_request`).

- Mode resolution: `dialer.go` `Dial` maps `""`/`auto` to `packet-up`, or to `stream-one` with REALITY (to `stream-up` with `downloadSettings`, which is not supported here).
- Session: `dialer.go` `Dial` draws a session id for every mode except `stream-one`. Xray's default is a UUID string (`config.go` `GenerateSessionID`); the server accepts any path segment, and this crate sends 32 hex characters, as for stream-up.
- Download: one GET to `{path}/{session}` (`dialer.go` `Dial` stream-down branch, `client.go` `OpenStream` with a nil body, `config.go` `FillStreamRequest`). It stays open for the session; only status 200 carries data.
- Upload: POSTs to `{path}/{session}/{seq}`, with `seq` a decimal counter from 0 (`dialer.go` `Dial` upload goroutine, `config.go` `ApplyMetaToRequest` and `appendToPath`). The configured query follows the last segment (`GetNormalizedPath`, `GetNormalizedQuery`).
- Body: the raw bytes in the request body with Content-Length and no Content-Type (`config.go` `FillPacketRequest`, default `uplinkDataPlacement` body). The VLESS request header is the start of POST 0.
- Size bound: each body is at most `scMaxEachPostBytes`, default 1000000 (`config.go` `GetNormalizedScMaxEachPostBytes`, `buf.SplitSize` in `Dial`). The server answers 413 above its own bound (`hub.go` `ServeHTTP`).
- Pacing: POST starts are at least `scMinPostsIntervalMs` apart, default 30 ms (`config.go` `GetNormalizedScMinPostsIntervalMs`, `time.Sleep` in `Dial`). This crate sleeps before it reads the next chunk, so bytes written during the pause join that POST.
- Status: a POST succeeds only with status 200 (`client.go` `DefaultDialerClient.PostPacket`). Any other status or a transport error fails the session: the read side returns `ConnectionReset` and no further POST is sent (Xray interrupts its upload pipe).
- Padding: each GET and POST carries a fresh `x_padding` value in the Referer query (`config.go` `FillStreamRequest` and `FillPacketRequest`, `xpadding.go` `PlacementQueryInHeader`), as for stream-up.
- Upload EOF sends no marker; the GET stays open until the stream is dropped, which resets both legs (`dialer.go` `splitConn`).

Divergences and limits:

- Xray waits only until a POST is written before it starts the next one (`dialer.go` `Dial`, `wroteRequest`), and the server reorders by `seq` (`upload_queue.go`, `scMaxBufferedPosts` 30). This crate submits POSTs in `seq` order on one H2 connection and keeps at most 4 POSTs and 2 MB in flight. Any non-200, transport error or a POST without an answer in 60 s fails the session.
- Memory: a session that uploads at line rate holds up to 1 MB in its upload pipe, 1 MB in the next chunk and 2 MB in flight. The pipe fills lazily, so idle and interactive sessions hold little. A 256 KB pipe measured about 25% slower upload than Xray on loopback at 80 and 200 ms RTT, because 30 ms pacing makes POST size the throughput limit.
- Opening waits for the GET's 200 before it returns the stream, so POST 0 starts one round trip later than in Xray, where `OpenStream` returns once the connection is up. Stream-up shares this path; the pool reads the peer's stream limit from that response.
- `poll_shutdown` completes after every accepted byte was answered with 200 (packet-up) or handed to the H2 connection (stream-up, stream-one), within 30 s. For stream-up and stream-one, dropping the stream right after shutdown can still let H2 reset a request whose last frames are queued but not yet sent.
- `scMaxEachPostBytes`, `scMinPostsIntervalMs`, `xmux` request and reuse limits, header or cookie data placement and HTTP/1.1 or HTTP/3 carriers are not configurable or not implemented; the defaults above apply. `xmux` pool limits apply only as for stream-up: a peer that admits fewer than two H2 streams splits the GET and POSTs across two carriers. Kotlin admission accepts a pool size only with explicit `stream-up`.

Connection pooling is in `pool.rs`; session ownership is in `relay.rs`.
HTTP request construction and body framing are in `h2_body.rs`. The gRPC-like
Content-Type is a header mask; the xHTTP driver does not apply `grpc.rs` framing.

## Finalmask transforms

Sudoku-based byte-mapping padding applied at the transport boundary on TCP. Implemented in `finalmask/`:

- `spec.rs` — table specification
- `masks.rs` — encoder/decoder
- `bridge.rs` — bridges into the TCP inbound/outbound

The implemented modes are `HeaderCustom`, `Noise`, `Fragment`, and `Sudoku`.
They operate on attacker-influenced bytes and are exercised by the
`finalmask_spec` and `finalmask_decoder` fuzz targets.

## gRPC framing

Length-prefixed message framing per the gRPC over HTTP/2 wire (5-byte prefix: 1 byte compression flag + 4 byte big-endian length). See `grpc.rs`.

## Known divergences from upstream

- The xHTTP wire reference is Xray v26.2.6; packet-up uses v26.10.31. The separate Android Xray provider has its own version admission policy; this crate pin is not proof of that AAR.
- Only the supported H2 modes and default metadata placement are implemented.
- Finalmask uses its own reference and supported subset in `SPEC_VERSION.md`; no full-release parity is claimed.
- `fragment` splits the first carrier chunk without changing TLS records. It does not guarantee separate TCP segments.
- Header and noise support is a one-way prefix contract. Variable-length prefixes need a peer with an explicit length agreement; the Xray example uses a fixed length and an empty server sequence.
- Connection-pool behavior is RIPDPI-specific and not a faithful copy of upstream's pool.

## Non-goals

- Server-side XHTTP.
