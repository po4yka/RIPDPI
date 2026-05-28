---
title: Add configurable asset provider picker with four presets
type: task
status: backlog
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add configurable asset provider picker with four presets #repo/RIPDPI #area/routing #status/backlog 🔼

## Summary

Surface an asset-provider picker that lets users choose the source of `geoip.db` / `geosite.db`, mirroring Reference implementation's four built-in presets: SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret.

## Context

Different regions have different "good" asset providers. Chocolate4U is tuned for Iran; antizapret is Russia-centric; SagerNet and soffchen are generalist. Picker is in Advanced Settings; updates are user-triggered via a button, not background fetch.

## Acceptance criteria

- [ ] Four built-in providers with labels, descriptions, and repository URLs (GitHub Releases).
- [ ] "Custom URL" option for a user-supplied GitHub-Releases-compatible provider.
- [ ] "Check for updates" button compares local version tag to latest release; downloads only if newer.
- [ ] Download uses the existing in-proxy HTTP client so the update works from inside a bypass tunnel.
- [ ] Imported DBs land in external files dir; runtime reload signal fires the geo-loader swap without restart.
- [ ] SAF import path for local `.db` files as a final fallback.
- [ ] Post-update, surface new version tag inline.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt` — the full provider picker + update-from-GitHub-Releases flow. Four built-in providers (SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret) listed here verbatim. **Port the provider list** and the "check for updates" logic (compares local tag file to GitHub Releases API `/latest` tag).
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `rulesProvider` preference.
- `app/src/main/res/xml/assets_preferences.xml` — the preference layout for reference.

**Provider URLs** (same four reference implementation ships):
- `https://github.com/SagerNet/sing-geoip` + `sing-geosite`
- `https://github.com/soffchen/sing-geoip` + `sing-geosite`
- `https://github.com/Chocolate4U/Iran-sing-box-rules`
- `https://github.com/L11R/antizapret-sing-box-geo`

**Adapt:** Provider list verbatim, GitHub Releases `/latest` tag comparison, SAF import path for custom files, swipe-delete + undo. **Skip:** Reference implementation's PreferenceFragment XML (use Compose).

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- Add geoip.db and geosite.db runtime loader and lookup (closed task)
