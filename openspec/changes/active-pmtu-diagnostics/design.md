## Context

Only interface MTU exists. Quinn already provides authenticated DPLPMTUD and a real size-cliff test fixture.

## Goals / Non-Goals

- Goal: independent IPv4/IPv6 active UDP payload lower bounds with honest limits.
- Non-goal: ICMP probing, raw sockets, exact whole-path MTU, automatic VPN MTU changes or provider attribution. QUIC failure remains inconclusive.

## Decisions

Reuse Quinn 0.11.12 on a dedicated current_thread runtime. Protect sockets through the existing bounded worker; reject sockets that may fragment. Use certificate/hostname verification and h3 ALPN without making HTTP success a prerequisite. Observe ACK-updated path statistics for a bounded window; no public search-complete API exists. The peer UDP cap is not exposed, so never infer it from QUIC DATAGRAM limits. Record payload bytes only, not guessed IP wire size.

Profile pmtu-connectivity; optional pmtuProbe config version1, host www.cloudflare.com, port443, connectIpv4/connectIpv6 nullable pins, timeoutMs10000 per family, observationMs3000, upperBoundUdpPayloadBytes1472. Validate timeout250..15000, observation250..10000 and <=timeout, upper bound1201..1472, bounded hostname and correct literal family pins.

Evidence per family: version1, method QUIC_DPLPMTUD, addressFamily IPV4/IPV6; status OBSERVED/INCONCLUSIVE/FAILED/TIMEOUT/CANCELLED/UNSUPPORTED/INVALID/NOT_OBSERVED; reason nullable stable code, peerAddress nullable; tlsValidated false, alpn nullable, fragmentationPrevented false; initialUdpPayloadBytes1200; configuredUpperBoundUdpPayloadBytes1472; currentUdpPayloadBytes nullable; acknowledgedUdpPayloadLowerBoundBytes nullable (>1200 only); sentProbeCount/lostProbeCount/blackHoleCount zero; observationWindowComplete false; configuredUpperBoundReached false; durationMs/handshakeElapsedMs nullable; pathScope RAW_PATH/UNSUPPORTED_PROXY. No samples initially: final counters and confirmed lower bound are sufficient. OBSERVED requires a confirmed lower bound and complete observation window. Partial facts survive cancellation/timeouts but are not a healthy final result.

## Contracts and ownership

- Native writer: all Rust diagnostic sources/tests/manifests; no Cargo.lock, public API snapshots, schema versions or golden writes.
- UI writer: app mappings/cards/tests and all ten locale resource sets only.
- Integration writer: Kotlin contracts, catalog sources/tests, generated asset, request/full-run wiring, scope/privacy, task/spec/docs, Cargo.lock, API snapshots and translation manifest.
- Golden specialist: only the two authorized shared catalog/taxonomy fixtures, in an isolated worktree.
- Each writer uses a separate worktree. Gradle runs are serialized. No writer commits; integration owns the final commit.

## Risks / Trade-offs

UDP or QUIC filtering can prevent measurement; say inconclusive. Peer limits and ceilings can stop growth; lower bounds remain scoped. Probe loss during search is expected and does not prove a black hole. Existing quic-mtu-test-util fixtures exercise real ACK behavior under IPv4/IPv6 cliffs.

## Migration Plan

Add optional config and typed details without changing JNI/Room/schema versions. Old rows remain readable. Rollback removes the profile and producer. Run affected Rust tests/clippy, Kotlin diagnostics tests, catalog and golden gates, app UI tests, locale lint/staticAnalysis, locked metadata, architecture checks and native Android build. Separate device, APK and remote CI evidence.

References: https://www.rfc-editor.org/rfc/rfc8899.html and locked Quinn source.
