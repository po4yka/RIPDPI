# ADR 0014: Relay protocol crates code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions moved out of source comments in `ripdpi-trojan`, `ripdpi-anytls`, and `ripdpi-shadowtls`. Paths are relative to `native/rust/crates/`.

## Decisions

### ripdpi-trojan/src/lib.rs#build_request

A Trojan request is: the SHA-224 of the password as 56 lowercase hex characters, CRLF, one command byte (0x01 TCP CONNECT, 0x03 UDP ASSOCIATE), the target address in SOCKS5 form (ATYP 0x01 IPv4, 0x03 domain with one length byte, 0x04 IPv6; then a 2-byte big-endian port), CRLF, and then the payload. A UDP packet is: address, port, length, CRLF, payload.

### ripdpi-trojan/src/lib.rs#MAX_UDP_PAYLOAD

The largest UDP payload is 8192 bytes. Xray-core ends the whole association when one frame is larger (measured on 26.3.27: 8192 is accepted, 8193 ends the association). trojan-go discards a larger frame with an error. Xray also ends the association when a downlink frame is larger than 8192 bytes in total, header included (an 8182-byte IPv4 echo failed, 8181 passed).

### ripdpi-trojan/src/lib.rs#TrojanUdpReader

The reader keeps a partial frame between calls. A cancelled read then does not lose bytes and does not desynchronise the stream.

### ripdpi-trojan/src/lib.rs#TrojanClientConfig

`outbound_bind_ip` pins the carrier socket to an interface. The bind IP family must match the family of the resolved server address. If it does not, the connect fails before any connection starts. An ignored bind would send the carrier over the default interface and not over the pinned one.

### ripdpi-trojan/src/lib.rs#PER_ADDRESS_CONNECT_TIMEOUT

Each server address except the last gets a short connect budget. A blackholed address would otherwise wait for the kernel connect timeout. That timeout is longer than the relay connect deadline, so the next address would never be tried.

### ripdpi-trojan/src/lib.rs#connect_first_reachable

Addresses are tried with the two IP families interleaved (RFC 8305 section 4), so a broken family costs at most one timeout. Each attempt builds its own socket and protects its fd through the in-process `VpnService.protect()` registry before connect. The non-loopback carrier socket then bypasses the own TUN route. With a bind IP, only addresses of its family are tried. The last error is returned when all attempts fail.

### ripdpi-trojan/src/lib.rs#protect_outbound_socket

The function does nothing for a loopback peer. For a non-loopback peer with no registered protect callback, it fails closed. Under a live TUN no other per-socket mechanism keeps the socket out of the tunnel, so a refusal is safer than an unprotected dial. The exclusion of the own UID from the TUN route (`computeAppRoutingPlan`) is the second layer. The same helper exists in `ripdpi-vless`.

### ripdpi-trojan/src/lib.rs#write_request

The plaintext password is hashed with SHA-224 in memory. It is never written to disk or to logs.

### ripdpi-trojan/src/lib.rs#connect_udp_associate

The UDP ASSOCIATE request goes out with the first write (the first UDP frame), as in Xray-core. The first TLS record then has no fixed header-only length. The request address is 0.0.0.0:0: servers ignore it for UDP, and each frame carries its own destination. If the caller reads before any write, the header goes out alone after a short delay (see `TrojanStream`). The same rule applies to `connect_tcp` and `connect_tcp_over` when the first payload is empty.

### ripdpi-trojan/src/stream.rs#TrojanStream

The request header goes out in the same write as the first payload, as in trojan-go and Xray-core. The first application-data record then does not have a fixed header-only length that shows the target address type. A server-first protocol still works: when the stream is read and not written for `HEADER_ALONE_DELAY` (100 ms, the value of trojan-go and Xray-core), the header goes out alone. A flush or a shutdown also sends it.

A first write that the inner stream cannot take at once still reports its length, and its bytes stay queued. The next write, flush, shutdown or read sends them, and a read parked on the stream is woken to do so. A caller that writes and then does none of these must flush, as with a buffered writer.

A read that drains the queue can take the write-readiness wake-up of the carrier. It wakes the parked writer at once, and the writer registers again. A writer may not come back after a first write, so a parked read is woken to drive the drain (fields `drain_waiter` and `read_waiter`).

### ripdpi-trojan/src/stream.rs#tests

