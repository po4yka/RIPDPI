# ADR 0015: Diagnostics probe crates code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions moved out of source comments in the `ripdpi-diagnostics-http`, `ripdpi-diagnostics-tls`, `ripdpi-diagnostics-dns`, and `ripdpi-diagnostics-transport` crates. Paths are relative to `native/rust/crates/`.

## Decisions

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech.rs#module

When DNS HTTPS record resolution fails (for example, encrypted DNS is blocked), the probe falls back to a bundled ECH config for known CDN providers. The config is best effort. If the server rejects it (key rotation), the caller falls back to normal TLS.

Cloudflare rotates ECH keys. To get a fresh config, run `dig +short type65 cloudflare.com | grep -oP 'ech=\K[^ ]+'`, decode the base64 value, and replace the bundled Cloudflare ECHConfigList in `cdn_ech/catalog.rs`.

`EchConfigSource` and `CdnEchUpdater` form a TTL-gated cache with primary-then-fallback behavior. `BundledEchConfigSource` is the fallback that is always available. `RemoteEchConfigSource` sends an HTTPS-RR (type 65) DoH query. The cache accepts its bytes only after `validate_ech_config_list_bytes` approves them.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/cache.rs#CdnEchUpdater::cached_config

This function returns fresh cached bytes only. It never fetches, because a fetch would run on the caller's network path.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/catalog.rs#CLOUDFLARE_ECH_CONFIG_LIST

The bytes were captured on 2026-04-08 from the `ech` SvcParam of the `cloudflare.com` HTTPS record. They are the public ECH key of Cloudflare and are not secret. Runtime refresh uses `RemoteEchConfigSource`. Rotate this fallback by hand with the steps in `cdn_ech.rs`.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/catalog.rs#CLOUDFLARE_IPV4_RANGES

The ranges are major allocations and are not exhaustive. The source is `https://www.cloudflare.com/ips-v4/`. The IPv6 list (`CLOUDFLARE_IPV6_RANGES`) comes from `https://www.cloudflare.com/ips-v6/`.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/production.rs#PRODUCTION_TTL

The TTL of the singleton updater is 24 hours. This matches the key-rotation cadence that was observed for Cloudflare.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/source.rs#EchConfigSource

An implementation must be cheap to call when it already holds the config (for example, a copy of a static slice). Expensive work belongs in a higher-level scheduler that calls `CdnEchUpdater::refresh`.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/source.rs#REMOTE_ECH_DEFAULT_DOMAIN

Cloudflare publishes the HTTPS resource record on this name. It rotates with the same cadence as the runtime ECH key. `REMOTE_ECH_DEFAULT_RESOLVER` is the public resolver of Cloudflare, which is the correct source for this config.

### native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech/source.rs#RemoteEchConfigSource

`fetch` sends an HTTPS-RR (type 65) query to the chosen resolver through the existing encrypted-DNS code. Each failure returns `EchSourceError::InvalidConfig`. `CdnEchUpdater` then uses the bundled config, so ECH support stays.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/encrypted_observation.rs#resolve_via_encrypted_dns_with_observations

The function uses the existing exchange one time. It keeps the response facts even when the response has no usable address.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64.rs#Nat64Prefix

RFC 6052 puts the reserved u octet at byte 8 for prefixes that are not /96. Bits 64 to 71 are this u octet, and they are zero also for /96 (RFC 6052 section 2.2).

The Well-Known Prefix never represents a non-global IPv4 address (RFC 6052 section 3.1). The RFC 7050 discovery sentinels are the one exception.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64.rs#discover_nat64_prefixes

The result is a bounded, ordered prefix reading of the AAAA data that answers a query for `ipv4only.arpa`. An ambiguous single embedding and an address that is not a sentinel do not authorize a TCP probe.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64.rs#discover_network_nat64

The function uses the resolver literals that the network gave, with at most three requests. Blocking I/O keeps the scan deadline of the caller. The function checks cancellation before and after each request.

