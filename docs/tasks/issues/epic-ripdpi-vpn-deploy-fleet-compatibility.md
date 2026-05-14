---
title: Epic - ripdpi-vpn-deploy fleet compatibility
type: epic
status: done
area: epic
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [x] #task Epic - ripdpi-vpn-deploy fleet compatibility #repo/RIPDPI #area/epic #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-ripdpi-vpn-deploy-fleet-compatibility`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** `add-proxygroup-and-subscription-entities-to-ripdpi-data-layer`, `add-share-sheet-handler-for-proxy-uri-schemes`, `add-sing-box-json-subscription-parser`, `fork-boringtun-and-add-amneziawg-handshake-obfuscation`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Make the RIPDPI Android client a **one-tap consumer of every artifact**
emitted by the sibling deployer `ripdpi-vpn-deploy`
(`/Users/npochaev/GitHub/ripdpi-vpn-deploy/`). A user provisioned by an
operator running `make issue-sub-token`, `make issue-bootstrap`,
`make emit-qr`, or `vpnd share` must reach a working tunnel **without
typing a single relay field by hand**, across all four transport
profiles and every multi-cohort failover bundle the deployer produces:

| Deployer profile | Wire shape | Client gap today |
|---|---|---|
| **P0** VLESS + REALITY + `xtls-rprx-vision`, TCP/443 | `vless` + `reality` + `tcp` | Protocol OK; only manual entry works (no sub import) |
| **P1** VLESS + xHTTP + plain TLS 1.2/1.3, nginx-fronted TCP/8443 | `vless` + `tls` + `xhttp`, **no Reality** | xHTTP is welded to the Reality kind — shape unrepresentable |
| **P2a** Hysteria2, UDP/443, optional Salamander + port-hopping | `hysteria2` | Protocol OK; port-hop window owned by a feeder task |
| **P2b** AmneziaWG cohorts, UDP, `Jc/Jmin/Jmax/S1/S2/H1..H4` | `amneziawg` standalone | Only exists as a WARP packet codec, not a relay kind |

Plus the bundle-level structure the deployer always emits: a
`selector` + `urltest` outbound pair for auto-failover, Android
`route.rules` carrying `package_name` per-app policy, and the
delivery envelope (long-lived `/sub/<token>`, one-time
`/bootstrap/<token>`, QR, and the `singbox://` recipient-page
deep-link).

## Why now

The deployer ships **today**. The protocol-level wire-compatibility
audit (2026-05-14, see the three feeder epics below) found the client
and deployer aligned at the *protocol* layer but **misaligned at the
provisioning layer**: the only path that works end-to-end is "retype
every field into the relay editor", which defeats the deployer's
entire design. Seven concrete, independently shippable gaps block
real onboarding — each is now a child task of this epic. Until they
land, `ripdpi-vpn-deploy` cannot be recommended to non-technical
users on Android.

## Key decisions

- **Integration epic, not a re-implementation.** Every existing
  feeder task keeps its current parent epic. This epic owns *only*
  the deployer-specific glue and the regression suite that proves
  the full bundle round-trips. Re-parenting feeder tasks is
  explicitly out of scope.
- **Three independent onboarding entry points, all mandatory.**
  (1) the `singbox://` recipient-page deep-link, (2) the QR code,
  (3) pasting a bare `/sub/<token>` or `/bootstrap/<token>` URL.
  No path may gate on another — losing camera permission must not
  block the deep-link, a missing browser must not block the QR.
- **Bootstrap tokens are first-class one-shots,** not "subscriptions
  that fail on the second poll". Schema, retry policy, and audit
  log distinguish them so the client never re-fetches a consumed
  bootstrap URL and never alerts as if it were a recoverable error.
- **Server-coordinated obfuscation params are immutable on the
  client.** AWG cohort presets ship as a **read-only data catalog**
  (`core/data/assets/awg-cohorts.json`), hot-updatable via app
  update, with the exact values from
  `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`. Users pick a preset;
  they do not edit its numbers.
