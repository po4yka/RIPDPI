# Selector Contract

This document defines the stable selector rules for external UI automation.

## Source Of Truth

All stable IDs live in [`RipDpiTestTags.kt`](../../app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt). New Maestro/Appium flows should reuse those IDs instead of inventing local selectors.

## Rules

- Put the tag on the actual clickable, editable, or dismissible node.
- Keep one stable ID per externally automated element.
- Use business keys for dynamic rows and options.
- Expose tags through `ripDpiAutomationTreeRoot()` at app and dialog roots.
- Prefer tag selectors over visible text for navigation, form entry, and save actions.
- If a flow needs a new selector, add it to `RipDpiTestTags` first and cover it in a repo test.

## Selector Families

- Screen roots (generated via `RipDpiTestTags.screen(Route)` as `"${route.stableRoute}-screen"`; tracks `Route.all` in `Route.kt`): `about-screen`, `advanced_settings-screen`, `app_customization-screen`, `asset_provider-screen`, `backup_restore-screen`, `biometric_prompt-screen`, `blockcheck-screen`, `config-screen`, `config/local_bypass-screen`, `config/vpn-screen`, `data_transparency-screen`, `detection_check-screen`, `detection_settings-screen`, `diagnostics-screen`, `dns_settings-screen`, `domain_bypass_list-screen`, `history-screen`, `home-screen`, `import/profile_confirm-screen`, `import/subscription_confirm-screen`, `logs-screen`, `mode_editor-screen`, `onboarding-screen`, `owned_stack_browser-screen`, `pcap_capture_list-screen`, `pcap_viewer-screen`, `profile/amneziawg-screen`, `profile/anytls-screen`, `profile/mieru-screen`, `profile/ssh-screen`, `replay_history-screen`, `routes-screen`, `rule_editor-screen`, `scanner-screen`, `settings-screen`, `shared_diagnostic_result-screen`, `split_tunnel-screen`, `strategy_config-screen`, `xray/import-screen`. Static audit-only presentation screens also expose `profile_variants-screen` and `strategy_import-screen`.
- Bottom navigation: `bottom-nav-home`, `bottom-nav-config`, `bottom-nav-diagnostics`, `bottom-nav-settings`
- Permission and repair actions: `settings-permission-<kind>`, `home-permission-issue-banner`, `home-permission-recommendation-banner`
- Settings navigation: `settings-dns-settings`, `settings-advanced-settings`, `settings-support-bundle`, `settings-data-transparency`, `settings-about`
- Advanced settings: `advanced-section-<section>`, `advanced-toggle-<setting>`, `advanced-input-<setting>`, `advanced-save-<setting>`, `advanced-option-<setting>`; the bypass strategy section is `advanced-section-bypass-strategy`.
- Activation ranges: `advanced-<dimension>-from`, `advanced-<dimension>-to`, `advanced-<dimension>-save`
- Diagnostics and logs: `diagnostics-top-history-action`, `diagnostics-share-archive`, `diagnostics-save-archive`, `diagnostics-share-summary`, `diagnostics-save-logs`, `diagnostics-status-snackbar`, `logs-save`, `logs-clear`
- Diagnostics strategy reports: `diagnostics-strategy-probe-report`, `diagnostics-strategy-probe-summary`, `diagnostics-strategy-winning-path`, `diagnostics-strategy-winning-tcp-action`, `diagnostics-strategy-winning-quic-action`, `diagnostics-strategy-full-matrix-toggle`, `diagnostics-strategy-audit-assessment`, `diagnostics-strategy-audit-low-confidence-banner`, `diagnostics-strategy-audit-medium-confidence-note`, `diagnostics-workflow-restriction-card`, `diagnostics-workflow-restriction-action`

## Key Examples

- Home root: `home-screen`
- Home mode cards: `home-mode-card-vpn`, `home-mode-card-proxy`
- Home mode primary actions: `home-mode-primary-vpn`, `home-mode-primary-proxy`
- Home connection actuator stages: `home-connection-stage-network`, `home-connection-stage-dns`, `home-connection-stage-handshake`, `home-connection-stage-tunnel`, `home-connection-stage-route`
- Secure route design-system samples: `route-profile-{id}`, `route-capability-{kind}`, `route-stack`, `route-opportunity-panel`
- Open advanced settings: `settings-advanced-settings`
- Advanced diagnostics retention input: `advanced-input-diagnostics-history-retention-days`
- Advanced diagnostics retention save: `advanced-save-diagnostics-history-retention-days`
- Diagnostics share archive: `diagnostics-share-archive`
- Automatic Audit winning path: `diagnostics-strategy-winning-path`
- Automatic Audit full matrix toggle: `diagnostics-strategy-full-matrix-toggle`
- Diagnostics remediation CTA: `diagnostics-workflow-restriction-action`
- Onboarding continue: `onboarding-continue`

## Resource ID Notes

The raw `testTag` value is the selector contract. Depending on the driver and inspector, the same element may appear as either:

- `home-screen`
- `com.poyka.ripdpi:id/home-screen`

When in doubt, inspect the debug build on a device and keep the raw tag value in test source so the same identifier works across Compose tests, UiAutomator, Maestro, and Appium.
