---
id: UIX-1795677614058050
title: Rename imported servers by their name, not their internal ID
kind: bug
status: review
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-26
updated: 2026-11-26
spec_reason: regression-tested-single-module
---

## Goal

Renaming a link or subscription server changes the name the user sees, starts from that name, and works for servers inside a group.

## Context

Found while integrating UIX-1795615862353491 (server name instead of ID). The Servers sheet shows a link or subscription server by its group member name, but `RenameServerDialog` in `ServerListItems.kt` prefills `server.id`, the generated UUID. `ServerListViewModel.rename` calls `renameRelay`, which changes the relay profile ID and not the display name. That ID belongs to a proxy group member, so `requireRelayUnreferenced` rejects the rename with `RelayProfileInUseException`. The user sees a UUID in the field and then an error.

## Acceptance criteria

- The rename dialog for a link or subscription server opens with the name the list shows, never the internal ID; a test covers it.
- Renaming such a server changes the name shown in the Servers sheet and the Connection card and keeps the server in its group; a test covers it.
- Renaming an own server and an AmneziaWG server works as before.

## Ownership

One writer in a dedicated worktree owns the rename path in `app` (`ServerListItems.kt`, `ServerListViewModel.kt`) and the group member name update in `core/data`, with their tests.

## Evidence

- Cause: the rename dialog prefilled `server.id`, and the relay rename changed the relay profile ID, which a proxy group member holds, so `requireRelayUnreferenced` rejected it.
- Fix: relay rows prefill the dialog with `RelayProfileUiState.name`, the name the list shows. `ProxyGroupRepository.renameMember` sets the member `displayName` in every group that holds the ID and keeps the ID; `ServerListViewModel.rename` uses it and keeps `renameRelay` for an own server that no group holds. `ProxyProfile.withIdentity` moved from the subscription refresh code to `core/data/runtime-state` so both use one helper. AmneziaWG rename is unchanged.
- Tests: `ConnectionScreenTest` long-press test asserts the dialog opens with the shown name (it failed before the fix with the ID in the field); `ProxyGroupRepositoryTest` asserts a rename changes only the member name, keeps its ID and group, and returns false for an ID no group holds.
- Gates: `./gradlew :app:verifyRoborazziGithubDebug :core:data:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :app:lintGithubDebug :core:data:runtime-state:detekt` pass; `just ci-gates` exit 0. `:app:detekt` reports 18 findings, all in files this change does not touch (for example `ConnectionScreenTest` was already 858 lines on main, over `LargeClass`).
- Limit: a subscription refresh re-applies the provider's member name (`remapMemberIds` matches by connection or endpoint and keeps the refreshed name), so a renamed subscription server returns to the provider name after the next refresh. Link-imported servers are not refreshed.
