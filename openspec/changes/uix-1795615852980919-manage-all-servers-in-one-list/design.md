## Context

Portfolio task `UIX-1795615852980919` owns this change. The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## Goals / Non-Goals

- Goal: deliver `Manage all servers in one list` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

### Terms

- "Server" names every saved relay profile and every saved AmneziaWG profile. The Connection sheet section is "Servers", its button is "Add server", its search is "Search all servers", the dedicated editors are "AmneziaWG server", "AnyTLS server", "Mieru server", and "SSH server", and the share action is "Share server".
- "Preset" names the DPI settings: "Recommended preset" and "Manual preset". The sheet that holds both is "Presets and servers".
- The Profiles utility screen, which checks and selects subscription servers, is "Server checks".
- Other UI sessions use the same two terms. Older strings that still say "profile" for a server (share sheets, import confirmations, subscription text) are outside this change; UIX-1795661374629256 owns them.

### Add menu

- The Add server menu lists `relayKindLabels()` (a public projection of `RelayKindDescriptors` in `:core:service`) filtered to kinds that have an editor: the dedicated AnyTLS, Mieru, and SSH editors, or a Mode Editor chip. A new descriptor appears in the menu once it gets an editor; no list is kept by hand. Snell and Off have no editor and stay out.
- Labels come from the Mode Editor chip (localized where the chip has a string resource), then the descriptor label.
- A Mode Editor kind opens the editor on a `new-relay:<kind>` session. Hydration builds a blank server of that kind with `applyRelayAfterImage` under a fresh id (`<kind>`, `<kind>-2`, ...), so its save cannot overwrite a saved profile.
- AmneziaWG, "WireGuard / AmneziaWG from file", and Subscription stay in the menu.
- `RipDpiContextMenu` now scrolls, because the menu holds about 20 entries.

### One list

- Saved AmneziaWG servers render in the Servers card under the relay servers. `SavedAwgProfilesViewModel` stays the one owner of that list; the AWG section moves out of the Profiles utility screen.
- Relay rows keep Select; AWG rows open their editor, where Connect lives.

### Actions

- Every server row has an Actions button and a long-press with the same menu: Rename, Duplicate, Share, Delete. A row with no editor opens the menu on tap.
- Relay delete and rename are new journaled profile mutations (`relay_delete`, `relay_rename`) behind a separate `RelayCatalogMutationCoordinator`, so the existing coordinator fakes stay unchanged. Both refuse a profile that a chain or proxy group uses; delete also refuses the active relay. Rename follows the active selection. Replay after a crash is idempotent.
- The relay id is the server name, so rename changes the id. Duplicate saves `<id>-copy` without selecting it.
- AWG rename and duplicate use `AwgProfileRepository.save`; a duplicate is a local server, not subscription-owned. AWG delete refuses the running tunnel and subscription rows, as the AWG editor does.
- AWG servers have no Share action yet: there is no AmneziaWG `.conf` exporter for `AwgActivationRequest`, and one must carry the AWG 3.x obfuscation fields. The acceptance criterion was narrowed to rename, duplicate, and delete for AmneziaWG; UIX-1795661310559744 owns the exporter and the Share action.
- A refused or failed action shows a dismissible banner in the Servers card.

### Preset versus server

- A preset owns only the DPI settings (`directSettings`). Recommended now resets those fields and keeps the selected server, so selecting a server no longer flips the preset to Manual, and choosing Recommended no longer turns the relay off. The reset dialog says the server stays.
- The saved-connection card shows the server as its title and the preset as its subtitle.

### Sticky Relay view

- An import returns home with `navigateHome()`, which drops the Connection graph and its saved state. The Relay view therefore lives in `ConnectionPathMemory`, a `@Singleton` that outlives the graph. Plain `Route.Connection` opens the last viewed path; `ConnectionDirect` and `ConnectionRelay` still force their path. Process death resets it to Direct.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.
- `relay_delete` and `relay_rename` are new journal intent names. An older build that replays a journal with them cannot decode it; downgrades are not supported.
- Rename of a relay that a chain or group references is refused instead of rewriting references.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
