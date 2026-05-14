---
title: Define policy bundle profile schema
type: task
status: backlog
area: vpn
priority: critical
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Define policy bundle profile schema #repo/RIPDPI #area/vpn #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `define-policy-bundle-profile-schema`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/data/model/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define RIPDPI's internal full-device policy profile schema so URI strings become import/export formats, not the app's runtime source of truth.

## Motivation

Existing clients often lose routing, DNS, IPv6, selector, kill-switch, and credential lifecycle policy because subscriptions only carry transport URIs. RIPDPI needs one typed model that can render transport configs and drive Android policy consistently.

## Scope

- In scope: schema fields for device ID, profile version, outbounds, selector/urltest, routing, DNS, IPv6, kill-switch, subscription state, expiry, and redaction metadata.
- Out of scope: public server-side delivery API and payment/subscription business logic.

## Acceptance criteria

- [ ] `DeviceProfile` or equivalent typed model represents transport profiles plus policy, not just `vless://` / `hy2://` strings.
- [ ] VLESS/REALITY, XHTTP/HTTPS, and Hysteria2 initial outbound shapes can be represented without raw JSON.
- [ ] Secrets are represented through redacted/secret wrapper types and never through default `toString()`.
- [ ] Schema has explicit `profile_version`, `expires_at`, and migration hooks.
- [ ] Renderers can derive Xray/sing-box-style config fragments from the typed model without losing policy information.

## Design notes

Keep this schema separate from direct-mode strategy packs. It describes the user device VPN profile, while strategy packs describe network-path compatibility decisions and rule catalogs.

## Risks / open questions

- Decide whether imported third-party subscription profiles become lossy typed records or preserve a redacted raw extension block for unsupported fields.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Render validated Xray client configs]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