A test with a server-first caller reads before it writes. The header must go out alone after the delay. A test with a client-first caller writes once and never flushes, while the read half waits: the header and the request must still reach the server. A test with a split stream checks that a writer parked on a full pipe does not hang when the read half takes the write wake-up and then ends.

### ripdpi-trojan/tests/tls_connect.rs#v6_bind_with_v4_server_fails_closed

A v6 bind IP against a v4-only resolved server must fail before any connect runs. An ignored bind would route the carrier over the default interface and not over the pinned one. A certificate bundle test uses port 1, which refuses the connection: the error must come before any network use.

### ripdpi-trojan/src/lib.rs#client_hello_parity_tests

The test proves that the Trojan outbound ClientHello carries the selected `ripdpi-tls-profiles` fingerprint. The cover of Trojan is that a server forwards a connection that fails authentication to a real fallback site. The ClientHello must therefore look like the configured browser. Otherwise the connection is not like a browser and the cover is void.

The oracle is the `chrome_stable` profile (`chrome::CHROME_LATEST`): the groups `X25519MLKEM768:X25519:P-256:P-384`, the ALPN `h2` then `http/1.1`, and `grease_enabled = true`. A stock BoringSSL ClientHello has none of these. The negative control at the end of the test compares a stock ClientHello, so the assertions are not true by default. The cipher order is stable and is compared directly. The extension order is permuted (`permute_extensions = true`), so extensions are compared as a set with GREASE values folded. The supported groups in the test have GREASE removed. A GREASE value has both bytes equal and the low nibble 0x0a (RFC 8701).

The capture listener accepts one connection and reads only the first TLS record. The handshake is expected to fail when the listener drops the socket, because only the ClientHello is needed. `connect_tcp` writes the ClientHello before the handshake fails, and the test ignores the resulting error.

### ripdpi-anytls/src/padding.rs#PaddingScheme

The parser follows the reference implementation: keys that are not packet indexes are ignored, invalid or oversized range parts are skipped, and a part that is not `c` or a positive range, or that would take the packet past one wire frame, is skipped. `stop=0` disables padding. Only a missing or non-numeric `stop` fails.

The length of the zero padding after the auth hash takes the first part of the packet-0 rule whatever `stop` is, as the reference client does. A leading `c` gives 0: the reference would send the check mark as a length of 65535 with no bytes behind it, and that breaks the stream. A size is drawn from `[min, max)` as the reference does (`rand.Int(max-min) + min`), so the range `1-2` always gives 1. Equal bounds parse as a fixed size, so `min < max` holds in the draw.

### ripdpi-anytls/src/pin.rs#verify_pinned_leaf

The leaf-certificate SHA-256 pin is the safe alternative to skipping TLS verification for a server with a self-signed certificate (the stock anytls-go server). The handshake is accepted only when the digest of the leaf certificate equals the pin. The chain and the host name are not checked: the pin replaces them.

### ripdpi-anytls/src/session.rs#SERVER_SETTINGS_WAIT

A fresh session of a server with an unknown version waits for `cmdServerSettings`. A v2 server sends it right after it handles the first packet (Settings, SYN, PSH), before it dials the target, so it arrives within one round trip. Silence for the wait time means a v1 server, which has no SYNACK and ignores HeartRequest. The peer version belongs to one session and is not carried to other sessions: addresses of one server can differ in version, and a server can be downgraded. The first session of a v1 server pays the wait, and a pooled session does not. A SYNACK error that arrives after the open returned ends the stream as a reset and not as a clean end, because no FIN follows.

### ripdpi-anytls/src/session.rs#IDLE_SWEEP_INTERVAL

The idle-session sweep period is the default of the reference client. The sweep alone can leave an idle session up to one interval longer than the timeout, so `take_idle_session` also checks the age.

### ripdpi-anytls/src/session.rs#ADDRESS_CONNECT_TIMEOUT

One resolved address gets a short connect budget. A blackholed first address then leaves time for the next addresses in the session establish budget.

### ripdpi-anytls/src/session.rs#connect_protected_tcp

The carrier socket is built explicitly and its fd is protected through the in-process `VpnService.protect()` registry before connect. The non-loopback carrier socket then bypasses the own TUN route. With `bind_ip`, the resolved server address must have the same family, or the connect fails closed. The socket is bound to the address before connect (interface pinning). Addresses are tried in order, each with its own socket, and addresses of the other family are skipped. `protect_outbound_socket` does nothing for loopback and fails closed for a non-loopback target when no callback is registered. The exclusion of the own UID from the TUN route is the second layer. `establish_session` over an existing transport has no OS socket of its own.

