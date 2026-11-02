# Change: Give cards a tonal hierarchy and one elevation rule

Task ID: `UIX-1793534441702605`

## Why

Almost every surface in the app is the same outlined card on the page color. Primary and secondary content have the same weight. Some cards use a grey fill with no rule, and shadows are inconsistent. A UI/UX audit of the Material 3 work on 2026-11-01 found about 20 call sites that use the Elevated or Status card role for ordinary content. `DESIGN.md` says default cards stay flat, so these sites break the design contract.

## What Changes

- Define one card rule: `surfaceContainer` tonal steps group content, an outline marks only interactive or selected cards, and a shadow marks only hero or selected cards.
- Document the rule in `DESIGN.md` and `docs/design-system.md`.
- Apply the rule to every call site named in the portfolio task, including the `RipDpiRemediationCard` default.
- Re-record the affected Roborazzi goldens with owner approval.

## Capabilities

### New Capabilities

- `m3-card-hierarchy`: One card rule for tonal grouping, outline, and shadow.

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Compose card components and the screens that use them in `:app`.
- Roborazzi baselines for the changed screens.
- No protobuf, JNI, wire, or locale change.
