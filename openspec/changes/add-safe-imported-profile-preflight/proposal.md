# Change: Add safe imported profile preflight

Task ID: `RLY-1786618247484998`

## Why

The generic share-link import flow currently lets a user either inspect a parsed profile or immediately persist and activate it. It has no way to verify that the profile can start a relay and reach a controlled TCP target before changing durable configuration. The existing Xray `Check profile` action validates syntax only, so reusing its wording without a runtime distinction would be misleading.

Users need an explicit, bounded preflight that can identify an unusable relay profile before import while guaranteeing that the check cannot interfere with an active VPN/proxy session, persist secrets, or leave a native runtime running.

## What Changes

- Add a localized `Check profile` action to the single-profile import-confirmation screen for relay-activatable profiles.
- Run exactly one isolated ephemeral relay session and one bounded TCP egress probe when the app service is fully halted.
- Preserve all profile, credential, group, settings, failover, and service state during the check, and clean up the temporary runtime on every terminal path.
- Report typed, privacy-safe check states. A successful result states only that the relay reached the test target during this check; it does not claim that the profile was imported, selected, or that VPN traffic is validated.
- Disable or reject the preflight while VPN/proxy lifecycle work is active, while another check is running, or for an unsupported profile kind.
- No breaking external contract or compatibility layer is introduced.

## Capabilities

### New Capabilities

- `safe-imported-profile-preflight`: Isolated, bounded, privacy-safe runtime verification of a parsed relay profile before import.

### Modified Capabilities

- None. The new capability owns its import-confirmation UI integration as well as its runtime behavior.

## Impact

- `:app`: import-confirmation ViewModel, Compose UI, dependency injection, UI tests, screenshots, and all nine locales.
- `:core:service`: reusable preflight orchestration around the relay runtime and existing TCP capability probe.
- `:core:engine-api`: existing relay runtime/factory interfaces are consumed; no JNI or wire-schema change is expected.
- Runtime safety: the preflight is unavailable unless the VPN/proxy service is halted, so it never opens unprotected non-loopback sockets while a VPN protection callback may be active.
- Verification: JVM tests, Compose/screenshot tests, locale lint, static analysis, architecture checks, and a physical-device owner-controlled relay proof are required.
