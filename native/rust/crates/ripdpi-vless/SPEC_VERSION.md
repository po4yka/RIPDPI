# Spec Version

This crate's wire format (VLESS, REALITY, XTLS-Vision) is pinned against the
following upstream reference. Any wire-affecting upstream change at or after
the pinned point requires a tracking issue and an explicit decision to re-pin
or document divergence.

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v1.260206.0
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the VLESS client wire protocol, the REALITY TLS
handshake (X25519 + HKDF-SHA256 session_id), the XTLS-Vision flow addon, and
VLESS-mux framing. The xHTTP transport that layers on top of this is
implemented in `ripdpi-xhttp`.

## Drift policy

xray-core is on a fast release cadence. The recurring upstream watch
documented in `docs/tasks/issues/recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes.md`
plus the workflow in `.github/workflows/upstream-spec-watch.yml` diff this
pin against upstream `main` weekly. Known deadlines as of 2026-05-15:

- 2026-06-01 — VLESS-without-flow deprecation + `allowInsecure` auto-disable
- xray-core v26.1.18 — XHTTP+REALITY combination breakage

When drift is detected, the watch job opens or updates a tracking issue.
