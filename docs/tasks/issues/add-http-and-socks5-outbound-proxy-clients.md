---
title: Add HTTP and SOCKS5 outbound proxy clients
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add HTTP and SOCKS5 outbound proxy clients #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

Add generic HTTP CONNECT and SOCKS5 outbound client adapters so profiles
whose upstream is a commodity HTTP or SOCKS5 proxy can be used.

## Context

Many subscription providers include nodes that are "just an HTTP proxy"
or "just a SOCKS5 proxy" over TLS; without these adapters, the corres-
ponding profile types in Clash/sing-box subscriptions cannot connect.
RIPDPI has SOCKS5 as a local inbound, but not as an outbound adapter
consumable by the relay dispatch. SOCKS4/4a are deliberately excluded
as legacy; add only if a real subscription sample requires them.

## Acceptance criteria

- [ ] `ripdpi-http-proxy` adapter in `ripdpi-relay-core` (or a dedicated
    crate) speaks HTTP CONNECT; supports optional Basic auth and TLS
    on the upstream connection (HTTPS proxies).
- [ ] `ripdpi-socks5-client` adapter supports username/password auth
    plus unauthenticated mode; UDP ASSOCIATE is out of scope for v1.
- [ ] Both adapters plug into the existing outbound dispatch; no
    parallel supervisor.
- [ ] Profile editors for each: server + port, auth fields, TLS toggle
    for HTTP, SNI override for HTTPS proxies.
- [ ] Clash YAML, sing-box JSON, and URI-list subscription parsers
    route `http`, `https`, `socks5`, `socks5-tls` node types to
    these adapters.
- [ ] Credentials are redacted in all diagnostic surfaces.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/fmt/http/HttpBean.java` — bean fields: `username`, `password`, `tls`, `sni`, `allowInsecure`.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/http/HttpFmt.kt` — `http://` / `https://` URI parse. Port.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/socks/SOCKSBean.java` — fields: `protocol` (`PROTOCOL_SOCKS5` / `PROTOCOL_SOCKS4` / `PROTOCOL_SOCKS4A`), `username`, `password`, `tls`, `sni`.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/socks/SOCKSFmt.kt` — URI codec for the four SOCKS variants.

**Outbound engine (NOT from NekoBox):** build as thin Rust adapters (`hyper` for HTTP CONNECT, `tokio-socks` or hand-rolled for SOCKS5). Total Rust: ~300 lines combined.

**Adapt:** Bean field set (drop SOCKS4/4a per task scope), URI codec for `http`/`https`/`socks5`. **Skip:** NekoBox's sing-box delegation; SOCKS4/4a variants.

## Links

- [[Epic - Extended outbound protocol support]]
- [[Epic - NekoBox subscription and profile import]]
