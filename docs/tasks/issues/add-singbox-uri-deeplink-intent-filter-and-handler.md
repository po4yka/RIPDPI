---
title: Add sing-box URI deep-link Intent filter and handler
type: task
status: done
area: android
priority: critical
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [x] #task Add sing-box URI deep-link Intent filter and handler #repo/RIPDPI #area/android #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-singbox-uri-deeplink-intent-filter-and-handler`
- **Verify:** `./gradlew :app:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Register the Android Intent filter + handler for the
`singbox://import-remote-profile?url=…` deep-link the deployer's
recipient page hands to users, plus the equivalent `ripdpi://` and
`sn://` shapes. The handler routes to the **subscription-add** flow
(not the single-profile relay editor), pre-populates the URL and
name, and lets the user confirm before any network request.

## Context

### What the deployer hands out

`ripdpi-vpn-deploy/vpnd/templates/recipient.html:65` renders a
one-tap button:

```html
<a class="btn" href="{{ singbox_deeplink }}">Open in sing-box</a>
```

`vpnd/src/pages/recipient.rs:23-31` builds `singbox_deeplink` as
`singbox://import-remote-profile?url=<urlencoded sub URL>&name=<urlencoded client name>`
— the de-facto convention shared by sing-box-for-Android and
NekoBox. Any sing-box-family client the user already has installed
picks it up from the chooser.

### The gap

The base share-sheet task
([[Add share-sheet handler for proxy URI schemes]]) declares filters
for **single-profile** schemes (`vless://`, `vmess://`, `trojan://`,
`ss://`, `hysteria://`, `hysteria2://`, `tuic://`, `anytls://`,
`ssh://`) — but **not** `singbox://`, and not the
subscription-import shape. Subscription deep-links are a different
dispatch target: the subscription list, not the relay editor.

### Required behaviour

- Manifest filters for schemes `singbox`, `ripdpi`, `sn` with host
  `import-remote-profile`.
- A handler activity that parses `url=` (required) + `name=`
  (optional), URL-decodes both, and navigates to the
  subscription-add screen with both fields pre-filled.
- If the `url=` path is `/bootstrap/...`, the screen flips to the
  bootstrap shape
  ([[Add bootstrap one-time subscription token import flow]]).
- Filter priority **below** sing-box-for-Android's, so SFA stays the
  user's default if installed; RIPDPI is offered in the chooser.
  A settings entry explains how to set RIPDPI as default — no
  programmatic preferred-app grab.

## TDD workflow

Implement strictly test-first per the epic TDD policy.

1. **Red** — author these and confirm each fails before
   implementation:
   - `app/src/test/kotlin/.../DeepLinkParserTest.kt` — pure parser:
     valid `singbox://`, `ripdpi://`, `sn://` deep-links →
     `(url, name)`; missing `url=` → typed error; non-UTF8 / bad
     percent-encoding → typed error; `/bootstrap/` path flagged.
     *Fails: no parser.*
   - `app/src/androidTest/kotlin/.../DeepLinkIntentTest.kt` —
     launches a `singbox://import-remote-profile?url=…&name=…`
     Intent; asserts the subscription-add screen opens with both
     fields populated. *Fails: no Intent filter / no handler.*
   - same file, **bootstrap case** — deep-link whose `url=` path is
     `/bootstrap/<token>`; asserts the add screen is in bootstrap
     mode. *Fails: handler does not branch.*
   - same file, **malformed case** — missing `url=`; asserts a
     typed error toast and **no crash**, screen does not open.
     *Fails: NPE / crash.*
   - `app/src/androidTest/kotlin/.../DeepLinkManifestPriorityTest.kt`
     — resolves the Intent and asserts RIPDPI's filter priority is
     strictly below the documented SFA value. *Fails: default
     priority claims it.*
2. **Confirm failures** — record observed messages in the Work log.
3. **Green** — add the manifest filters, the handler activity, the
   parser, the bootstrap branch, the settings entry — minimal to
   pass.
4. **Refactor** — share the URI codec with
   [[Add share-sheet handler for proxy URI schemes]] rather than
   duplicating; re-run, stay green.
5. **Verify** — run `## Completion criteria` commands + the manual
   walkthrough; attach output.

## Acceptance criteria

- [ ] `AndroidManifest.xml` adds Intent filters for schemes
    `singbox`, `ripdpi`, `sn`, host `import-remote-profile`, with
    `action.VIEW` + `category.DEFAULT` + `category.BROWSABLE`.
- [ ] Filter priority is **strictly lower** than
    sing-box-for-Android's; verified by an instrumented resolution
    test, not by inspection.
- [ ] Settings exposes a "Make RIPDPI the default subscription
    handler" entry that explains the chooser and links to the
    system "Open by default" screen; no programmatic preferred-app
    hack.
- [ ] Handler parses `url=` (required) + `name=` (optional),
    URL-decodes both, rejects empty `url=` with a typed error.
- [ ] Handler routes to the subscription-add screen with URL + name
    pre-populated; the user confirms before any network request.
- [ ] If the `url=` path is `/bootstrap/...`, the screen opens in
    bootstrap mode.
- [ ] A sing-box JSON resource at the URL is handled by
    [[Add sing-box JSON subscription parser]].
- [ ] Malformed deep-link (missing `url`, bad encoding, unsupported
    path) → typed error toast, no crash, screen does not open.

## Test plan

| Layer | File | Cases |
|---|---|---|
| Kotlin unit | `DeepLinkParserTest.kt` | 3 valid schemes; missing `url`; bad percent-encoding; `/bootstrap/` flag; extra unknown params ignored |
| Instrumented | `DeepLinkIntentTest.kt` | populated add screen; bootstrap-mode branch; malformed → toast, no crash |
| Instrumented | `DeepLinkManifestPriorityTest.kt` | RIPDPI priority < SFA; chooser includes RIPDPI |

## Completion criteria

`#status/done` only when **every** item holds, with evidence in the
`## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All five test files exist, written **before** implementation
    (red-then-green confirmed in the Work log), and pass.
- [ ] `./gradlew :app:testDebugUnitTest` green — output attached.
- [ ] `./gradlew :app:connectedDebugAndroidTest` green on an
    emulator — output attached.
- [ ] `./gradlew lintDebug` clean; any new string key present in
    all 7 locale files.
- [ ] **Manual walkthrough on a clean install**, recorded in the
    Work log: tap the recipient-page button → RIPDPI appears in the
    chooser → tap it → subscription-add screen populated → "Add" →
    profiles land.
- [ ] **Manual walkthrough with SFA also installed**: chooser shows
    both; the default is the user's pick, not silently grabbed.
- [ ] Reviewed by a separate `code-reviewer` pass.
- [ ] `## Work log` added: changed files, test output, manual-test
    notes, residual risk.

## Source references

- Deployer recipient template (deep-link button):
  `ripdpi-vpn-deploy/vpnd/templates/recipient.html:65`
- Deployer recipient renderer:
  `ripdpi-vpn-deploy/vpnd/src/pages/recipient.rs:23-31`
- Base share-sheet task (manifest pattern + URI codec to reuse):
  [[Add share-sheet handler for proxy URI schemes]]
- Convention: `singbox://import-remote-profile?url=<enc>&name=<enc>`

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Add share-sheet handler for proxy URI schemes]]
- [[Add bootstrap one-time subscription token import flow]]
- [[Add sing-box JSON subscription parser]]
