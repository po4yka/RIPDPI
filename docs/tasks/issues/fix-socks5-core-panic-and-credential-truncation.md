---
title: "Fix SOCKS4 reply panic and legacy SOCKS5 credential truncation in ripdpi-socks5-core"
type: task
status: todo
area: proxy
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit found two correctness bugs on the public API surface of `ripdpi-socks5-core`:

1. **SOCKS4 reply panic** — `src/socks4/mod.rs:73`: `ReplyError::as_u8(self)` is a `pub` method whose match covers only 4 of 5 enum variants and falls through to `panic!`. The variants `AddressTypeNotSupported` and `UnknownResponse(u8)` are valid; a SOCKS4 server returning an unusual reply code panics the calling thread. `lib.rs` re-exports `pub mod socks4`, so this is reachable by callers.

2. **Legacy credential truncation** — `src/client.rs:189,191`: the older `Socks5Stream` API still does `user_bytes.len() as u8` / `pass_bytes.len() as u8` with no bounds check. Credentials over 255 bytes silently truncate, desynchronizing the auth sub-negotiation; callers cannot distinguish truncation from a genuine auth failure. The newer `client::outbound` module was already fixed; this legacy path was not.

## Proposed change

1. `socks4/mod.rs:73`: change `as_u8` to return `Option<u8>` (or map unmapped variants to a defined failure code such as `SOCKS4_REPLY_FAILED`) — no `panic!` on valid input. Update callers.
2. `client.rs:189,191`: add a length check before the `as u8` cast; return `Err(SocksError::...)` for credentials > 255 bytes, matching the fix already in `client::outbound`.
3. Add unit tests: every `ReplyError` variant round-trips without panic; a 256-byte username/password yields a clean error, not a truncated frame.

## Acceptance criteria

- [ ] PR confirms current state at `socks4/mod.rs:73` and `client.rs:189,191`.
- [ ] `ReplyError::as_u8` is total over all variants (no `panic!`); signature/return documented.
- [ ] Legacy `Socks5Stream` rejects > 255-byte credentials with an error, no silent truncation.
- [ ] Unit tests cover both fixes.
- [ ] `cargo nextest run -p ripdpi-socks5-core --locked` green; clippy clean.

## Risks / open questions

- Changing `as_u8`'s signature is a (minor) API break for `ripdpi-socks5-core` consumers — check fan-in (audit notes it is a high-pub-surface crate) and update call sites in the same PR.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, items 4–5 / N1, F2-legacy).
- Already-fixed precedent: `ripdpi-socks5-core/src/client/outbound` credential length check.
