## Context

Task ID: `TRN-1794198500598113`. The six STD-03 findings concern owned TLS connection behavior and active diagnostics.

## Goals / Non-Goals

- Goal: repair F1-F6 with independent regression evidence and one commit for each finding.
- Non-goal: enable Real ECH in REALITY, add server-side ECH, or probe public servers.

## Decisions

- Treat configured Real ECH as fail-closed. The vendored Boring setter will reject unusable lists before emitting cleartext inner SNI; H2 callers also require acceptance. Do not duplicate backend suite selection in Rust.
- Make the vendored retry getter return no data for unauthenticated state. Keep its authentication guard; remove the abort and misleading malformed placeholder. This avoids a new unsafe binding or dependency wrapper.
- Filter mandatory SVCB compatibility before selecting ECH; retain raw metadata parsing. Follow HTTPS aliases with three hops and cycle detection in Kotlin.
- Keep the pinned quinn-proto 0.11.18 source locally with a typed TLS-error callback before error string conversion. Preserve its package version and licenses; the integration owner serializes its Cargo source-patch and lockfile change. No package version is added or upgraded. xHTTP declares its existing Boring backend package directly because its retry helper names the builder type.
- Retry a cryptographically authenticated rejection once, using the original resolved endpoint, trust and overall budget. Keep retry evidence structured. Do not retry a failed certificate or a second rejection. QUIC error conversion must preserve the retry evidence if required by the pinned adapter.
- DoH cancellation calls Call.cancel. System DNS uses CancellationSignal. Native ECH uses per-request watch cancellation with HandleRegistry, so cancellation drops the current-thread runtime owned operation. Add create, cancel and release JNI lifecycle calls, with release after the worker returns. The existing connect descriptor changes from String to (Long, String), an internal binary contract break; mixed old/new Kotlin/native pairs are unsupported.

## Contracts and ownership

- tls_safety_fixes owns TLS profile helpers, vendored BoringSSL, MASQUE H2/xHTTP F1/F2.
- dns_mandatory_fix owns DNS selection, Kotlin HttpsDnsWireParser.kt compatibility and tests F3.
- ech_diagnostics_fixes owns Kotlin HTTPS resolver and native cancellation lifecycle F4/F6, including native_ech.rs, bridge APIs and a JNI smoke test.
- Integration owner owns planning, F5 consumers, generated contract artifacts and combined validation. F5 waits for F1/F2 on shared TLS/transport files and for F6 on native_ech.rs.
- Cargo.lock changes only the pinned Quinn source and existing dev-dependency edges. Locale, golden and quality-baseline files remain unchanged. Expected JNI symbols or public API artifacts are regenerated with their owning scripts on the combined tree.

## Risks / Trade-offs

- Backend local semantics differ from upstream: document both safe changes and run TLS profile and consumer tests.
- Retry can stretch latency: retain one absolute deadline and original addresses.
- Cancellation/release races: allocate monotonic handles, retain registration through the worker, test pre-cancel and concurrent lifecycle.
- JNI updates require Kotlin and native outputs from the same source. Keep all call sites and expected symbol contracts coherent.

## Migration Plan

No persisted migration. Ship Kotlin and native changes together. Revert the corresponding repair commit to roll back a change; never weaken certificate validation, socket protection or quality gates. Run targeted RED/GREEN tests, affected builds, general/JNI/async reviews and ci-gates before main integration and push.

The MASQUE UDP operation owns rejection state across its outer timeout and any
HTTP fallback decision. The typed Quinn callback records authenticated rejection
before the connection future resumes. A short mutex protects this state. No mutex
guard crosses an await. An outer timeout after rejection cannot restart the
operation through H2 with the stale config. Ordinary HTTP 405/501 fallback remains
available when no authenticated ECH rejection occurred.