### ripdpi-anytls/src/session.rs#AnyTlsClientConfig

The `Debug` implementation is written by hand so that the password and the root-certificate data never appear in logs, diagnostics, or crash reports. It follows the redaction pattern of `ripdpi-mieru` (`MieruConfig`) and `ripdpi-ssh` (`SshConfig`). `Eq` and `PartialEq` stay for test equality.

### ripdpi-anytls/src/session.rs#allow_network_dial

A client built over the transport of a caller (a hop of a chain) never opens its own TCP connection. The server of the hop is reachable only through that transport. A direct dial would bypass the previous hops and leak the device address. Such a client has no network dialer: with an empty pool the open fails.

### ripdpi-anytls/src/session.rs#ClientState

Each session carries at most one stream. The carrier reader can then wait for a slow stream reader (real TCP backpressure) and does not stall a sibling stream. A stream that is gone drops the data. A finished stream returns its session to the idle pool for reuse. The idle session with the highest sequence number is used first.

### ripdpi-anytls/src/session.rs#StreamRoute

Only a peer `cmdFIN` is a clean end of a stream. A carrier that dies mid-stream shows to the application as a connection reset. A local end of the write side closes the stream at once, as the reference clients do, because AnyTLS has no half-close. A silent target then does not keep the relay slot open until the idle timeout. The downlink still delivers the frames that are in flight. `close` queues a FIN, and the route stays registered only for frames already in flight. A `Drop` after a successful `close` does not send a second FIN.

### ripdpi-anytls/src/session.rs#PaddingSchemeHandle

Clients built with the same scheme value keep a scheme that the server pushed with `cmdUpdatePaddingScheme` after the session that learned it is gone, as the protocol requires. An unusable pushed scheme keeps the current one: the reference only logs it and does not close the carrier. The random draw for a size range uses the CSPRNG of BoringSSL, as the reference client uses `crypto/rand`. If the draw fails, the range minimum is used. That is a padding size and is not a loss of security.

### ripdpi-anytls/src/session.rs#open_tcp_worker

The drop of the pending stream undoes every step that follows its creation. A carrier that is silently dead (path black hole, no RST) would hold every open until the deadline of the caller, so each open watches the carrier. v2 servers answer a SYN with SYNACK and v1 servers never do. The open decides from the peer version of the session and waits a bounded time for the ServerSettings when the version is unknown. A receiver that is gone drops the stream, which closes it.

### ripdpi-anytls/src/session.rs#watch_carrier

The function completes when the carrier stops responding: no inbound frame for `CARRIER_PROBE_AFTER` (3 s), and then none for `CARRIER_PROBE_TIMEOUT` (5 s) after a HeartRequest. A v1 server ignores HeartRequest, so only a server known to be v2 is probed. A live server answers the request even while it dials the target of a pending open. A full queue means the writer is stalled as well, and the timeout decides. A carrier busy with a large upload can also miss the deadline, because the request waits behind the upload. The caller then fails only its open and evicts the carrier. Streams already on it keep running.

### ripdpi-anytls/src/session.rs#open_udp_over_tcp

UoT v2 requires its request before the server can acknowledge the SYN: `isConnect=false` followed by an unused SOCKS destination 0.0.0.0:0. Datagram mode carries the real destination in every packet. A UDP association is one UoT byte stream on its own session. The carrier reader waits for a slow consumer and does not drop datagrams, because dropped bytes in the middle of the stream would break the UoT framing. The UoT datagram serializer has an address-family registry that is different from the SOCKS serializer of the first request.

### ripdpi-anytls/src/session.rs#establish_network_session

The carrier socket is protected before connect through the in-process `VpnService.protect` registry (loopback skipped, fail-closed under a live TUN). This is the same pattern as in `ripdpi-vless` and `ripdpi-xhttp`. AnyTLS is a standalone relay kind that is reachable under a live TUN. The exclusion of the own UID (`computeAppRoutingPlan`) is the second layer. See `.claude/rules/vpnservice-protect-invariant.md`.

### ripdpi-anytls/src/session.rs#establish_session

The padding scheme is cloned and the lock guard is dropped before the `write_auth` TLS write. A lock held across that write would serialize concurrent opens behind the handshake I/O.

### ripdpi-anytls/src/session.rs#write_all

The wire length is a u16. A large application write is split into chunks before it enters the writer queue. Otherwise the frame encoding ends the whole shared TLS carrier, including unrelated streams.

