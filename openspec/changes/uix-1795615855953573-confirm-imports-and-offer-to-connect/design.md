## Context

Portfolio task `UIX-1795615855953573` owns this change. After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

The 2026-11-25 audit found four gaps: Add on a link import returned to Status with no confirmation; an empty clipboard ended the Paste link flow; the subscription confirm step showed `Unnamed subscription` and no server count; and a failed subscription fetch showed a generic profile error.

## Goals / Non-Goals

- Goal: deliver `Confirm imports and offer to connect to the new server` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.
- Non-goal: Select and Connect for subscription servers. A subscription group's members are selected through the selector group store, which has no single-member UI action yet; `UIX-1795615852980919` (one server list) owns server selection. The subscription flow shows the name and server count before Add and the result after it.

## Decisions

- Single-profile imports (link, QR code, and a file that holds a link all reach `ProfileImportConfirm`) stay on the confirm screen after Add. The card title becomes `Added: <name>`, and the screen offers Connect (primary) and Select. Back after the save returns to Status and leaves the profile saved and unselected, as before.
- Select reuses `ConnectionRelayProfileSelector`, the same code the Connection server list uses, through the `ImportedServerSelection` seam. It runs in the application scope, so a reconnect of a running service survives the screen closing. `select()` now returns its job, so the import screen can wait for the selection before it leaves.
- Connect selects the profile, opens Status, and calls `MainViewModel.onStartRequested()`. That path goes through the normal permission and VPN-consent flow. It starts the configured mode only when nothing runs; a running service already follows the new selection through the selector's reconnect.
- An empty clipboard opens a dialog with a link field and Import instead of a dead end. The typed link goes through the same `ClipboardImportClassifier`; the clipboard is not read again and is never cleared for a typed link. The field uses plain `remember`, not saved state, so the credential-bearing link is not written to disk.
- The subscription confirm step gets a Check action before Add for an ordinary link. Check calls `SubscriptionRefreshCoordinator.preview`, which fetches and parses without saving, and the card then shows the server title and server count. Add saves exactly that fetch through `install`, without a second request. The network request still waits for an explicit user action, as before this change.
- A bootstrap link is not checked, because its first fetch spends the one-time token; Add fetches it as before. A local bundle has no URL and needs no check.
- After a failed Check, Add stays available and saves the link the old way (a retryable failure keeps the group for auto-update), so a temporarily unreachable server can still be added.
- `Unnamed subscription` is removed. The card names a subscription by the user's name, then the server title, then the link host, which is the name Add saves.
- Failure text names the cause and uses the word subscription: a refused payload keeps its specific entry or schema text; an HTTP refusal shows the status code; otherwise the typed `SubscriptionRefreshFailure` maps to network, format, size, expiry, or invalid-link text. `RefreshOutcome.Failure`, `SubscriptionRefreshResult.Failed`, `SubscriptionPreviewResult.Failed`, and the `core/data` `BootstrapConsumeResult.NetworkError` carry the HTTP status. The status is not persisted.
- New strings live in `strings_settings.xml` in all ten locales. Connect and Select reuse `vpn_connect` and `profile_variants_cta_select`.

## Risks / Trade-offs

- Check adds one tap before Add for ordinary links. → It replaces a blind Add with a visible result, and Add after a failed Check keeps the old behavior.
- A profile import no longer shows the `Added` toast; the confirm screen shows it instead. → The screen stays until the user picks Connect, Select, or Back.
- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