An expired budget keeps a clean negative answer that the function already received. Cancellation always wins. Each resolver waits for a fair share of the remaining time, so a silent resolver leaves time for the next one. Only cancellation discards a reply. The function judges a deadline that expired during the exchange after it classifies the reply, so an earlier clean negative answer is not lost.

The general semantics parser sorts addresses, but this function keeps the DNS response order. An Answer always has AAAA data, so an empty prefix list is not a clean negative. A well-formed answer that fails the Pref64 heuristic is a verdict and not a resolver fault. A clean negative answer from one resolver outranks the transport fault of another resolver.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64.rs#resolver_targets

The list keeps the input order, has no duplicates, and has at most three entries. The function skips unusable literals before it applies the limit, so they do not use the budget.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64.rs#resolver_endpoint

The function accepts IP literals and IPv6 literals with a numeric or interface-name zone. A link-local IPv6 address needs a zone, because the kernel cannot choose an interface without one.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/nat64/tests.rs#silent resolver test

A silent resolver makes the receive wait end exactly at the active deadline.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/semantics.rs#parse_dns_response_semantics

The function parses diagnostic facts and does not relax the strict API of the runtime resolver. Negative and truncated replies are facts. They are never usable address answers.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns/udp.rs#resolve_via_udp_with_observations

The function measures plain UDP DNS on the current path. It does not reuse results from an earlier scan.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_analysis/compression.rs#BACKWARD_CHAIN

The dnspython library 2.8.0 decoded this fixture independently. It gives four `www.example.test` A records.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_analysis/records.rs#compare_dns_record_sets

Two caches see rotating pools and geo-steered CDN names. A different answer count or CNAME chain is a divergence only when the two answers share no address.

A TTL is evidence only. It is the remaining cache time (RFC 1035 section 3.2.1), so two caches of one long-TTL record differ by their cache age.

DNS names compare without case (RFC 4343).

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_analysis/response.rs#analyze_dns_response

The function gives useful results for malformed packets. The raw byte layer always reads the header flags and the section counts from the first 12 bytes.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_analysis/scoring.rs#compute_tampering_score

The diagnostic query asks for EDNS metadata. A missing OPT record, or an empty additional section alone, does not prove tampering. The score does not count them.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_analysis/tests.rs#score tests

The value 45 is the `dns_response_anomaly` threshold in the diagnosis layer. A compliant resolver can answer a client that has no OPT record without an OPT record (RFC 6891 section 7). The comparison fixture has an ISP cache that was filled two hours ago and a fresh DoH cache. The same CDN name uses another letter case. The ISP resolver also gives a steered CNAME and a larger pool that share an address with DoH.

### native/rust/crates/ripdpi-diagnostics-dns/src/dns_oracle/independence.rs#independence checks

The loop closes every alias before the attempt limit, also bridges that are later in the catalog. ODoH proxy sharing does not prove that the target resolvers have the same owner. The check uses known catalog identities. It does not use user labels or shared bootstrap infrastructure.

### native/rust/crates/ripdpi-diagnostics-dns/src/resolver_panel.rs#RESOLVER_PANEL

The public IP addresses come from the official documents of each provider.

### native/rust/crates/ripdpi-diagnostics-dns/src/resolver_panel.rs#ipv4_only filter test

The panel has no IPv6-only entry now, so both modes return the same count. The test checks the filter path only. A synthetic IPv6-only resolver would be dropped.

### native/rust/crates/ripdpi-diagnostics-http/src/http/classifier.rs#body_has_explicit_blockpage_message

The function looks for a short body that says a third party blocked the site. Generic words such as "forbidden", "access denied", and "blocked" also appear on origin authorization pages, WAF pages, rate-limit pages, and error pages. They are not evidence for any status. A large page from a legitimate site can quote these phrases in normal content, so the function ignores a body that is longer than 8192 bytes.

### native/rust/crates/ripdpi-diagnostics-http/src/http/content_coding_tests.rs#GZIP_BLOCKPAGE