### ripdpi-anytls/src/session.rs#connect_tls

The TLS version range of the profile is kept. Forcing TLS 1.3 only removes the TLS 1.2 suites and extensions that the browser sends, so the ClientHello would match no real browser. The server still negotiates TLS 1.3.

### ripdpi-anytls/src/session.rs#run_session

A wrong password makes the server close the connection or fall back to another service, so no valid AnyTLS frame ever arrives. A v1 server also sends no frame after a good auth, but it keeps the connection open. Only the end of the carrier is a verdict. A command that the client does not know is skipped, as in the reference client, so a newer server cannot kill the session. As the first frame of a session, an unknown command means that the peer is not an AnyTLS server (for example a fallback web server that answers with HTTP). It is reported as `AuthenticationRejected` without waiting for its body. A padded payload and its waste frame leave in one write, so they form one TLS record.

### ripdpi-anytls/src/session/bridge.rs#AnyTlsIo

The task group of the client retains and joins the pump. Dropping one endpoint aborts only its stream, so multiplexed peers are not affected. When the route closes, only a peer FIN is a clean end, and the read side ends with a connection reset if the pump ended without a FIN. A dead carrier must not look like a complete response. A stalled uplink must not stop the pump from draining the downlink: one coupled loop starved the mailbox into a reset.

### ripdpi-anytls/src/session/owned_tasks.rs#OwnedTasks

Registration is synchronous, so it also covers FIN sends that start in `Drop`. Finished tasks are reaped before admission, so the history does not grow the registry. Only application clients and streams own the guard. Background workers never hold it, so dropping the last application owner breaks pending handshakes.

### ripdpi-anytls/src/session/tests.rs#tests

The peer version belongs to one session: a later session of the same client starts unknown. A test with an address that never answers uses 192.0.2.1 (TEST-NET-1), which most hosts black-hole. Where the network rejects it at once, the fallback is still used, only without a wait. In a test where the carrier queue is full, the first chunk fills the queue and the pump then waits to send the second. In a test that drops the receiver, the uplink branch of the SOCKS pump can win the race.

### ripdpi-anytls/tests/tls_session.rs#tests

The fixture parks its frame loop in the SYNACK delay. It then answers neither the SYN nor a HeartRequest, and the carrier is silently dead: the open fails after the liveness probe, and the next open dials a new carrier. The test of a flood on stream 1 shows that the carrier reader waits for the reader and a stream on another session is not affected. In the test of the slow drain the local reader pauses for 3 s while the peer keeps echoing, and the bound is one limit on the whole drain and not per read: a stall must fail, and a slow machine only needs the transfer to finish. A v1 server sends no ServerSettings and no SYNACK: a fresh session waits 2 s, and a second fresh session waits again. A server that falls back to a web site answers the auth with HTTP and closes, and the open fails at once as an authentication failure. A bind IP of a different family than the server fails closed, for the same reason as in `ripdpi-trojan`. A target that refuses the connection ends only its own stream (a rejected SYNACK on v2, a FIN on v1). That must not read as a wrong password, because the carrier stays up.

### ripdpi-shadowtls/src/client.rs#connect

The behavior is the same as in `ripdpi-trojan/src/lib.rs#connect_first_reachable`: a short per-address budget, protection of the socket before connect (`PROTECT INVARIANT`), and fail-closed when no callback is registered. A protection failure is a policy failure and not an unreachable peer: it returns `Attempt::Abort` and stops the whole connect, and the next address is not tried. ShadowTLS is a standalone relay kind that is reachable under a live TUN. `connect_over` carries no OS socket of its own.

### ripdpi-shadowtls/src/client.rs#handshake_switch

At the switch point a v3 server sends the HMAC-authenticated TLS_APPLICATION_DATA (0x17) frame. A raw TLS handshake record (0x16 0x03) at this point is classified as a v2 framing signal. Upstream v2 relays the cover TLS flight unchanged (`relay_v2` in `server.rs` at commit 02dd0bc7), so with a TLS 1.3 cover a real v2 server sends 0x14 and 0x17 records here and never 0x16. It then shows as the HMAC failure in `verify_handshake_frame`. A TLS 1.2 cover, whose plaintext Certificate records also start with 0x16 0x03, is rejected earlier by `parse_validated_server_hello` (strict TLS 1.3). In practice only the synthetic `ShadowTlsV2RejectLoopback` reaches this branch. The ServerHello is also 0x16 0x03, but it is read and validated before this loop. A bad-password reject sends a 0x17 frame whose HMAC fails and classifies as `Other`, so an auth failure is not a false version mismatch.