- **xHTTP is a transport, not a kind.** Decouple it from
  `vless_reality` so a `vless` profile with `transport=xhttp` +
  `security=tls` (no Reality fields) is a first-class shape — the
  deployer's P1.
- **Per-app routing rides the subscription bundle.** Importing a
  sing-box sub merges its `route.rules` into the device's per-app
  routing store; conflicts with user-set rules are surfaced in a
  confirm dialog, **never silently merged**.
- **Selector + urltest become a single ProxyGroup at import.** The
  runtime side already exists; this epic wires only the importer.
- **Test-first across the whole epic.** Every child task is
  implemented under the repo TDD policy (see `## TDD policy`
  below). The epic is gated by a golden-file suite of literal
  `emit-singbox.sh` output — a deployer schema change must make a
  client test go red, never be silently absorbed by a lenient
  parser.

## Scope

- **In scope (7 new child tasks):**
  - Decouple VLESS xHTTP transport from the Reality relay kind
  - Bootstrap one-time token import flow (issue → consume-once →
    persist → never retry)
  - `singbox://` / `ripdpi://` / `sn://` deep-link Intent filter +
    handler
  - Sing-box `route.rules` Android `package_name` import → per-app
    routing store
  - Sing-box `selector` + `urltest` outbound import → ProxyGroup +
    failover policy
  - AmneziaWG RU-ISP cohort preset catalog (read-only data asset)
  - Golden-file fleet compatibility test suite against
    `emit-singbox.sh`