The bytes are the output of Python `gzip.compress(BLOCKPAGE, mtime=0)`. The fixture was decoded independently. In the same file, the client can reject a response from its head and close before the server sends all bytes. The test server ignores the write result for this reason.

### native/rust/crates/ripdpi-diagnostics-http/src/http/probe_execution.rs#classify_with_bundled_fingerprints

The bundled blockpage fingerprint database keeps known ISP pages as `http_blockpage` on every HTTP path. These pages can be non-English or a redirect to a block host.

### native/rust/crates/ripdpi-diagnostics-http/src/http/probe_execution.rs#run_with_abort

The response loops read until EOF, a socket timeout, or the size cap. A peer that sends one byte within every socket timeout keeps them running. A watchdog shuts the socket down at the deadline or on cancel. A failure after that reports the abort reason. The cancel flag uses Acquire order to see the flag that the scan owner published.

The test peer sends one header byte every 100 ms. Every read then returns well within the socket timeout.

### native/rust/crates/ripdpi-diagnostics-http/src/http/response_decoder.rs#HttpResponseDecoder

The Hyper request gives method context only. The decoder output goes to a sink and never to the network, so probes keep their exact request bytes. Drop ends decoding at once. The type creates no tasks, runtimes, or threads.

All polling runs on one thread. The wake flag and the input-needed flag only record a request to poll again. They publish no data, so Relaxed order is enough. The payload moves through the channel.

For a response to HEAD, a 1xx, a 204, or an HTTP/1.0 response, transfer codings describe the body of the hypothetical GET (RFC 9112 sections 6.1 and 6.3).

### native/rust/crates/ripdpi-diagnostics-http/src/http3.rs#probe_http3

The optional verifier is the internal test seam. It is never wire input. `probe_http3_download` gives the receiver the body bytes only. The receiver does not get headers or trailers.

The function captures the synchronous scan context before it enters the Tokio runtime. The cancel checks use Acquire order to see the flag that the scan owner published.

When the runtime drops, it aborts all Quinn tasks after the request future and its endpoint are dropped. The code has no detached blocking tasks.

Evidence belongs to one peer. The code never combines the TLS data of a failed peer with the response of another peer.

`handshake_deadline` gives an equal share of the remaining time to the QUIC handshake. A black-holed address (often the first, IPv6 one) then cannot use the whole probe budget. The last attempt gets all of the time.

The code drains trailers, if any, so that the FIN of the body is confirmed.

### native/rust/crates/ripdpi-diagnostics-http/src/http3/frame_limit_tests.rs#response fixtures

A QPACK `:status=200` plus `x:<16000 bytes>` has a decoded size of 16075, which is below 16384. A literal value length of 16000 uses a 7-bit prefix and base-128 continuation (RFC 9204). A HEADERS length of 65537 is tested against RFC 9114 section 7.1 and RFC 9000 section 16. The second fixture is a reserved frame 0x21 with length 131072, then QPACK static `:status=200`.

### native/rust/crates/ripdpi-diagnostics-http/src/http3/socket.rs#module

A fixed worker keeps the blocking platform protection callback off the probe runtime. If the caller cancelled, the send fails, and the failed send owns and drops the returned socket. An expired job drops the socket in the worker, which never sends packets. A full queue is transient backpressure and not an I/O failure. The sender waits for a slot until the probe deadline and does not report `io_error`.

### native/rust/crates/ripdpi-diagnostics-http/src/http3/socket_tests.rs#protection test

An unspecified target invokes protection. The code must not send a QUIC packet to it. `pmtu/socket_tests.rs` has the same test for the PMTU probe.

### native/rust/crates/ripdpi-diagnostics-http/src/http3/tests.rs#test timing and fixtures

The server never finishes the stalled stage. The test timeout only decides when the probe gives up. It must be longer than the handshake, the headers, and the first body chunk under heavy CPU load, so the probe always captures the last observation.

One test runs in a child process. This isolates the process-global protection and the fd ownership from parallel socket tests. Without it, another test can reuse the closed descriptor before the assertion.