### ripdpi-shadowtls/src/config.rs#Config

`outbound_bind_ip` is not part of the serialized profile. It is runtime routing state that the relay builder supplies, like `socket_protection`.

### ripdpi-shadowtls/src/handshake.rs#HELLO_RETRY_REQUEST_RANDOM

The fixed `random` value marks a ServerHello as a HelloRetryRequest (RFC 8446 section 4.1.3). The store of root certificates is built from `webpki_roots::TLS_SERVER_ROOTS` and no CRLs are set, so the build cannot fail.

### ripdpi-shadowtls/src/hmac.rs#ShadowTlsHmac

The HMAC-SHA1 is rolling over the cumulative byte sequence and is cut to 4 bytes. It chains across every frame (payload, digest, next payload), so it must absorb the whole stream. It keeps an incremental `hmac::Context`. Buffering every byte and signing again on each call was O(n^2) in the frame count and grew memory without limit for the life of a connection. That slowed and bloated long-lived relays. `digest()` clones the context and finalizes the clone, so the running context keeps absorbing. Streaming HMAC equals a one-shot signature over the concatenation, so the wire contract is the same.

The test vector is RFC 2202 test case 1 for HMAC-SHA1 (key 0x0b repeated 20 times, data "Hi There", full result b617318655057264e28bc0b6fb378c8ef146be00). ShadowTLS uses the first 4 bytes.

### ripdpi-shadowtls/src/lib.rs#classify_failure_payload

v3 servers prefix every server response with a 4-byte truncated HMAC-SHA1 tag. v2 servers do not: they send the TLS record directly. A payload that starts with a TLS handshake record header (0x16 0x03) at offset 0 therefore signals a v2 server. A payload that is empty or shorter than 2 bytes is `Other`, because the function cannot decide. A first byte that is not 0x16 is not a handshake record. A v3 tag is arbitrary bytes and is never the record marker at offset 0.

`ShadowTlsFailureKind::VersionMismatch` maps to `ripdpi-failure-classifier::FailureClass::ShadowTlsVersionMismatch`, so the diagnostic tells the user to upgrade the server from v2 to v3. The enum and the function are local, so the crate does not depend on the failure classifier. `ShadowTlsHandshakeError` is the typed seam: it is the inner error of an `io::Error` (`io::Error::other`), and the relay layer downcasts it with `error.get_ref().downcast_ref::<ShadowTlsHandshakeError>()`. It follows `ripdpi_tuic::TuicHandshakeError`. `CoverHelloRetry` means that the cover server answered with a HelloRetryRequest. The rewritten session id makes rustls reject it, so the cover server must accept the X25519 key share of the client. See `docs/architecture/shadowtls-version-policy.md`.

### ripdpi-shadowtls/src/lib.rs#loopback

The loopback test servers exist only with the `test-server` feature and are never part of the production relay path. `ShadowTlsLoopback` is a conforming v3 server. `ShadowTlsV2RejectLoopback` emulates a v2 server, so integration tests of other crates can drive the version-mismatch path.

### ripdpi-shadowtls/src/stream.rs#ShadowTlsStream

The read and the write side keep separate frame buffers. A single shared buffer (`pending_frame`) turned the duplex into a frame-by-frame ping-pong under `tokio::io::split` and `copy_bidirectional`: an inbound frame in progress and a queued outbound frame could not exist together. Throughput fell to the delayed-ACK timer of about 40 ms. Separate buffers let both directions run in a pipeline.

A write, flush, or shutdown that waits for the queued frame to drain stores its waker. A read that drains the frame wakes it too, so the single write-readiness slot of the carrier never loses the wake-up of the writer. A write writes through: callers such as the VLESS request header may never flush, and a server-first peer then waits forever. The payload is already accepted, so the call reports success in every case. If the write hit `Pending`, a caller that only reads next (a server-first protocol) would never send the queued frame, so a read drives it. A write error stays queued: the next write, flush, or shutdown tries again and reports the error.

### ripdpi-shadowtls/src/tests.rs#tests

A VLESS request header is written with `write_all` and no flush. A server-first protocol (SMTP, IMAP) sends nothing more, so the frame must reach the carrier on the write itself. After a write that hit `Pending`, a caller that only reads next must still send the queued frame, or the server never answers.