- **Out of scope:**
  - Server-side changes to `ripdpi-vpn-deploy` (that repo's backlog)
  - Scraping the recipient HTML page — users hit the deep-link, QR,
    or sub URL directly
  - Implementing the deployer's server-side audit log on the client
  - Multi-tenant subscription tokens (deployer model is one client
    per token; that invariant is preserved)
  - Hysteria2 randomized hop window — owned by
    [[Add randomized port-hopping window to Hysteria2 outbound]]
  - WebSocket / gRPC VLESS transports — not emitted by the deployer

## TDD policy

This epic and **every child task** follow the repo TDD discipline
(`<tdd-mode>` is in force):

1. **Red first.** No production code is written before a failing
   test exists that pins the behaviour. Each child task's
   `## TDD workflow` section names the exact test files to author
   first and the failure each must show.
2. **Confirm the failure is correct.** A test that fails to compile,
   or fails for an unrelated reason, does not count as "red". The
   Work log must record the *observed* failure message.
3. **Minimal green.** Implement the smallest change that makes the
   red test pass. Do not add unrequested abstraction.
4. **Refactor under green.** Clean up only with the suite passing.
5. **Layered coverage** — every child task carries tests at the
   layers it touches:
   - **Rust unit / vector tests** — `cargo test -p <crate>` for
     `native/rust/crates/*` changes; reference vectors where a wire
     format is involved.
   - **Kotlin unit tests** — `./gradlew :<module>:testDebugUnitTest`
     for parser / data-model / mapping logic.
   - **Instrumented tests** — `app/src/androidTest/**` for Intent
     filters, VpnService wiring, and anything needing a real
     `Context`.
   - **Golden-file tests** — the fleet suite
     ([[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]])
     is the epic-level integration gate.
6. **No-secret-logging tests are non-negotiable.** Any task touching
   tokens, UUIDs, shortIds, passwords, or keys extends the existing
   redaction harness; a redaction test ships in the same change.
7. **CI gate.** The fleet golden-file suite runs on every PR that
   touches the subscription parser, relay model, routing model, or
   AWG model. A red suite blocks merge.

## Ship definition

- [ ] User taps `singbox://import-remote-profile?url=...` from the
    deployer's recipient page on a device with RIPDPI installed and
    lands on a populated subscription-add screen.
- [ ] User scans the QR from `make emit-qr CLIENT=phone` and lands
    on the same populated screen.
- [ ] User pastes `https://<host>/sub/<token>` and the resulting
    ProxyGroup contains exactly one profile per
    `(host, cohort, transport)` tuple in the deployer's bundle.
- [ ] One-time bootstrap URL is consumed exactly once: success →
    profiles persist + URL marked spent; HTTP 410 → "already used",
    no retry.
- [ ] P1 cohorts load as a `vless` profile with `transport=xhttp`,
    `security=tls`, no Reality fields, `uTLS=chrome`.
- [ ] P2b AmneziaWG cohorts load as standalone `amneziawg` profiles
    with a cohort preset applied; switching preset in the editor
    updates the profile.
- [ ] Sing-box `selector` + `urltest` groups become one ProxyGroup
    with `urltest` driving the failover state machine.
- [ ] `route.rules` with `package_name` arrays merge into per-app
    routing; conflicts prompt the user.
- [ ] Subscription URL, bootstrap token, per-client UUIDs / shortIds
    / passwords / WG keys never appear in logcat, crash reports, or
    the diagnostics export bundle.
- [ ] Golden-file suite runs every `emit-singbox.sh` output variant
    and asserts byte-stable import → save → re-export.

## Epic completion criteria

The epic is `#status/done` only when **all** of the following hold:

- [ ] All 7 new child tasks are `#status/done` (their own
    `## Completion criteria` gates satisfied, files deleted per
    lifecycle rule).
- [ ] All 19 feeder tasks this epic depends on are `#status/done`
    OR explicitly waived in this note with a written rationale.
- [ ] Every "Ship definition" checkbox above is checked, each with
    a manual-test evidence line (device, build, observed result).
- [ ] The fleet golden-file suite
    ([[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]])
    is green in CI and gates merges.
- [ ] A clean-install end-to-end walkthrough is recorded for all
    three onboarding entry points (deep-link, QR, pasted URL)
    against a live deployer instance, for at least P0, P1, P2a,
    and P2b each.
- [ ] The deployer team has confirmed (cross-repo issue link) that
    `ripdpi-vpn-deploy` releases are now gated on this client
    suite.
- [ ] A separate reviewer pass (`code-reviewer` / `verifier`, not
    self-approval) has signed off on the integration surface.
- [ ] `docs/tasks/board.md` and the deployer's `docs/` cross-link
    each other for the fleet contract.

## Child tasks

**New (this epic owns)**

- [[Decouple VLESS xHTTP transport from the Reality relay kind]]
- [[Add bootstrap one-time subscription token import flow]]
- [[Add sing-box URI deep-link Intent filter and handler]]
- [[Add sing-box route.rules Android per-app routing import]]
- [[Add sing-box selector and urltest group import from subscription]]
- [[Add AmneziaWG Russian ISP cohort preset catalog]]
- [[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]]

**Feeder tasks (other epics, depended on)**

Subscription plumbing — from [[Epic - NekoBox subscription and profile import]]:

- [[Add sing-box JSON subscription parser]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
- [[Add subscription auto-update WorkManager worker]]
- [[Add force-resolve DNS and Subscription-Userinfo handling]]
- [[Add duplicate-profile detection on subscription merge]]
- [[Add selector outbound runtime for group-based profile switching]]
- [[Add per-device subscription token UX and shared-link warnings]]
- [[Add multi-delivery subscription mirror support]]

AmneziaWG outbound — from [[Epic - AmneziaWG outbound support]]:

- [[Fork boringtun and add AmneziaWG handshake obfuscation]]
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]
- [[Add AmneziaWG profile editor screen with obfuscation fields]]
- [[Add amneziawg URI codec for profile share and import]]
- [[Wire AmneziaWG into the subscription WireGuard-INI parser]]
- [[Add strategy-pack compatibility hints for AmneziaWG servers]]

Single-profile import — from [[Epic - QR code and clipboard profile import]]:

- [[Add QR scanner screen with CameraX and ML Kit]]
- [[Add share-sheet handler for proxy URI schemes]]
- [[Add clipboard-import menu action with explicit user consent]]

Transport — from [[Epic - Composable transport layer parity]]:

- [[Add randomized port-hopping window to Hysteria2 outbound]]

Runtime failover:

- [[Add priority-based outbound failover state machine]]

Testing — from [[Epic - VPN fleet testing matrix and release gates]]:

- [[Add client compatibility regression matrix for fleet profiles]]

## Dependencies

- **Hard-blocked by** [[Add sing-box JSON subscription parser]] — the
  parser is the spine of every selector/urltest, route-rules, and
  per-cohort import path here.
- **Hard-blocked by** [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
  — the data model must exist before deployer-specific glue lands.
- **Hard-blocked by** [[Fork boringtun and add AmneziaWG handshake obfuscation]]
  — without a standalone AWG outbound the P2b cohorts cannot run.
- **Soft-blocked by** [[Add share-sheet handler for proxy URI schemes]]
  — the singbox-deeplink task extends that handler's manifest block;
  land the base first to avoid manifest churn.
- **Feeds** [[Epic - VPN fleet testing matrix and release gates]] —
  the golden-file suite is the deployer-side input matrix for the
  wider fleet gate.

## Sibling repo coordination

- Bundle generator: `ripdpi-vpn-deploy/scripts/emit-singbox.sh`
  (405 lines as of 2026-05-14)
- QR generator: `ripdpi-vpn-deploy/scripts/emit-qr.sh`
- Per-cohort obfuscation profiles:
  `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`,
  `ripdpi-vpn-deploy/ansible/roles/amneziawg/vars/cohorts/`
- Subscription endpoint:
  `ripdpi-vpn-deploy/ansible/roles/subscription-host/`
- Recipient page: `ripdpi-vpn-deploy/vpnd/templates/recipient.html`
  + `ripdpi-vpn-deploy/vpnd/src/pages/recipient.rs`
- Android-aware notes (split-tunnel localhost leak, SOCKS5 exposure,
  NaiveProxy padding leak): `ripdpi-vpn-deploy/docs/CLIENT-NOTES.md`

When the deployer adds a cohort, transport, or bundle field, the
matching client task gates the deployer release. Cross-link issue
slugs across repos in commit messages.

## Risks / open questions

- **Bundle drift.** `emit-singbox.sh` evolves frequently. The
  golden-file suite must fail on byte mismatch; a "lenient" parser
  that masks schema drift is explicitly disallowed.
- **xHTTP plain-TLS as kind vs. transport.** Decoupling may regress
  existing P0 (Reality + xHTTP) profiles. Mitigation: a one-shot
  ProfileStore migration with an audit entry; covered by a
  migration test.
- **Per-app routing conflicts.** The user may have manually excluded
  packages the deployer wants tunnelled. Default: "ask on first
  conflict, remember the choice"; never silently overwrite.
- **AWG cohort preset accuracy.** Cohort numbers drift as RU ISPs
  retune classifiers. Catalog is data (`awg-cohorts.json`), not
  code; a CI diff against `docs/AWG-COHORTS.md` catches drift.
- **Bootstrap retry storm.** A double-tap before the first request
  lands burns the token. Mitigation: a local mutex keyed on the
  token hash; covered by a concurrency test.
- **`singbox://` scheme collision** with sing-box-for-Android.
  Register at non-default priority; expose a "set as default"
  settings entry; never grab the preference programmatically.
- **Recipient-page scheme evolution.** Accept any of
  `{singbox, ripdpi, sn}://import-remote-profile?url=…` so a
  deployer scheme switch does not require an app update.

## Links

- [[ripdpi-android]]
- [[Epic - NekoBox subscription and profile import]]
- [[Epic - AmneziaWG outbound support]]
- [[Epic - QR code and clipboard profile import]]
- [[Epic - VPN fleet testing matrix and release gates]]
- Sibling repo: `/Users/npochaev/GitHub/ripdpi-vpn-deploy/`
- Child issues: 7 new + 19 feeder