The silent socket is bound and never read, so Initial packets to it get no answer. The control stream fixture is the stream type followed by an empty SETTINGS frame (RFC 9114).

### native/rust/crates/ripdpi-diagnostics-http/src/http3/validation_tests.rs#response fixtures

The fixture bytes are QPACK static `:status=200` followed by the literal trailer `x:y` (RFC 9204 section 4.5.6 and Appendix A). The static indices 25 (`:status=200`) and 17 (`:method=GET`) come from RFC 9204 Appendix A. The bytes are independent of the h3 encoder under test.

### native/rust/crates/ripdpi-diagnostics-http/src/http_injection_probe.rs#BUILTIN_FINGERPRINTS

The table is a minimal set of offline anchors. A larger runtime set is in `ripdpi-failure-classifier`. This table covers the three structural injection classes that the diagnostic classifier needs.

Many ISP transparent caches add a `Via` header that has their cache product name. The marker `nginx-cache-isp` is a representative example. Block pages that Russian authorities require often contain the HTML marker phrase. A redirect whose Location starts with a known operator block-page host name shows a captive-portal injection.

### native/rust/crates/ripdpi-diagnostics-http/src/pmtu.rs#probe_pmtu

The verifier is an internal fixture seam. It is never wire input. The cancel checks use Acquire order to see the flag that the scan owner published. The socket workers share the cutoff, so the code keeps its owner for their timeouts.

The h3 ALPN needs the control streams and SETTINGS, even without a request.

The `Observation` guard captures the final counters when the outer select cancels the observation timer. It is private and short lived. It borrows only inside `observe` and does not enter a public API.

With initial and minimum MTU of 1200 and no migration, only an acknowledged discovery probe can raise `current_mtu`. The code never treats the baseline as a measurement.

### native/rust/crates/ripdpi-diagnostics-http/src/pmtu/resource_tests.rs#control stream fixture

The control stream type is followed by a SETTINGS length of 65537 without payload or FIN.

### native/rust/crates/ripdpi-diagnostics-tls/src/ja3/grease.rs#is_grease

A GREASE value has the pattern `0x?a?a`. The high and low bytes are equal, and `(byte & 0x0f) == 0x0a`.

### native/rust/crates/ripdpi-diagnostics-tls/src/ja3/tests.rs#GREASE fixture

TShark gave the expected hash for the DIA-02 GREASE fixture. The JA3 text is `771,49199-156,10-11,29-23,0-1`.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls.rs#ECH profile

The ECH profile is TLS 1.3 only, also before live ECH config discovery.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/certs.rs#parse_issuer_cn

The parser reads the issuer Common Name of a leaf certificate without an external x509 crate. It walks the DER only as far as the issuer field. Then it scans the RDN sequences for OID 2.5.4.3 (id-at-commonName). The DER structure is a certificate sequence that holds a TBSCertificate sequence with the version, serial number, signature algorithm, and issuer.

The length in a DER element comes from the peer. On 32-bit targets an unchecked sum can wrap, so the code uses `checked_add`.

The test OID 1.3.85.4.3 ends with the bytes of 2.5.4.3 but is a different attribute. The bytes `30 84 FF FF FF FF` are a sequence that claims 4 GiB - 1 bytes of content.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/config.rs#root store and provider

The root store cannot fail. It starts from `webpki_roots::TLS_SERVER_ROOTS`, and the code sets no CRLs. The real ECH leg uses AWS-LC, so all other controls stay on that provider and on TLS 1.3. A read of the fallback cache must not start a direct lookup on an in-path scan.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#module

Each attempt opens a new protected TCP connection with the default TTL. It then sets the outbound TTL to k, sends one ClientHello, and records the first reaction in a fixed window. Without root, the probe never sees router addresses. The result is a hop interval and never an identified device. The OpenSpec change is `tls-interference-hop-comparison`.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#TOTAL_DEADLINE

A silent k costs four windows plus connects. The deadline of 150 s reaches k of about 30 on a path that connects fast.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#AttemptOutcome

