---
id: UIX-1795615852980919
title: Manage all servers in one list
kind: feature
status: doing
area: ui
priority: high
owner: server-list
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795615852980919-manage-all-servers-in-one-list
created: 2026-11-25
updated: 2026-11-25
---

## Goal

The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The Add profile menu lists only AnyTLS, Mieru, SSH, AmneziaWG, WireGuard/AmneziaWG from file, and Subscription. VLESS, VLESS Reality, Trojan, Shadowsocks, Hysteria2, TUIC, NaiveProxy, ShadowTLS, MASQUE, Tor, and chain relays are reachable only through Manual profile, RELAY section. Saved AmneziaWG profiles are missing from the Servers list and appear only on the Profiles utility screen. The word profile names both DPI presets and servers. Selecting a server silently switches the DPI preset from Recommended to Manual profile. The Relay node selection on the Connection diagram resets to Direct on return.

## Acceptance criteria

- The Add menu offers every relay kind that has an editor in `RelayKindDescriptors`, plus AmneziaWG.
- Saved AmneziaWG profiles appear in the same Servers list as relay profiles.
- Each server has rename, duplicate, share, and delete actions; long-press opens these actions.
- UI text uses one term for servers and a different term for DPI presets in all ten locales.
- Selecting a server does not change the DPI preset without telling the user.
- The Connection screen keeps the selected Relay view when the user returns to it.

## Ownership

One writer in a dedicated worktree owns server list, profile sheet, and Connection screens in `app`, server selection in `core/data`, strings, and baselines. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
