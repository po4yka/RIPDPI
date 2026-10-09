## Context

Task DGN-1791529750911166 adds a native opt-in family. The existing Home IPv6 Boolean is not sufficient for durable independent evidence.

## Goals / Non-Goals

- Goal: independent IPv4, IPv6 and NAT64 destination TCP evidence in scans, full analysis, history and safe exports.
- Non-goal: prove provider attribution, run a full HTTP service survey, replace runtime address selection, or infer a physical IPv4 underlay from IPv4 success through CLAT.

## Decisions

- Add optional `ipFamilyProbe` configuration: version=1, ipv4Address, ipv6Address, port=443, timeoutMs=1500. Use paired Cloudflare resolver service addresses 1.1.1.1 and 2606:4700:4700::1111. Fixed numeric destinations isolate transport from DNS; TCP is explicitly the measured stage.
- Add task family/stage `IP_FAMILY` / `ip_family`, explicit standalone profile `ip-family-connectivity`, and one raw full-analysis stage. Profiles without config never run it.
- Three ProbeResult rows use probeType `ip_family` and bounded JSON `ipFamilyEvidence`. Contract: version=1; family IPV4/IPV6/NAT64; stage TCP_CONNECT/DNS64_DISCOVERY; status REACHABLE/FAILED/TIMEOUT/NOT_OBSERVED/UNSUPPORTED/CANCELLED/INVALID; optional reason, durationMs, destinationAddress, prefix, prefixLength, discoveryStatus; destinationPort; attemptCount; pathScope RAW_PATH/UNSUPPORTED_PROXY. Discovery statuses NOT_RUN/DISCOVERED/NO_PREFIX/FAILED/INVALID/TIMEOUT. No arbitrary error text.
- Outcomes: ip_family_reachable, ip_family_unavailable, ip_family_inconclusive, ip_family_cancelled. Facts remain in ordinary saved ProbeResult details; no Room migration or new observation variant is needed.
- NAT64 uses only captured network DNS servers, never a public resolver fallback. Validate query-bound AAAA replies for ipv4only.arpa and RFC 6052 /32,/40,/48,/56,/64,/96 synthesis, including the reserved u octet. Prefix discovery alone cannot produce REACHABLE. Connect only to the synthesized reference IPv4 endpoint, never 192.0.0.170/171. Try up to four distinct valid prefixes in resolver response order; ambiguity means multiple embeddings within one address, not distinct valid prefixes. Bound DNS servers/attempts and preserve cancellation.
- Raw-path sockets use the existing protected direct transport. Reject proxy vantage for this profile; existing Kotlin network-epoch guards invalidate a changed scan. No claim of per-socket Android Network binding is added.
- Bounded model decoding rejects unknown enums, invalid sizes, prefixes and duplicate detail keys. Redacted export removes destinations/prefixes and unknown fields before generic archive processing.

## Contracts and ownership

Native writer owns Rust contracts, DNS helpers, probe/runner/registry and tests. UI writer owns app mapping/cards/tests and ten locales. Integration writer owns Kotlin shared models, profile/request/home wiring, export, task/spec/docs, golden fixtures and public API snapshots. Writers use separate worktrees; Gradle is serialized. No dependency, Cargo.lock, schema version, JNI method or Room change.

## Risks / Trade-offs

A single paired control tests one service only; the UI states that limit. IPv4 may traverse CLAT. DNS64 can be configured while translation is unreachable. DNS discovery on the raw default path is scoped by the scan epoch; it is not proof about another interface or a proxy egress.

## Migration Plan

Optional configuration and detail payloads preserve existing profiles and stored results. Verify native transport/prefix/cancellation tests, Kotlin contract/privacy/history tests, localized Compose UI, static analysis, fixtures, API snapshots and architecture health. Record hosted CI, device and Android artifact acceptance separately.

## Protocol sources

- https://www.rfc-editor.org/rfc/rfc7050.html
- https://www.rfc-editor.org/rfc/rfc6052.html

Control endpoint reference: https://developers.cloudflare.com/1.1.1.1/ip-addresses/