Below the path length, an RST, FIN, or alert cannot come from the server, because the ClientHello never reached it.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#LadderAttempt::elapsed_ms

This value is local history only. The ordinary export must not carry per-attempt timings.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#interval verdicts

A ServerHello at k of 1 or 2 means that a local proxy or interception answers. `InconsistentRepetitions` means that the reactions change across repetitions, or that the test reaches the server at a smaller k than the control (load balancing or asymmetric paths).

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#analyze

The function is pure, so a cancelled or partial run keeps its attempts. A measurement from before a network change does not describe the current path. A stop at the deadline is visible as `stop`. Only a run over every k proves that the control never got through. If the test reaches the server at a k where the control does not, the two flows take different paths.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#SocketLadderProbe

The probe runs against the one endpoint that the user selected. It uses the protected direct connector that the TLS probe uses. `SocketLadderProbe::new` fails with `UnsupportedReason::Protect` when a registered protect callback rejects a socket for the target. Runs without a callback (RAW_PATH) stay supported.

A ClientHello flight is built one time for each SNI role, because the ladder sends one ClientHello for each connection. An IP literal parses as a `ServerName`, but rustls then sends no SNI, so the code rejects it.

The connector fails closed on protect. The code tells a protect failure apart from an unreachable server.

Every exit drops the stream after `set_abortive_close`, so each close is abortive and no socket is reused. TCP retransmits the ClientHello at the same TTL inside the window. This is expected.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder/tests.rs#silent_peer_gets_an_abortive_close_after_the_window

Silence lasts the whole window. The close after it is abortive. The peer sees a reset and not a FIN.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/key_log.rs#TlsKeyLogFile

Many instances can write the same file at one time (one for each probe or candidate worker). The code writes each line with one `write` on an `O_APPEND` handle. The kernel appends it atomically. A lock would order only the writers of one instance.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/probe/capture.rs#capture helpers

RFC 9460 section 9.1 adds a prefix to HTTPS query names for ports that are not the default. The provider can reject the published key when it prepares HPKE for the first ClientHello. rustls sets the version and the cipher suite also while it processes a HelloRetryRequest, so the code cannot use them alone as proof of a ServerHello.

A comparison needs the config that was published for this origin. The fallback to the peer IP is for legacy mode only.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/probe/capture.rs#BoundedHandshakeIo

The type checks cancellation and the scan deadline before each read and write. `complete_io` loops until the handshake ends, and a socket timeout bounds only one read. A peer that sends a few bytes within every timeout would otherwise hold the probe past the deadline and past a user cancel.

A short socket wait (`CANCEL_POLL`) lets the code see cancellation on a silent peer. The code retries the same read or write up to its original idle budget, also limited by the scan deadline. The code avoids the error kind `Interrupted`, because rustls retries it inside.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/probe/capture.rs#slow_drip_server

The test server accepts one client and reads its ClientHello. Then it sends a TLS record header and one byte of the record every 100 ms for `drip_for`. Each read returns well within the socket timeout, but the handshake never completes.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/probe/retry.rs#ECH retry

The code retries only the same known peer. A new DNS result cannot authorize the retry.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/probe/retry/tests.rs#unusable_key

An all-zero X25519 public key cannot be used for key agreement.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/types.rs#certificate verification types

`certificate_anomaly` reports that a local verifier rejected the certificate. Use `certificate_verification` for the trust verdict. With a custom policy, the caller owns the decision and the probe infers no WebPKI verdict. `ProbeCertificatePolicy` has explicit overrides, so an unchecked handshake cannot be reported as authenticated.

### native/rust/crates/ripdpi-diagnostics-tls/src/tls/verifier.rs#NoCertificateVerification

This is an explicit diagnostic override that skips the certificate and handshake signature checks. Use it only with authorized diagnostic payloads. It gives no authentication and no evidence of certificate validity. It cannot authorize an ECH retry.

### native/rust/crates/ripdpi-diagnostics-transport/src/lib.rs#unsafe lints

