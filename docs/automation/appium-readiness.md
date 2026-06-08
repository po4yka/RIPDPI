# Appium Readiness

RIPDPI is prepared for Appium through the debug automation launch contract and Compose resource-id exposure. This document keeps the first manual smoke path narrow and repeatable.

## Desired Capabilities Template

Use UiAutomator2 against a debug build:

```json
{
  "platformName": "Android",
  "appium:automationName": "UiAutomator2",
  "appium:deviceName": "Android",
  "appium:appPackage": "com.poyka.ripdpi",
  "appium:appActivity": "com.poyka.ripdpi.activities.MainActivity",
  "appium:noReset": true,
  "appium:newCommandTimeout": 120
}
```

## Inspector Checklist

- Install a `debug` APK, not `release`.
- Start the app with automation extras before attaching the inspector.
- Use `DISABLE_MOTION=true` for inspector sessions.
- Verify resource IDs are visible for: `home-screen`, `settings-screen`, `advanced_settings-screen`, `home-mode-primary-local-dpi-bypass`, `home-mode-primary-remote-vpn`
- Check that bottom nav IDs are present: `bottom-nav-home`, `bottom-nav-settings`
- Check that form controls expose the same IDs as `RipDpiTestTags`.

## Manual Smoke Spec

1. Force-stop the app.
2. Launch the debug activity with automation extras.
3. Attach Appium Inspector with the capabilities above.
4. Confirm the requested route is open.
5. Confirm the expected resource IDs are visible in the hierarchy.

Example launch:

```bash
adb shell am force-stop com.poyka.ripdpi

adb shell am start \
  -n com.poyka.ripdpi/.activities.MainActivity \
  --ez com.poyka.ripdpi.automation.ENABLED true \
  --ez com.poyka.ripdpi.automation.RESET_STATE true \
  --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
  --es com.poyka.ripdpi.automation.START_ROUTE settings \
  --es com.poyka.ripdpi.automation.PERMISSION_PRESET granted \
  --es com.poyka.ripdpi.automation.SERVICE_PRESET idle \
  --es com.poyka.ripdpi.automation.DATA_PRESET settings_ready
```

## Selector Reference

Use the raw tag values from `RipDpiTestTags` as your locator contract.

- Screen roots (generated via `RipDpiTestTags.screen(Route)` as `"${route.stableRoute}-screen"`; tracks `Route.all` in `Route.kt`): `about-screen`, `advanced_settings-screen`, `app_customization-screen`, `asset_provider-screen`, `backup_restore-screen`, `biometric_prompt-screen`, `blockcheck-screen`, `config-screen`, `config/local_bypass-screen`, `config/vpn-screen`, `data_transparency-screen`, `detection_check-screen`, `detection_settings-screen`, `diagnostics-screen`, `dns_settings-screen`, `domain_bypass_list-screen`, `handshake_timeline-screen`, `history-screen`, `home-screen`, `import/profile_confirm-screen`, `import/subscription_confirm-screen`, `latency_graph-screen`, `logs-screen`, `mode_editor-screen`, `onboarding-screen`, `oom_recovery-screen`, `owned_stack_browser-screen`, `pcap_capture_list-screen`, `pcap_viewer-screen`, `profile/amneziawg-screen`, `profile/anytls-screen`, `profile/mieru-screen`, `profile/ssh-screen`, `profile_variants-screen`, `replay_failure-screen`, `replay_history-screen`, `routes-screen`, `rule_editor-screen`, `scanner-screen`, `settings-screen`, `shared_diagnostic_result-screen`, `split_tunnel-screen`, `state_machine-screen`, `strategy_ab-screen`, `strategy_config-screen`, `strategy_import-screen`, `throughput_graph-screen`, `xray/import-screen`
- Primary actions: `home-mode-primary-local-dpi-bypass`, `home-mode-primary-remote-vpn`, `home-mode-primary-diagnostic`, `settings-advanced-settings`, `settings-dns-settings`, `mode-editor-save`, `dns-custom-save`
- DNS configuration: `dns-mode-encrypted`, `dns-mode-plain-udp`, `dns-protocol-doh`, `dns-protocol-dot`, `dns-protocol-dnscrypt`, `dns-resolver-cloudflare`, `dns-resolver-google`, `dns-resolver-quad9`, `dns-resolver-adguard`
- Diagnostics and sharing: `diagnostics-top-history-action`, `diagnostics-share-archive`, `diagnostics-save-archive`, `diagnostics-share-summary`, `diagnostics-save-logs`
- Dialogs: `vpn-permission-dialog`, `vpn-permission-dialog-continue`, `vpn-permission-dialog-dismiss`

For the full registry, use [`RipDpiTestTags.kt`](../../app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt).
