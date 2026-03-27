# Appium Smoke Tests

Python + pytest test suite targeting the RIPDPI debug automation contract via Appium UiAutomator2.

## Prerequisites

- Debug APK installed on an emulator or device (`./gradlew assembleDebug`).
- Appium 2.x with the UiAutomator2 driver:
  ```bash
  npm install -g appium
  appium driver install uiautomator2
  ```
- Python 3.11+ with dependencies:
  ```bash
  pip install -r appium/requirements.txt
  ```

## Run

Start Appium server in one terminal:

```bash
appium
```

Run all tests in another:

```bash
cd appium
pytest tests/ -v
```

Run a single test:

```bash
cd appium
pytest tests/test_01_cold_launch.py -v
```

Generate an HTML report:

```bash
cd appium
pytest tests/ -v --html=appium-report.html --self-contained-html
```

## Structure

```
appium/
  conftest.py          # Driver lifecycle + automation launch fixtures
  lib/                 # Capabilities, launch contract, helpers
  pages/               # Page objects (one per screen)
  tests/               # Test files (test_01_ through test_08_)
```

## How It Works

Each test is decorated with `@pytest.mark.automation(...)` specifying the automation
launch contract parameters. The `conftest.py` `launch_app` fixture:

1. Force-stops the app via `adb`.
2. Launches `MainActivity` with automation intent extras (`ENABLED`, `RESET_STATE`,
   `START_ROUTE`, `PERMISSION_PRESET`, `SERVICE_PRESET`, `DATA_PRESET`).
3. Waits for the expected screen resource ID to appear.

This mirrors the Maestro flows under `maestro/` but uses Appium for richer assertions
and programmatic control.

## CI

The GitHub Actions CI workflow exposes a `run_appium_smoke` input on `workflow_dispatch`.
When enabled, the Appium smoke suite runs after the Android E2E instrumentation step.

```bash
bash scripts/ci/run-appium-smoke.sh
```

## Tests

| # | File | Coverage |
|---|------|----------|
| 1 | `test_01_cold_launch.py` | Home screen visible after cold launch |
| 2 | `test_02_tab_navigation.py` | Bottom nav switches across all 4 tabs |
| 3 | `test_03_settings_navigation.py` | Settings to advanced settings navigation |
| 4 | `test_04_advanced_settings.py` | Edit diagnostics retention days |
| 5 | `test_05_connect_disconnect.py` | VPN connect/disconnect toggle |
| 6 | `test_06_config_mode_selection.py` | Config preset chip selection |
| 7 | `test_07_dns_settings.py` | Plain DNS address entry and save |
| 8 | `test_08_diagnostics_sections.py` | Diagnostics pager section navigation |