The `unsafe` blocks of this crate are the `getsockopt(IP_TTL)` call in `platform_ttl`, the `poll` connect wait in `transport::protect`, and the `getaddrinfo` and `if_nametoindex` calls in `transport::address`. The crate enables the `undocumented_unsafe_blocks` lint and the `multiple_unsafe_ops_per_block` lint locally. This makes the `// SAFETY:` requirement for each block a build error here. It matches the convention in ripdpi-vless, ripdpi-privileged-ops, and ripdpi-proxy-runtime, while the rest of the workspace migrates step by step.

### native/rust/crates/ripdpi-diagnostics-transport/src/platform_ttl.rs#TTL helpers

`set_outbound_ttl` follows the traffic family. A v4-mapped IPv6 peer sends IPv4 packets, so it gets the IPv4 TTL. `set_abortive_close` sets `SO_LINGER` to 0. The kernel drops unsent data and sends an RST at the next close. `get_local_socket_ttl` may see an IPv4 value for an IPv6 socket on Linux, so use the IPv4 option only on an IPv4 connection. Linux does not give the received TTL as `IP_RECVTTL` ancillary data for TCP sockets. The function returns `None` when the option is not available or does not apply.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#classify_gai_code

Only `EAI_NONAME` means that the name does not exist. `EAI_NODATA` and `EAI_AGAIN` are other failures. `system_lookup` calls getaddrinfo like `ToSocketAddrs` but keeps the return code that std reduces to message text.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#system_lookup_by_family

`AI_ADDRCONFIG` keeps the IPv6 half from returning AAAA answers on a device with no IPv6 address. First-address callers would pick an unusable peer.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#resolver pool ordering

The active counter is only a capacity counter. It publishes no lookup data, and the result channel gives the synchronization. The code releases capacity before the result lets the supervisor start another lookup. The shutdown flag is published with Release before the queue disconnects and the supervisors join. The supervisors poll it with Acquire, which pairs with that store.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#resolve_split

The function looks up IPv6 and IPv4 as two independent jobs that share one budget (RFC 8305 section 3). A stalled family does not hide the answer of the other family. IPv6 addresses come first. The result is an error only when both lookups fail (see `merge_family_results`). In that case NXDOMAIN wins, to keep the tampering signal.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#start_address_resolution

The function submits DNS work to the bounded shared executor. Dropping the receiver abandons the result. The function does not spawn another resolver and does not wait for a platform lookup. It is one `AF_UNSPEC` lookup with one receiver, so there is no place to merge per-family results without another thread.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#tests

The pool served four names at one time before the split. The split must keep that capacity. A bare IPv6 authority must give `InvalidSocketHost` and must not reach the resolver as the corrupted host `2001:db8:` (audit H4 siblings).

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/protect.rs#module

A `Direct` connectivity probe measures the underlying network path of the device. When the VPN of the app is up, an unprotected probe socket goes into the TUN route. It no longer measures the direct path. Also, the `VpnService.protect()` invariant says that the own sockets of the app must not route back into the tunnel that they own. When a protect callback is registered (the VPN is up), the code protects the probe fd so it bypasses the tunnel.

The helpers fail open only when there is nothing to protect against. They do nothing when no callback is registered (desktop, tests, VPN down) and for loopback targets. When a callback is registered and protect fails, the error propagates, so the caller abandons the unprotected probe (fail closed).

The `Socks5` transport connects to its proxy through the direct connector too. The proxy host is the loopback listener of the app (`LocalProxyEndpoint`), which protect skips. The path through the proxy is not routed here. It measures the path through the proxy and must go through the tunnel.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/protect.rs#protected_tcp_connect_until

The attempt stops when `stop` is set. An attempt that lost a connect race then does not complete a connection that nobody reads (RFC 8305 section 5). POLLHUP without `SO_ERROR` means that the peer is gone although no error was recorded, so the code reads `peer_addr`.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/protect.rs#first_routable_address

The function connects a protected UDP socket, which sends no packet, to find a route. Without it, a probe that pins one address can choose an AAAA record on an IPv4-only network and fail every attempt locally. It falls back to the first address when none is routable.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/protect.rs#test helpers

