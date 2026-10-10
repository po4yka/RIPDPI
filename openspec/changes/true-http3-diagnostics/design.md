## Context

Existing QUIC probes produce Initial-only evidence. RFC 9114 requires HTTP semantics over a verified QUIC connection; an advertised protocol is not an observed response. The app already has bounded probe JSON, network-scope revocation and current/history cards.

## Goals / Non-Goals

- Goal: perform a real bounded HTTP/3 GET and retain exact observed progress.
- Goal: manual raw-path profile, full analysis, current/history/summary/export support.
- Non-goal: modify strategy QUIC scores, implement proxy UDP carriers, follow redirects, or infer provider interference.

## Decisions

- Use the existing locked quinn 0.11.12, h3 0.0.8, h3-quinn 0.0.10 and rustls 0.23.45 packages. No upstream package or version is added.
- Use current-thread Tokio, protected UDP, verified TLS/hostname and ALPN h3. A single absolute timeout covers DNS, connection, request, headers and body. Poll cancellation and close all streams/endpoints/driver tasks.
- Use one reviewed control: www.cloudflare.com:443/cdn-cgi/trace. Do not persist its returned data, which can contain client IP. The report contains only bounded status and counters.
- Keep raw-path and proxy-path authority separate. Unsupported proxy execution never opens a direct connection. This release does not add a SOCKS QUIC carrier.
- HTTP/3 confirmation requires response headers. A non-2xx status is a confirmed H3 response with an HTTP error; a body timeout or cap retains protocol confirmation but not full completion.

## Contracts and ownership

Task DGN-1791532838740620 records isolated writers. Rust owns its contracts/manifests, integration owns Kotlin contracts, Cargo.lock and API snapshots, app owns all locales, and a golden specialist owns the two shared fixtures and generated asset. Gradle runs are serialized.

### Optional request and evidence

Optional request/profile field http3Probe; config class Http3ProbeConfig:
version=1 (Int/u32), host="www.cloudflare.com", port=443 (Int/u16), path="/cdn-cgi/trace", connectIp:String?=null, timeoutMs=5000 (Long/u64, 250..15000), maxResponseBytes=65536 (Long/u64, 1..262144). Defaults must decode empty config. host bounded ASCII DNS name or numeric IP; connectIp numeric literal only; path absolute origin form, <=1024, no fragment, query, controls, whitespace or authority. GET only, no redirects/fallback. One reviewed control in manual raw-only profile http3-connectivity; full stage key http3; phase/probeType http3; task family HTTP3 / Rust Http3. No schema bump, optional omitted on legacy serialization.

Evidence detail key http3Evidence. Kotlin Http3ProbeEvidence; Rust Http3Evidence:
version:Int=1
stage: Http3ProbeStage = DNS|QUIC_HANDSHAKE|HTTP_REQUEST|HTTP_HEADERS|HTTP_BODY
status: Http3ProbeStatus = COMPLETE|HTTP_ERROR|BODY_LIMIT|FAILED|TIMEOUT|CANCELLED|UNSUPPORTED|INVALID|NOT_OBSERVED
dnsStatus: Http3DnsStatus = NOT_RUN|PINNED|RESOLVED|FAILED|TIMEOUT
reason:String? (stable codes below)
peerAddress:String? numeric IP (not socket string), redacted in export
alpn:String? only h3 allowed in published evidence, mismatch reason alpn_mismatch with null
 tlsValidated:Boolean=false
http3Validated:Boolean=false (true only after genuine h3 response headers)
requestSent:Boolean=false
httpStatus:Int? (200..599 final response)
responseBytes:Long/u64=0 (<=maxResponseBytes)
bodyComplete:Boolean=false
attemptCount:Int/u32=0 (bounded <=4, QUIC attempts only)
durationMs:Long? total elapsed
handshakeElapsedMs:Long? elapsed since probe start
headersElapsedMs:Long? elapsed since probe start
firstByteElapsedMs:Long? elapsed since probe start
pathScope: Http3PathScope=RAW_PATH|UNSUPPORTED_PROXY

Reason codes: dns_error,dns_timeout,no_addresses,connect_error,tls_error,alpn_mismatch,http3_error,http_status_error,body_limit,timeout,deadline_exceeded,cancelled,unsupported_path,invalid_config,protect_failed,network_scope_unverified,io_error.
Outcomes: http3_complete (COMPLETE, 2xx complete body, Healthy), http3_http_error (HTTP_ERROR, non-2xx final status complete body, Attention), http3_incomplete (headers validated but body incomplete/limit/error, Attention), http3_unavailable (actual failed attempt before headers, Attention), http3_inconclusive (unsupported/invalid/no observation, Inconclusive), http3_cancelled (Inconclusive).

No HTTP body or header values stored, no raw error text. Missing/malformed/duplicate evidence stays unknown. Network scope invalidation downgrades status NOT_OBSERVED + reason network_scope_unverified; protocol facts retained as historical observations but UI/scale explicitly unverified.
TLS certificate/hostname verification required; no production insecure override. Test-only custom root trust path or existing internal verifier seam is allowed if not exposed in serialized request. Direct socket protection before use, original hostname for SNI/authority even with pinned peer. QUIC handshake TLS and h3 not inferred from Initial or Alt-Svc. Cancellation/deadline cover entire run including DNS, require resource cleanup. IN_PATH explicit unsupported, never direct fallback. Current_thread Tokio runtime; h3 driver polled until completion, cancellation-safe.

Root adds ConnectionLaneKind.HTTP3 and ConnectionStage.QUIC_HANDSHAKE (the existing QUIC_RESPONSE remains Initial-only). Root owns shared Kotlin types + catalog/core. Native writer owns Rust source and crate manifests, root owns lock. App worker owns app+10locales (including stage/lane labels and old QUIC/H3 fingerprint rename to QUIC Initial fingerprint). No agent commits.

## Risks / Trade-offs

- A peer can stall any phase: one absolute deadline, cancellation polls, stream/body/header limits and local failure tests bound resources.
- QUIC/TLS may succeed without HTTP: preserve separate TLS, request, headers and body facts.
- Local tests cannot prove real-network behavior: report device, artifact and remote CI acceptance separately.
- Contract drift: legacy omission tests, shared Kotlin/Rust fixture checks and generated API snapshots.

## Migration Plan

Optional fields preserve legacy documents and request encoding; no Room, protobuf or JNI migration. Rollback removes the optional profile and producer; old records remain parseable as unknown details. Run native real-peer regressions, Kotlin and app tests, local-network admission tests, locale lint, static analysis, native Android build and independent/async review.

References: https://www.rfc-editor.org/rfc/rfc9114.html and https://docs.rs/quinn/0.11.9/quinn/struct.Endpoint.html. Implementation uses locked 0.11.12 source as API authority.
