## Context

Task PRX-1794071497680636 addresses five confirmed local SOCKS5 defects. The existing delayed route needs application bytes, but a standard client waits for CONNECT success before sending them.

## Goals / Non-Goals

- Goal: accurate replies, early header validation, strict auth framing, and working payload selection.
- Non-goal: new auth methods, remote DNS ownership, chain BND semantics, or lifecycle redesign based on unconfirmed hypotheses.

## Decisions

- Establish a provisional upstream using a known IP/default route before SOCKS5 success. Prefer a route that matches without unknown payload; only use the original unknown-payload route when no known route exists. Keep the original payload-selection origin so an earlier host-filtered group can win after the payload arrives. Send its real local endpoint. Hold the socket and session guards during the payload probe. Reuse it when the final route and egress match; otherwise replace it before sending application bytes. A payload-dependent Block sends no application data. The initial SYN follows the existing IP/default policy because a standard client cannot provide its hostname payload before success. Document this observable compatibility trade-off.
- Validate VER and RSV before consuming the request address and in the public session parser. The reader returns an unsupported address-type header to the parser without consuming an unknown tail. The parser accepts a four-byte header for rejection and performs address-specific bounds checks. Truncated known-address I/O stays an I/O error.
- Use the existing typed policy-rejection model for target and routing denial. Unclassified OS PermissionDenied remains a general failure.
- Reject zero credential lengths with an auth failure. Keep token comparison and correlation-ID semantics.

## Contracts and ownership

- Rust crates: ripdpi-proxy-runtime and ripdpi-session. No Kotlin, JNI, protobuf, persistent schema, locale, dependency, golden, or baseline changes.
- connect_success agent owns delayed connect/relay/routes and their tests, in its own worktree.
- framing_auth agent owns protocol readers, session socks5 parser, and framing/auth tests, in its own worktree. Separate commits for F2, F3, F5.
- policy_reply agent owns typed policy errors and policy-reply tests, in its own worktree.
- Root owns portfolio/OpenSpec files and generated board; shared handbook files are not changed. Root serializes overlapping handshake/test hunks at integration. No serialized schema/lockfile writer is needed.

## Risks / Trade-offs

- A provisional connection can contact the IP/default route before payload-based routing. It carries no application bytes until the final decision. Tests must verify reuse, replacement, policy denial, server-first, and correct guards.
- Existing optimistic-success tests encode the defect and must be changed to assert real connection outcomes.
- Auth and framing rejection changes only invalid inputs. Test valid fragmented/coalesced input and max-sized fields.

## Migration Plan

No data migration. Revert an individual defect commit to roll back that behavior. Run targeted Cargo tests with --locked, affected crate tests, independent read-only review, just ci-gates, architecture-health check, and locked metadata before main integration. Device and external-network proof are not implied by host loopback tests.
