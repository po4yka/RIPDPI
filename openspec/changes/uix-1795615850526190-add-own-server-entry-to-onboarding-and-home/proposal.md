# Change: Add an own-server entry point to onboarding and home

Task ID: `UIX-1795615850526190`

## Why

A new user who has their own VPN server finds how to add it during onboarding and from the home and Connection screens.

## What Changes

- Onboarding has an optional, skippable step that offers Paste link, Scan QR, Import file, and Add manually.
- The home screen shows an add-server action while no server is saved.
- The Connection tab has a visible Add server control that does not depend on the diagram node.
- Onboarding copy does not claim that no remote server is used when the user added one.
- New strings exist in all ten locales and Roborazzi baselines are updated under the bless workflow.

## Capabilities

### New Capabilities

- `uix-1795615850526190-add-own-server-entry-to-onboarding-and-home`: Add an own-server entry point to onboarding and home

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
