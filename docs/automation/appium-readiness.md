# Appium Readiness

RIPDPI is prepared for Appium through the debug automation launch contract and Compose resource-id
exposure. This document keeps the first manual smoke path narrow and repeatable.

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
- Verify resource IDs are visible for:
  `home-screen`, `settings-screen`, `advanced_settings-screen`, `home-connection-button`
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

- Screen roots:
  `home-screen`, `config-screen`, `diagnostics-screen`, `settings-screen`,
  `advanced_settings-screen`, `dns_settings-screen`, `onboarding-screen`
- Primary actions:
  `home-connection-button`, `settings-advanced-settings`, `settings-dns-settings`,
  `mode-editor-save`, `dns-custom-save`
- Diagnostics and sharing:
  `diagnostics-top-history-action`, `diagnostics-share-archive`,
  `diagnostics-save-archive`, `diagnostics-share-summary`, `diagnostics-save-logs`
- Dialogs:
  `vpn-permission-dialog`, `vpn-permission-dialog-continue`,
  `vpn-permission-dialog-dismiss`

For the full registry, use
[`RipDpiTestTags.kt`](../../app/src/main/java/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt).
