---
title: Add amneziawg URI codec for profile share and import
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add amneziawg URI codec for profile share and import #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

Define and implement an `amneziawg://` URI scheme for single-profile
sharing, plus integrate it into the share-sheet intent filters, QR
scanner dispatcher, and clipboard-import flow.

## Context

There is no standardized AmneziaWG share-URI scheme in the upstream
ecosystem (the reference client uses `.conf` files and QR-of-`.conf`).
Define one locally and document it. Structure: scheme + base64url-
encoded AWG config fragment (or query-param layout). Pick the simpler
format. Share-sheet registration extends the filter list from the
subscription/QR epics.

## Acceptance criteria

- [ ] Format documented in `docs/` with rationale and example:
    likely `amneziawg://base64url-encoded-conf` or
    `amneziawg://host:port?<params>`.
- [ ] Codec: `AmneziaWGBean → URI` and `URI → AmneziaWGBean` round-trip
    losslessly; unit-tested.
- [ ] Share-sheet filter registered in AndroidManifest so the app
    appears as a handler when users tap `amneziawg://…` links.
- [ ] QR scanner recognizes the scheme and dispatches to profile-edit.
- [ ] Clipboard-import menu recognizes the scheme.
- [ ] Profile-detail "Share" action emits both `amneziawg://` URI and
    a QR code containing it (alongside the existing `.conf` share).
- [ ] Secrets-in-URI warning is shown once before sharing, same
    pattern as standard profile share.

## Source references

**No direct upstream analog.** Neither amneziawg-android nor amneziawg-go defines a URI scheme; sharing is `.conf`-file or QR-of-`.conf` only. RIPDPI invents `amneziawg://` for ergonomic single-profile sharing.

**Pattern references** (all NekoBox paths rooted at `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- NekoBoxForAndroid `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaFmt.kt` — `hysteria2://` URI codec is a good template (UDP-based protocol with query-param auxiliary fields). Follow the same shape: `amneziawg://<base64-private-key>@<host>:<port>?public_key=...&allowed_ips=...&jc=...&h1=...&s1=...`.
- NekoBoxForAndroid `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/WireGuardFmt.kt` — the WG-URI codec (`wireguard://`) shows how to serialize a WG-shaped profile. Extend with AWG query params.

**Reference URI layout** (proposed, documented in `docs/`):
```
amneziawg://<base64url(private-key)>@<host>:<port>
?public_key=<base64url>
&allowed_ips=<cidr,cidr>
&mtu=<n>
&preshared_key=<base64url>
&jc=4&jmin=40&jmax=70
&s1=0&s2=0&s3=0&s4=0
&h1=<hex>&h2=<hex>&h3=<hex>&h4=<hex>
&i1=<hex>&i2=<hex>&i3=<hex>&i4=<hex>&i5=<hex>
#<name>
```

**Adapt:** Hysteria2-style URI shape from NekoBox. **Invent:** All AWG-specific query-param names (this task defines them).

## Links

- [[Epic - AmneziaWG outbound support]]
- [[Add share-sheet handler for proxy URI schemes]]
- [[Add QR scanner screen with CameraX and ML Kit]]
