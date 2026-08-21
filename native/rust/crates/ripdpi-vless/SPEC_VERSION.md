# Spec Version

This crate's wire format (VLESS, REALITY, XTLS-Vision) is pinned against the following upstream reference. Any wire-affecting upstream change at or after the pinned point requires a tracking issue and an explicit decision to re-pin or document divergence.

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v1.260206.0
- **Upstream commit:** `12ee51e4bb1d02ece4ef4b7114efa2bcdc130995`
- **Last reviewed:** 2026-07-28
- **Owner:** RIPDPI native transport maintainers

## Scope

This crate implements the VLESS client wire protocol, the REALITY TLS handshake (X25519 + HKDF-SHA256 session_id), the XTLS-Vision flow addon, Xray-compatible XUDP over a destination-less VLESS Mux request, and the separate SagerNet sing-mux version-0 carrier with yamux inner sessions. The xHTTP transport that layers on top of this is implemented in `ripdpi-xhttp`.

## Drift policy

xray-core is on a fast release cadence. `.github/workflows/upstream-spec-watch.yml` and `docs/native/upstream-spec-watch-runbook.md` document the current pin-review process. Known deadlines as of 2026-05-15 (dispositions below):

- 2026-06-01 — VLESS-without-flow deprecation + `allowInsecure` auto-disable
- xray-core v26.1.18 — XHTTP+REALITY combination breakage

When drift is detected, open or update the relevant protocol task and keep this pin explicit until the wire change has been reviewed.

## Deadline dispositions

Both deadlines above have elapsed. Disposition as of 2026-08-21, based on the client tree rather than an upstream release diff — the weekly watch is still format-only and does not detect upstream enforcement automatically:

- **2026-06-01 — VLESS-without-flow deprecation + `allowInsecure` auto-disable.** Client-side readiness is in place. The native transport resolves `VlessFlow::default()` to `xtls-rprx-vision` (`src/addons.rs`), so relay connections never send flow-less VLESS unless a profile explicitly selects `None`, and `core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/XrayConfigValidator.kt` auto-disables `streamSettings.tlsSettings.allowInsecure = true`. No further action required at this review; re-verify both behaviors at the next pin review against whatever upstream actually shipped, since the deadline describes upstream intent that this repository has not yet diffed.

- **xray-core v26.1.18 — XHTTP+REALITY combination breakage.** Owned by `crates/ripdpi-xhttp/SPEC_VERSION.md`, whose drift section still carries this deadline unreviewed with no owner. This crate's pin is unaffected: the Reality handshake and Vision flow here are independent of the XHTTP framing change, and the combination lives entirely in the xhttp layer. The follow-up stays with that crate's next pin review.
