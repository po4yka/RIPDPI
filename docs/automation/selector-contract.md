# Selector Contract

This document defines the stable selector rules for external UI automation.

## Source Of Truth

All stable IDs live in
[`RipDpiTestTags.kt`](../../app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt).
New Maestro/Appium flows should reuse those IDs instead of inventing local selectors.

## Rules

- Put the tag on the actual clickable, editable, or dismissible node.
- Keep one stable ID per externally automated element.
- Use business keys for dynamic rows and options.
- Expose tags through `ripDpiAutomationTreeRoot()` at app and dialog roots.
- Prefer tag selectors over visible text for navigation, form entry, and save actions.
- If a flow needs a new selector, add it to `RipDpiTestTags` first and cover it in a repo test.

## Selector Families

- Screen roots: `home-screen`, `settings-screen`, `advanced_settings-screen`, `diagnostics-screen`
- Bottom navigation: `bottom-nav-home`, `bottom-nav-config`, `bottom-nav-diagnostics`,
  `bottom-nav-settings`
- Permission and repair actions: `settings-permission-<kind>`, `home-permission-issue-banner`,
  `home-permission-recommendation-banner`
- Settings navigation: `settings-dns-settings`, `settings-advanced-settings`,
  `settings-support-bundle`, `settings-data-transparency`, `settings-about`
- Advanced settings:
  `advanced-section-<section>`, `advanced-toggle-<setting>`, `advanced-input-<setting>`,
  `advanced-save-<setting>`, `advanced-option-<setting>`
- Activation ranges:
  `advanced-<dimension>-from`, `advanced-<dimension>-to`, `advanced-<dimension>-save`
- Diagnostics and logs:
  `diagnostics-top-history-action`, `diagnostics-share-archive`,
  `diagnostics-save-archive`, `diagnostics-share-summary`, `diagnostics-save-logs`,
  `diagnostics-status-snackbar`, `logs-save`, `logs-clear`
- Diagnostics strategy reports:
  `diagnostics-strategy-probe-report`, `diagnostics-strategy-probe-summary`,
  `diagnostics-strategy-winning-path`, `diagnostics-strategy-winning-tcp-action`,
  `diagnostics-strategy-winning-quic-action`, `diagnostics-strategy-full-matrix-toggle`,
  `diagnostics-strategy-audit-assessment`, `diagnostics-strategy-audit-low-confidence-banner`,
  `diagnostics-strategy-audit-medium-confidence-note`, `diagnostics-workflow-restriction-card`,
  `diagnostics-workflow-restriction-action`

## Key Examples

- Home root: `home-screen`
- Home connection actuator root: `home-connection-button`
- Home connection route label: `home-connection-route-label`
- Home connection actuator stages:
  `home-connection-stage-network`, `home-connection-stage-dns`,
  `home-connection-stage-handshake`, `home-connection-stage-tunnel`,
  `home-connection-stage-route`
- Secure route design-system samples:
  `route-profile-{id}`, `route-capability-{kind}`, `route-stack`,
  `route-opportunity-panel`
- Open advanced settings: `settings-advanced-settings`
- Advanced diagnostics retention input:
  `advanced-input-diagnostics-history-retention-days`
- Advanced diagnostics retention save:
  `advanced-save-diagnostics-history-retention-days`
- Diagnostics share archive: `diagnostics-share-archive`
- Automatic Audit winning path: `diagnostics-strategy-winning-path`
- Automatic Audit full matrix toggle: `diagnostics-strategy-full-matrix-toggle`
- Diagnostics remediation CTA: `diagnostics-workflow-restriction-action`
- Onboarding continue: `onboarding-continue`

## Resource ID Notes

The raw `testTag` value is the selector contract. Depending on the driver and inspector, the same
element may appear as either:

- `home-screen`
- `com.poyka.ripdpi:id/home-screen`

When in doubt, inspect the debug build on a device and keep the raw tag value in test source so the
same identifier works across Compose tests, UiAutomator, Maestro, and Appium.
