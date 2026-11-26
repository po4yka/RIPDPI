---
id: UIX-1795663496674824
title: Offer Select and Connect after importing a RIPDPI bundle file
kind: feature
status: doing
area: ui
priority: medium
owner: claude
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795663496674824-offer-connect-after-bundle-file-import
created: 2026-11-26
updated: 2026-11-26
---

## Goal

After the user imports a RIPDPI bundle file, the app names the imported servers and lets the user select one and connect to it in one step.

## Context

Split from `UIX-1795615855953573`. Link, QR code, and a file that holds a single link already open the profile import confirm screen, which shows `Added: <name>` with Connect and Select. A RIPDPI bundle file goes through the subscription confirm flow instead: it saves a SUBSCRIPTION group and returns to Status with no Select or Connect. Members of a subscription group are selected through the selector group store, which has no single-member action; the unified server list (`UIX-1795615852980919`) now owns server selection on `main`.

## Acceptance criteria

- After a RIPDPI bundle file import succeeds, the result screen lists the imported server names.
- The result screen offers Select and Connect for an imported server; Select makes it the active server through the same selection path as the server list, and Connect also starts the connection when nothing runs.
- A running service follows the new selection without a second start.
- New text uses the term server, and new strings exist in all ten locales.

## Evidence

- Cause: a bundle file import saved its SUBSCRIPTION group, then `completeImport` emitted the import event at once, so the screen returned to Status with no server named and no way to pick one.
- Fix: after a successful bundle import, `SubscriptionImportConfirmViewModel` finds the saved group with `localBundleTarget`, the rule the import itself used, and lists its members by `serverNames()`. Group members are saved as relay profiles under the member ID, so the screen calls `ImportedServerSelection`, which wraps `ConnectionRelayProfileSelector.select`, the call the server list's Select makes. `selectReturningStartNeeded` now holds the selection outcome for both confirm screens, so the single-link screen and the bundle screen share one path. Connect asks `MainViewModel.onStartRequested()` only for `Selected`; `AppliedToRunningService` returns Home without a second start. A keyless AmneziaWG profile from the same bundle still opens the key screen after Select, Connect, Done, or Back. AmneziaWG profiles are not listed, because the server list has no Select for them. Decision notes are in ADR 0016.
- Tests: `SubscriptionLocalBundleImportTest` adds four cases (servers listed by saved ID and Connect selects that ID; Connect on a running service asks for no start; a failed selection shows an error and stays; a bundle with a server and a keyless AmneziaWG profile keeps the key handoff and does not close). `SubscriptionImportConfirmScreenTest` asserts the `Added: <name>` card, both server names, and per-server Connect and Select. Screenshot `SubscriptionImportConfirmScreenshotTest.importedBundleServers` (light and dark) is new. These tests use `servers`, `select`, `selectedEvents`, and `onSelectServer`, which did not exist before the fix, so they could not pass on the old code. The local bundle card no longer shows an empty supporting line.
- Strings: no new string. The result uses the existing `import_done_added`, `vpn_connect`, `profile_variants_cta_select`, `relay_editor_activation_failed`, and `xray_import_done_action`, which exist in all ten locales.
- Limit: the screen title still reads "Add subscription" for a bundle file; renaming it belongs to the profile-strings task. Device and remote CI evidence were not collected.