Every test in the crate that changes the process-wide protect slot shares `TEST_LOCK`. The slot is global, so a non-loopback connect must hold the lock. Then it cannot reach the recording callback of another test.

TEST-NET-1 (RFC 5737) is never routed. The SYN gets no answer, and the connect stays pending. A loopback listener with a full backlog does not work, because macOS resets it. If the host has no route, the connect cannot stay pending, so the test has nothing to stop. `SO_RCVTIMEO` reports `WouldBlock` on Unix too, and only a blocking read waits for it first.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/route_experiment/tcp.rs#connect_addresses_with_route_experiment

The connects are sequential on purpose. Each socket binds a fixed source port for each bucket, so attempts cannot race.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/socks5.rs#cancel safety and UDP correlation

Short waits keep the same future and its partial frame state. On abort or timeout, the code drops the future and the socket that it owns alone.

UDP probes select a numeric peer. The code correlates numeric names and does not resolve arbitrary names that a relay supplies in an unauthenticated datagram header.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/socks5/reply_contract_tests.rs#no method reply

The test server sends no method reply. Only cancellation can make the client close.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/socks5/udp.rs#unsupported private request

An unsupported private request cannot leave a control stream that a later exchange can reuse, so the code drops the control stream.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/tcp.rs#MAX_IN_FLIGHT_ATTEMPTS

The limit bounds the threads that one race uses. A failed attempt frees a slot for the next address.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/tcp.rs#connect_direct_address

The function connects exactly one numeric peer through the protected direct path. It keeps the OS error kind for diagnostics. It never does DNS or family fallback.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/tcp.rs#Happy Eyeballs race

Literal peers stay together for Happy Eyeballs, before any fallback DNS work. `interleave_address_families` orders addresses with alternating families (RFC 8305 section 4, First Address Family Count 1). The family of the first address leads, and the order inside each family stays.

`race_addresses_with_abort` races the addresses in order (RFC 8305 section 5). Attempt `i` starts `HAPPY_EYEBALLS_DELAY` after attempt `i - 1` started, or at once when the previous attempt failed. Started attempts keep running, and the first success wins. At most `MAX_IN_FLIGHT_ATTEMPTS` attempts run at one time.

The `decided` flag is set when the caller returns, so a pending attempt does not complete a connection that nobody reads. `connect` receives it to stop a started attempt, and the attempt reads it with Acquire to pair with the Release store of the race owner. `DecideOnDrop` sets the flag on every exit of the race, also a failed spawn. `AttemptReport` sends a `ConnectRacePanicked` failure when it drops before a result was sent (a panicking attempt). The caller then gets exactly one message for each started attempt. The loop wakes for the next start only when one is allowed. Otherwise it waits for a result.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/tcp.rs#race tests

Attempts start at 0, 250, 500, and 750 ms. Sequential tails would take 3 x 2.5 s. Failing attempts fail fast, so each failure frees a slot for the next address. In the slot test, four attempts run at 0, 250, 500, and 750 ms. The fifth waits for the failure at 1200 ms, fails at once, and frees the slot for the sixth.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/types.rs#TransportError

The Display texts are byte for byte the same as the old `Result<_, String>` messages. Diagnostics summaries and JNI-visible text then stay the same. `RouteExperiment` marks the one remaining legacy seam: the route-experiment tracker formats failures into its report as strings.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp.rs#UDP probes

The relay probe is cancellable. The code prepares a new context for each socket or association. Plain Direct can send the second datagram in the same context before the receive deadline. The matcher and the returned request use the first datagram as the context for both sends. The cancel flag uses Acquire order to see the flag that the scan owner published. A valid UDP datagram can be larger than the path MTU after IP reassembly, so the receive buffer has the maximum UDP size.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/correlation_tests.rs#timing tests

The server is still preparing the second byte. The client must close its control stream by the shared deadline, even though each single read could still wait. The test keeps the UDP socket open, so only the closure of the TCP association wakes the client. The test keeps the first receiver open until the exchange has really waited for a reply.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#module

