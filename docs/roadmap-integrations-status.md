# Integrations Roadmap Status

This note summarizes the post-roadmap integration state after the remaining control-plane and rollout work landed in the repo.

## Status

All roadmap phases P0 through P3 are implemented in-repo, and the follow-up integration hardening backlog is now partially closed in code rather than only tracked as documentation.

- P0 transport work is present across WARP, xHTTP, Cloudflare Tunnel relay support, and TLS fingerprint infrastructure.
- P1 desync, routing, WARP preset, and mobile-whitelist hardening surfaces are implemented across native runtime, settings, and app policy catalogs.
- P2 relay expansion is implemented across VLESS, Hysteria2, MASQUE, xHTTP, TUIC v5, ShadowTLS v3, NaiveProxy, relay pooling, and server-capability plumbing.
- P3 detection-resistance work is implemented across TLS profile rotation, signed strategy-pack refresh, adaptive morph policies, QUIC migration controls, and pluggable transports.

## Newly Landed Follow-Up Work

- Cloudflare Tunnel settings now carry explicit `consume_existing` and `publish_local_origin` modes, plus credential references and import-backed secret storage in `RelayCredentialStore`.
- Strategy-pack schema version `3` is active, with feature flags for Cloudflare publish/consume validation, Finalmask rollout, Cloudflare-direct MASQUE, and NaiveProxy watchdog behavior.
- Relay editors and persisted relay profiles now expose Finalmask controls, and runtime startup rejects unsupported relay/finalmask combinations instead of silently ignoring them.
- NaiveProxy now reports helper version metadata, structured failure classes, restart-state telemetry, and bounded watchdog restarts for unexpected helper exits.
- Native relay config contracts now preserve Cloudflare mode/finalmask payloads end to end and validate Finalmask support at the Rust relay boundary as a second line of defense.

## What Remains

What remains is narrowed to runtime-complete transport work and iterative interoperability polish, not unfinished roadmap phases:

- Cloudflare Tunnel publish mode is now modeled, persisted, validated, and feature-gated, but this tree still does not ship a bundled `cloudflared` sidecar or local xHTTP-origin helper binary. The mode is intentionally kept rollout-disabled until that runtime exists.
- Cloudflare-direct MASQUE support now has explicit rollout gating, but interop and staged validation still continue in [native/relay-masque-status.md](native/relay-masque-status.md).
- Finalmask now mutates live xHTTP transport traffic for VLESS Reality xHTTP and Cloudflare Tunnel profiles, with runtime validation limiting support to the modes and transports that are actually implemented.
- NaiveProxy is implemented as a managed subprocess helper with watchdog logic. The remaining work is operational polish and broader field validation, as recorded in [native/relay-naiveproxy-decision.md](native/relay-naiveproxy-decision.md).
- TLS fingerprint catalogs, strategy-pack contents, and rollout flags will continue to need refreshes as upstream fingerprints and censorship conditions evolve.

## Where To Look

- P0 status: [roadmap-integrations-p0.md](roadmap-integrations-p0.md)
- P1 status: [roadmap-integrations-p1.md](roadmap-integrations-p1.md)
- P2 status: [roadmap-integrations-p2.md](roadmap-integrations-p2.md)
- P3 status: [roadmap-integrations-p3.md](roadmap-integrations-p3.md)
- Native relay follow-up notes: [native/relay-masque-status.md](native/relay-masque-status.md)
- NaiveProxy decision record: [native/relay-naiveproxy-decision.md](native/relay-naiveproxy-decision.md)
- Finalmask compatibility: [native/finalmask-compatibility.md](native/finalmask-compatibility.md)
