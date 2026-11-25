## Context

Portfolio task `UIX-1795615850526190` owns this change. A new user who has their own VPN server finds how to add it during onboarding and from the home and Connection screens.

## Goals / Non-Goals

- Goal: deliver `Add an own-server entry point to onboarding and home` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- One component owns the ways to add a server: `ServerAddActions` (`ui/screens/connection/ServerAddActions.kt`) shows Paste link, Scan QR, Import file (`WireGuard / AmneziaWG from file`, the existing `.conf` import), and Add manually (the existing editor menu: AnyTLS, Mieru, SSH, AmneziaWG, Subscription). Onboarding, the Connection Add server sheet, and the Profiles sheet all render it. It reuses the existing clipboard import, QR scanner route, `.conf` picker, and editor routes; no import logic is new.
- `Import file` moved from the Add profile menu to its own button, and the menu button is now `Add manually` (`config_vpn_add_server_manual`). The old `config_vpn_add_profile_title` key is deleted from all locales. Existing test tags stay, so journeys keep their selectors.
- Onboarding gets an `OwnServer` step before the connection test, titled `Have your own server?`, with no body text. Continue skips it. It sits before the test so a server added here is the one the test checks. It reuses the `ConnectScene` illustration. Preset onboarding keeps the step unchanged.
- An import or editor opened from onboarding returns to onboarding: `navigateHome()` calls `popBackToOnboarding()` first, which pops back to `Route.Onboarding` while onboarding is on the back stack, so the confirm screens do not show Home before onboarding completes.
- `navigateConnectionSubRoute` moved from `RipDpiNavHost.kt` to `ConnectionNavigation.kt`, so `RipDpiNavHost.kt` stays under the 500-code-line threshold of the architecture-health gate.
- The connection-test permission note no longer says `No remote VPN server is used`. The last sentence is removed in all ten locales, because a user can now add a remote server on the step before. The note still says the tunnel is local, which stays true with a relay.
- Home shows an outline `Add server` button under the connection measurements only while `SavedServersViewModel.hasSavedServer` is `false`. The value combines the profile catalog (relay, Xray, subscription members) and the AmneziaWG repository. It is `null` while loading or after a read failure, so the button never flashes over saved servers. `MainActivityContent` reads it and provides it as `LocalHasSavedServer`, which the home route reads; hosts without a provider (tests, previews) get `null` and show no button.
- The home button opens `Route.ConnectionAddServer` (`config/add_server`), which is the Connection tab with the Add server sheet open. It is a Connection-tab variant, so it is in `connectionSubRouteStableKeys`. It navigates without `restoreState`, so the sheet opens on every tap. The Profiles screen's import action now opens the same route.
- The Connection tab has an `Add server` row in the saved-connection card, under the profile row. It opens the Add server sheet and does not depend on the route diagram.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