### ripdpi-shadowtls/src/upstream_oracle_tests.rs#module

The module is an independent oracle of the ShadowTLS v3 server, derived from ihciah/shadow-tls `src/server.rs` and `src/util.rs` at commit 02dd0bc7bae8a2011729f95021690e694fd8e43e. It uses `ring` HMAC and SHA-256 directly. It must not use the helpers of the crate (`hmac`, `frames`, `handshake`): a bug shared by the client and the helpers would pass unseen. `ShadowTlsLoopback` uses those helpers and does not verify the ClientHello HMAC, so it cannot be the oracle. The cover server is a real rustls TLS 1.3 `ServerConnection`. The client side uses only the public `ShadowTlsClient` and `Config` API.

The ClientHello check is HMAC_SHA1(password, handshake message with the last 4 session-id bytes set to zero), cut to 4 bytes. The oracle follows the upstream steps: a failed check makes the server work as an SNI proxy, `copy_by_frame_with_modification` relays the rest of the cover flight, `copy_by_frame_until_hmac_matches` relays client data to the cover, and `verified_relay` echoes data. `verify_appdata` needs content type 0x17 and record version 03 03. An extra in-flight cover record (for example a late NewSessionTicket-sized record) must be ignored by the residual detector of the client. Upstream v2 relays the cover TLS flight unchanged (`relay_v2`).

### ripdpi-shadowtls/src/loopback.rs#ShadowTlsLoopback

ShadowTLS v3 abandons the TLS handshake right after the ServerHello, and the client never processes a Certificate. The loopback therefore sends a valid rustls-generated TLS 1.3 ServerHello (a real rustls server is used only for this, with a self-signed certificate that is never sent), then the HMAC-authenticated switch frame, then HMAC-framed echo. Only the first record is sent: the rest of the TLS flight is dropped. No certificate verification seam is needed on the client. The server is in this crate and not in `ripdpi-protocol-loopback` because the framing, HMAC, and handshake helpers that it reuses are `pub(crate)`. This keeps the test mutually consistent with the real client. The accept loop disables Nagle, so the per-frame echo is not slowed by the Nagle and delayed-ACK interaction (a socket artifact). On drop, the stop signal is sent before the accept-loop handle is detached, so the loop sees the shutdown on its next poll (fire-and-forget teardown).

### ripdpi-shadowtls/src/loopback.rs#ShadowTlsV2RejectLoopback

The wire shape is synthetic. Real upstream v2 relays the cover TLS flight unchanged, so a TLS 1.3 cover never sends a 0x16 record after the ServerHello (see `docs/architecture/shadowtls-version-policy.md`). The server relays a valid ServerHello, so `parse_validated_server_hello` passes as for a real v2 server that proxies to a cover site. Then, in place of the v3 switch frame, it sends a raw TLS handshake record (0x16 0x03 0x03). The v3 client reads it at the switch point and classifies it as `VersionMismatch`. The server holds the connection open until the client closes and drains what the client writes (for example a middlebox-compatibility ChangeCipherSpec). The close is then a clean FIN and not an RST, so the client reads the v2 record before EOF and does not race a reset that could discard the sent bytes.

### ripdpi-shadowtls/src/loopback.rs#tests

The test of 8 MiB full-duplex transfer over a `tokio::io::split` stream covers two bugs that small payloads miss. First, `ShadowTlsHmac` hashed its whole buffer on every `digest()`, so the rolling HMAC was O(n^2) and 8 MiB took minutes. Second, `ShadowTlsStream` shared one `pending_frame` between read and write, which corrupted data under concurrent read and write (HMAC failure). Now the transfer takes well under a second. The 10 s limit separates that from the O(n^2) regression with margin for slow CI. The runtime is multi-thread so the writer and the reader make progress together. Nodelay on both ends removes the Nagle and delayed-ACK timer, so the test checks only the read and write serialization. The version-mismatch tests check two paths: a v3 client against a v2-emulating server must give a typed `ShadowTlsHandshakeError` version mismatch (not a generic TLS error), and a v3 server that rejects a bad password sends the 0x17 switch frame whose HMAC fails, which is an auth failure and must not be a version mismatch. The soak test runs many handshakes and is `#[ignore]`, so it stays out of normal PR CI. Run it with `cargo nextest run -p ripdpi-shadowtls --run-ignored all`. A v6 bind IP against a v4-only server fails closed before any connect, for the same reason as in `ripdpi-trojan`.
