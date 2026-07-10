---
title: Add a parallel active-probe race for initial transport selection
type: task
status: doing
area: transport
priority: high
owner: Codex
parent: epic-transport-obfuscation-research
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
source_wiki_pages:
  - "whitelist-dpi-confirm-good-paradigm"
  - "urltest-dual-transport-fallback"
linked_task: null
---

## Goal

Race the simple flavor's seeded VLESS+Reality and Hysteria2+Salamander relay paths with an application-level probe before the VPN TUN is exposed, select the first confirmed-good transport, and retain the existing post-connection failover and UCB1 behavior.

## Scope

- Parse the embedded bundle's explicit `urltest` URL and require one TLS-mimicry candidate plus one UDP-obfuscation candidate.
- Start both relay runtimes concurrently on ephemeral loopback ports, retain the first path returning HTTP 2xx, and stop the loser.
- Cache only confirmed winners for 24 hours under the hashed network scope and candidate-set signature; use the cache only when both fresh probes fail.
- Re-run on normal startup and network handover, but skip the race during `FailoverCoordinator` self-induced restarts.
- Keep full flavor, proxy mode, AWG, command-line settings, native relay schema, JNI, UCB1, and periodic post-connection evolution unchanged.

## Acceptance criteria

- [ ] A stalled Reality application exchange does not delay selection of a healthy Hysteria2 path until the legacy timeout.
- [ ] A blocked UDP path selects healthy Reality.
- [ ] The TUN is not established before a probe-confirmed winner or eligible cached fallback exists.
- [ ] The first valid HTTP 2xx response wins and the losing runtime is stopped without surfacing an unexpected-exit event.
- [ ] Cached fallback is scoped by hashed network identity and candidate signature, expires after 24 hours, and is not refreshed by fallback use.
- [ ] Handover re-races; self-induced post-connection failover restart does not.
- [ ] Focused Rust, Kotlin, simple-flavor, architecture, static-analysis, and controlled relay-lab gates pass.

## References

- `/Users/po4yka/GitRep/censorship-bypass/wikis/tspu-dpi-internals/wiki/concepts/whitelist-dpi-confirm-good-paradigm.md`
- `/Users/po4yka/GitRep/censorship-bypass/wikis/transport-protocols/wiki/concepts/urltest-dual-transport-fallback.md`
