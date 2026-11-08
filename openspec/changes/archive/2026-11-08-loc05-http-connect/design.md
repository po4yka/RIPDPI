## Context

LOC-05 covers the local HTTP CONNECT listener. It shares routing and relay code with SOCKS but has a separate request parser and Basic adapter. The audit reproduced seven defects with real loopback streams.

## Goals / Non-Goals

- Goal: fix F01-F07 with separate commits and behavior regressions.
- Goal: preserve early tunnel bytes, bracketed IPv6, TLS, server-first exchange, token secrecy and routing policy.
- Non-goal: forward HTTP, H2/H3 CONNECT, request bodies, proxy TLS, new authentication schemes or device deployment.

## Decisions

- Keep byte parsing in the owning modules. No new production dependency or generic parser framework.
- Parse field values as octets; protocol tokens and authority remain ASCII. Reject malformed fields and ambiguous framing before DNS/dial.
- Accept absent Host only for HTTP/1.0; retain strict matching when Host exists.
- Reuse provisional upstream establishment for delayed CONNECT. For HTTP WS-first, open the known target DC WebSocket before success through a connected-session transport interface, then validate and relay the seed. Preserve non-HTTP behavior and existing seed-DC/fallback policy; do not suppress a backend to pass a test.
- Classify header-too-large explicitly, return 431 and retain the 64 KiB bound.
- Commit each finding separately after a read-only review. Combine tests on the final tree before main integration.

## Contracts and ownership

- Follow the portfolio Ownership section: parser worker owns session/auth; connect worker owns connect_relay; primary owns header limits, planning and integration.
- The connect worker removes the obsolete protocol_io success-reply forwarding helper after every handshake uses the established-upstream reply owner, and keeps literal socket-response coverage at that owner.
- No Kotlin, JNI, protobuf, persisted configuration or wire schema version changes. Reuse the upstream PreparedWsTunnel/open interface; its implementation owns the established WebSocket through relay and observes cancellation.
- No serialized dependency, locale, baseline or golden files are changed. The connect worker owns protocol documentation and reuses the existing WS API. API snapshots must remain unchanged unless a necessary reviewed contract delta is generated.
- H01 and H02 need evidence and an owning contract before implementation; adjacent work must not be duplicated.

## Risks / Trade-offs

- Stricter input rejection changes behavior for malformed clients; preserve valid HTTP/1.0 and field octets.
- HTTP CONNECT to a known WS target returns 502 for failure or 504 for timeout if target-DC WS bootstrap fails before any seed. Classifying a waiting client first would require a false 200; speculative direct SYNs would change privacy. Retain non-MTProto/CDN fallback after successful WS setup and retain non-HTTP flow.
- Provisional connections can affect payload-based routes; retain policy checks before application bytes and cover route changes and server-first behavior. A later group change opens another TCP connection; a server that accepts only one connection can fail. This follows the existing shared routing contract.
- Machine-wide builds share one RIPDPI lane; use worktree outputs, sccache and at most four jobs.

## Migration Plan

No data migration. Run affected package tests, all-target Clippy, formatting, locked metadata, architecture health and just ci-gates. Roll back through explicit revert commits if needed. Physical devices, production and release artifacts are outside this change.
