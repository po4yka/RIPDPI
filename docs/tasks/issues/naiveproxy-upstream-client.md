---
id: RLY-1793363326590556
title: Ship the pinned upstream NaiveProxy client for real-certificate profiles
kind: feature
status: doing
area: relay
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: rly-1793363326590556-naiveproxy-upstream-client
created: 2026-10-30
updated: 2026-10-30
---

## Goal

Pin-free NaïveProxy profiles run the pinned upstream Chromium-based client (v154.0.8037.49-5), so their TLS and HTTP/2 behavior on the wire matches upstream. Pinned profiles keep using the RIPDPI Rust helper.

## Acceptance criteria

- The upstream executable is staged for every ABI only from the sha256-pinned release.
- Credentials never appear in argv or logs. The proxy host resolves through protected DoH and fails closed.
- The `android-naiveproxy` emulator scenario passes for both pin-free and pinned profiles.
- The wire comparison shows no HelloRetryRequest and the upstream JA4 for a pin-free profile.