The matrix reports Initial-stage evidence only. The Initial keys come from public inputs. A valid Initial response is therefore not endpoint authentication, handshake success, or HTTP/3 success. A result never changes relay or strategy settings. Each cell uses a new local port, which does not prove a separate NAT mapping.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#QuicMatrixVersion::wire

The version numbers come from RFC 9000 and RFC 9369. A decoy with an unknown version is never a matrix version.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#QuicInitialCodec

The caller supplies the `ripdpi-packets` builders and the DIA-07 Initial-response validator. Every accepted Initial kind (CRYPTO, ACK, other frames, CONNECTION_CLOSE) maps to `ValidInitial`. Version Negotiation maps to `VersionNegotiation`. Retry or a rejected datagram maps to `Other`.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#residual and status types

`residual_state_suspected` means that the fresh-socket controls for the version were valid, but the control after the test timed out. An `Other` control is not counted, because leftover Handshake datagrams of the test flow can cause it. `Inconclusive` says nothing about blocking. `quic_version_status` counts a cell that is missing after a stop as a failed control.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#QuicMatrixConfig::cell_byte_budget

The budget is bytes sent plus bytes received for one Initial exchange. Receiving stops when a datagram crosses the budget, so the total can exceed it by at most that one datagram.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#run_quic_version_matrix

Each repetition alternates the version order. Every cell uses a new protected socket. All sockets stay open until the run ends, so no two cells share a local port. The code checks cancellation and the scan deadline between cells and while a cell waits. A cell that does not complete is dropped, and `stopped` says why.

A cell starts only with its full receive window, so each Timeout means the same wait. A builder that falls back to another version must not count as this version. `exchange` ignores datagrams that answer `earlier` (an Initial sent before on the same socket), and it does not charge them to the budget. Datagrams from other sources are not the reply of the endpoint.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix_tests.rs#test fixtures

The flow is dropped after a test SNI, and fresh flows are not. The test total deadline is 500 ms. Cells 1 and 2 fit, and cell 3 would start near 400 ms and end after 500 ms. The request has 29 bytes, so the third reply of 40 bytes crosses 120 bytes. Fresh flows answer at once. On the residual socket the test reply comes late, during the control wait.

### native/rust/crates/ripdpi-diagnostics-transport/src/transport/watchdog.rs#run_with_socket_watchdog

A socket timeout bounds one read only. Loops such as rustls `StreamOwned::read` or an HTTP body read continue while a peer sends one byte within every timeout. The watchdog shuts the socket down at cancellation or at the scan deadline of the calling thread. The next read or write then fails, which bounds the whole operation. The `socket` argument is a clone of the socket that `operation` uses. The caller decides from the deadline or the flag whether a failure came from the shutdown. Without a deadline and without a flag, or when the OS refuses the watchdog thread, `operation` runs directly.

`run_with_socket_abort_watchdog` does not start the operation if the watchdog cannot start. The caller checks its control state after the operation. The code drops the sender before the scope joins, also when the operation panics.

### native/rust/crates/ripdpi-diagnostics-transport/src/ws_tls.rs#WebSocket TLS probe

The `transport` field is the path of the scan to the host. A SOCKS5 transport sends the host name to the proxy, which resolves and connects it. A direct transport resolves with the bounded system resolver.

rustls can keep reading while a peer sends part of the Upgrade response, so the watchdog guards the read. The root store cannot fail. It starts from `webpki_roots::TLS_SERVER_ROOTS`, and the code sets no CRLs.

In the tests, the slow peer sends a record header and then one byte of the record every 100 ms, so no read times out. The DIAG-1 regression test covers a defect where WSS connected without protecting the fd. A registered protect callback must run before the connect. A protect denial must fail the connect (fail closed) and not proceed unprotected into the TUN.

### native/rust/crates/ripdpi-diagnostics-transport/src/ws_tls_control_tests.rs#Accept fixture

The test uses the example in RFC 6455 section 1.3. It is paired with the fixed Accept value in the same test.
