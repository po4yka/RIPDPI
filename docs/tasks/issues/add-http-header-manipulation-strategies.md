---
title: Add HTTP header manipulation strategies
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [expose-existing-techniques-as-config-addressable]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Add HTTP header manipulation strategies #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Create `ripdpi-strategy-http` crate implementing four HTTP-layer bypass techniques: `domcase` (alternate hostname case), `hostcase` (random Host header case), `methodeol` (extra CR after HTTP method line), and `unixeol` (Unix-style line endings in HTTP headers). These confuse DPI systems that do strict HTTP header string matching.

## Context

These four techniques are implemented in zapret2's Lua library (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — functions `http_domcase`, `http_hostcase`, `http_methodeol`, `http_unixeol`). They operate on the raw HTTP request bytes before the `Write` action — no raw socket required. They are Tier 0 techniques (available on all Android devices without root). In RIPDPI, they hook into `execute_tcp_actions()` as a `PreProcess` step that modifies the payload buffer before the first `Write` action.

Technique details:

- `domcase`: rewrite HTTP `Host:` header value with alternating letter case per domain label. e.g. `Host: example.com` → `Host: eXaMpLe.CoM` (precise algorithm: in each dot-separated label, alternate uppercase/lowercase starting from second character)
- `hostcase`: random case for each character of the Host header value (seeded from flow ID for determinism per connection)
- `methodeol`: insert an extra `\r` between the HTTP method line and the next header. Changes `GET / HTTP/1.1\r\n` to `GET / HTTP/1.1\r\r\n`. Some HTTP parsers accept this; some DPI systems do not.
- `unixeol`: replace all `\r\n` header delimiters with `\n`. Valid HTTP/1.1 allows `\n` as a line ending per RFC 7230 but many DPI systems only match `\r\n`.

Each function takes `&mut [u8]` (raw payload), finds the `Host:` header boundary using a simple byte scanner (not a full HTTP parser — maintain simplicity), applies the transformation in-place or via a new `Vec<u8>`, and returns the modified payload. Register each as a standalone `DesyncStrategy` that implements `matches()` by checking `dissect.proto == L7Protocol::Http`.

## Acceptance criteria

- [ ] `ripdpi-strategy-http` crate compiles; all four technique structs implement `DesyncStrategy`
- [ ] `domcase` correctly alternates case per label character starting at index 1 of each label (matches zapret2 output for `example.com` → `eXaMpLe.CoM` pattern)
- [ ] `hostcase` produces deterministic output for the same flow ID (test: same FlowId always produces same casing)
- [ ] `methodeol` only applies to HTTP/1.x requests (detect `GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|CONNECT` prefix)
- [ ] `unixeol` replaces `\r\n` sequences only within the header section (before the blank line separator), not in the body
- [ ] All four techniques are registered in `StrategyRegistry` with IDs: `"http_domcase"`, `"http_hostcase"`, `"http_methodeol"`, `"http_unixeol"`
- [ ] Techniques are Tier 0 — `required_capabilities` returns empty set
- [ ] Unit tests cover: correct transformation output, no modification when Host header absent, body unchanged by unixeol

## Source references

- zapret2 implementation: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — search for `http_domcase`, `http_hostcase`, `http_methodeol`, `http_unixeol`
- RIPDPI tcp actions: `native/rust/crates/ripdpi-desync-runtime/src/tcp_actions.rs` — `execute_tcp_actions()` dispatch to add `PreProcess` variant
- RIPDPI desync types: `native/rust/crates/ripdpi-desync/src/types.rs` — add `PreProcessPayload(PayloadTransform)` to `DesyncAction` enum

## TDD workflow

1. **Write tests first** — before any implementation code, write tests that exercise each of the four HTTP header transformation functions against known byte inputs and expected byte outputs.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-http` and confirm each new test fails with "function not found" or similar compile error that becomes a logical failure once stubs exist.
3. **Implement** — write the minimal byte-manipulation code to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-http/tests/domcase.rs` — given `b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"`, assert output contains `b"Host: eXaMpLe.CoM"` (exact alternating pattern); also assert body is unchanged; fails until `domcase` is implemented
- `native/rust/crates/ripdpi-strategy-http/tests/hostcase.rs` — given fixed FlowId seed, assert `hostcase` produces deterministic Host header casing (same input → same output across two calls); fails until implemented
- `native/rust/crates/ripdpi-strategy-http/tests/methodeol.rs` — given `b"GET / HTTP/1.1\r\nHost: x.com\r\n\r\n"`, assert output starts with `b"GET / HTTP/1.1\r\r\n"`; assert non-GET methods (POST, PUT) are also transformed; fails until implemented
- `native/rust/crates/ripdpi-strategy-http/tests/unixeol.rs` — given headers with `\r\n`, assert headers section uses `\n` only; assert body (after blank line) is NOT modified; fails until implemented
- `native/rust/crates/ripdpi-strategy-http/tests/no_host_header.rs` — all four techniques applied to a payload with no `Host:` header return the payload unchanged (no panic, no modification); fails if techniques don't handle missing header
- `native/rust/crates/ripdpi-strategy-http/tests/strategy_trait.rs` — assert all four structs implement `DesyncStrategy`, `matches()` returns false for TLS payloads, and `describe().required_capabilities` is empty (Tier 0)

## Definition of done

`cargo test -p ripdpi-strategy-http` green; `domcase` registered and selectable from YAML config. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
