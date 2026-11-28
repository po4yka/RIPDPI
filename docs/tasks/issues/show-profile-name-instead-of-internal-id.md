---
id: UIX-1795615862353491
title: Show the profile name instead of its internal ID
kind: bug
status: review
area: ui
priority: high
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: regression-tested-single-module
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); a device run remains: import a link named with a fragment, and a subscription, and check that the Servers sheet, the Connection card, and the failover screen show the names and never the internal ID."
---

## Goal

Every server list and card shows the name the user or the link gave the server.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). A link imported as `#Test%20VLESS%20Reality` shows the internal ID (`de53ebe8-...`) in the Servers sheet and the Connection card. Subscription servers show `Server 1: www.example.com`, the SNI, instead of the name.

## Acceptance criteria

- The Servers sheet, the Connection card, and subscription servers show the profile name; a test covers each surface.
- A profile without a name shows the kind and host, never the internal ID.

## Ownership

One writer in a dedicated worktree owns server display mapping in `app` and its tests.

## Evidence

- Cause: link and subscription servers are relay profiles keyed by the member's generated id; the name lives on the proxy group member. `RelayProfileUiState.selectorLabel` printed the id, and the subscription failover screen printed the TLS server name.
- Fix: `List<ProxyGroup>.serverNames()` next to `exitName()` maps member id to its name, or its host when the name is blank. The Servers sheet, the Connection card, the relay chain options, and the subscription failover screen use it; an own server keeps its typed name, which is its id.
- Tests: `ConnectionChainRelayUiStateTest` (named, nameless, and own server), `ConnectionScreenTest` (Connection card and Servers sheet show the name, never the id), `SubscriptionFailoverViewModelTest` (subscription server name and active label), and `SubscriptionRetainedLocaleRouteTest` (name comes from the member).
- Gates on the branch rebased onto main 70f3595801: `./gradlew verifyRoborazziGithubDebug` exit 0 (4324 app tests, no golden change), `just ci-gates` exit 0, `./taskctl validate` exit 0.
- Not checked: no device run. A nameless member shows its host; the active-exit chip keeps `exitName()`, which shows the kind instead of a host. The rename dialog prefilled the id of an imported server at that time; UIX-1795677614058050 fixed it.
- Remote CI: run 46009834608 on main d9aa00b99f passed (35 jobs, no failures).
