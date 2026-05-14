---
title: Epic - Subscription and profile import
type: epic
status: done
area: outbound
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Epic - Subscription and profile import #repo/RIPDPI #area/outbound #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-subscription-profile-import`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Let users load and keep fresh their proxy nodes from standard subscription-based network
subscription providers, the same way reference implementation does. Today RIPDPI only ships
built-in operator presets and ad-hoc user relays; there is no way to paste a
subscription URL and get a populated group with periodic refresh.

## Why now

Subscription management is the single largest feature RIPDPI lacks compared to
the reference implementation feature surface. Without it, users of third-party providers cannot
adopt the app without manual per-node entry. This is the gating item for
real-world adoption.

## Key decisions

- **Keep the Rust engine; only add parsing/transport layers.** No sing-box
runtime swap.
- **Support the subscription formats reference implementation parses,** not a broader set:
Clash/Clash.Meta YAML, sing-box JSON outbound array, WireGuard INI,
base64 URI list, plain URI list.
- **Parse in Kotlin, not Rust,** for iteration speed and to keep the Rust
engine focused on the runtime fast path.
- **Refresh via WorkManager with min 15-min cadence,** matching reference implementation.
- **Redact secrets on every log and diagnostic surface from day one.**
- **Preserve per-profile custom overrides** (`customOutboundJson`,
`customConfigJson`) across subscription merges so user tweaks survive.

## Scope

- **In scope:** ProxyGroup/SubscriptionBean entities, five subscription
parsers, per-protocol URI codec for import/export, auto-update worker,
force-resolve DNS option, dedup, quota tracking from `Subscription-Userinfo`
header.
- **Out of scope:** Clash routing rules (parsers should ignore them), sing-box
inbound/route sections, V2rayN legacy share links beyond common vmess/vless,
proxy chaining (separate concern, not on roadmap).

## Ship definition

- [ ] User can paste a subscription URL in a group-edit screen and see the
    populated profile list within the same session.
- [ ] All five subscription formats parse without exceptions on a realistic
    sample bank.
- [ ] `Subscription-Userinfo` header (upload/download/total/expiry) is
    surfaced in the group detail screen.
- [ ] Auto-update fires via WorkManager at the group's configured cadence,
    gated by "update when connected only" when set.
- [ ] Duplicate profiles (byte-equal minus display name) are detected and
    merged on refresh without losing user-edited names.
- [ ] User-edited `customOutboundJson` / `customConfigJson` overrides survive
    subscription refresh.
- [ ] Subscription URLs, tokens, and server addresses never appear in logs,
    diagnostics exports, or crash reports.

## Child tasks

**Data model**
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
- [[Add duplicate-profile detection on subscription merge]]
- [[Add selector outbound runtime for group-based profile switching]]

**Parsers**
- [[Add Clash and Clash.Meta YAML subscription parser]]
- [[Add sing-box JSON subscription parser]]
- [[Add WireGuard INI subscription parser]]
- [[Add base64 and plain URI-list subscription parser]]

**Refresh and transport**
- [[Add subscription auto-update WorkManager worker]]
- [[Add force-resolve DNS and Subscription-Userinfo handling]]

## Dependencies

- Feeds: [[Epic - QR code and clipboard profile import]] (shares URI codec).
- Feeds: [[Epic - Advanced routing rules and geoip enforcement]] (groups may
expose selector outbound state that rule engine consumes).

## Risks / open questions

- Clash-format drift: Clash.Meta YAML keeps adding fields. Design parsers to
ignore unknown keys rather than hard-fail.
- Subscription-Userinfo trust: some providers lie. Display as informational;
never use for billing-style gating.
- Large subscriptions (500+ nodes): ensure parser is streaming, not loading
the whole YAML into memory.
- WireGuard INI multi-peer: pick one active peer per parse; surface the others
as separate profiles.

## Links

- [[ripdpi-android]]
- [[wikis/mobile-platform-enforcement/index|mobile-platform-enforcement]]
- Child issues: 9
