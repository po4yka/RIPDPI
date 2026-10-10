## Context

Task DGN-1791527373647141 extends native DNS observations and the separate Kotlin DNS tools. Current UDP validation discards negative packets; encrypted oracle selection discards empty answers. Kotlin tools return address lists and overstate empty/different results.

## Goals / Non-Goals

- Goal: preserve bounded response facts through collection, history, localized UI and safe export.
- Non-goal: new target lists, DNSSEC validation, provider attribution, runtime resolver policy changes, or separate IPv6/NAT64 reachability probes. The current scan queries A; metadata names the query type explicitly and parsing tests cover A/AAAA boundaries.

## Decisions

- Add optional `udpResponse` and `encryptedResponse` to DNS observations. Their common `DnsResponseSemantics` contract uses camelCase keys: queryType (A), outcome (SCREAMING_SNAKE_CASE), nullable rcode, ttlMinSeconds, ttlMaxSeconds, negativeTtlSeconds, truncated, authoritative, recursionAvailable, authenticatedData, hasSoa; bounded cnameTargets and extendedDnsErrorCodes lists. Outcomes: ANSWER, NODATA, NXDOMAIN, SERVFAIL, REFUSED, OTHER_RCODE, TRUNCATED, TIMEOUT, TRANSPORT_ERROR, MALFORMED, NOT_OBSERVED.
- Probe details carry the same bounded JSON under `udpDnsResponse` and `encryptedDnsResponse` for persistence and shared current/history UI. Kotlin decodes these safely; raw JSON is hidden from generic UI rows.
- Diagnostic parsing validates response identity/question and CNAME/address ownership. Strict runtime resolver APIs retain their current behavior. Numeric EDE data is resolver-supplied context, not independent proof. AD is reported, not validated locally.
- `encryptedResponse` describes the selected usable oracle attempt, or the primary attempt when no oracle has usable addresses. It is not an aggregate of every fallback attempt. Existing resolver details identify that scope.
- Keep empty semantic responses distinct from usable encrypted oracle address answers. Report response evidence even when oracle address comparison is inconclusive. Valid response errors must not be classified as UDP transport blocking.
- Existing list-only Kotlin probes retain source compatibility. Add semantic-aware adapters where wire data exists and conservative fallback classification where it does not. Cancellation must propagate.
- Bounds: at most 16 CNAME targets (253 UTF-8 bytes each), 16 EDE codes (0..65535), RCODE 0..4095, TTL 0..4294967295. Invalid metadata is dropped or unknown; no raw DNS error text or packet enters the new evidence.

## Contracts and ownership

- Native writer owns native diagnostic DNS, resolver helpers if required, native observation types/mapping and tests. Integration writer owns Kotlin common evidence model, privacy/export and task records. Tools writer owns dpi Kotlin tools/tests. UI writer owns app mapping/cards/tests and ten locales. Separate worktrees; serialized Gradle and shared contract decisions.
- No Cargo.lock, dependency, schema version, JNI signature, Room migration, golden or baseline change is planned. Additive fields omit absent defaults.

## Risks / Trade-offs

- CNAME names can identify targets: export redacts both structured observations and nested detail JSON.
- NXDOMAIN or REFUSED can originate at authoritative services: wording does not attribute cause to the provider.
- CDN differences and TTL/AD/EDE values do not prove tampering.
- Legacy and list-only probes have incomplete evidence; do not fabricate RCODE.

## Migration Plan

Optional JSON fields preserve old reports. Existing stored probe details need no migration. Validate parser adversarial fixtures, loopback transport, native runner/classifier, Kotlin compatibility/privacy, app UI, ten-locale lint, static analysis, native architecture and independent review. Hosted CI, APK and physical-device acceptance remain distinct.

## Protocol sources

- https://www.rfc-editor.org/rfc/rfc2308.html
- https://www.rfc-editor.org/rfc/rfc8914.html
