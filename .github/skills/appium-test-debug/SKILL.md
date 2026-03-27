---
name: appium-test-debug
description: Use when Appium tests fail, are flaky, or produce unexpected results. Covers locator failures, wait/timeout issues, session problems, and test stability. Triggers on: flaky, NoSuchElement, TimeoutException, StaleElement, appium fail, appium debug, appium error.
---

# Appium Test Debug

Systematic troubleshooting for failing or flaky Appium tests in the RIPDPI suite.

## Triage Checklist

Work through in order -- stop at the first failure:

1. **Appium server running?** -- `curl -s http://127.0.0.1:4723/status | jq .value.ready`
2. **Device/emulator connected?** -- `adb devices -l` (should show at least one device)
3. **Debug APK installed?** -- `adb shell pm list packages | grep ripdpi`
4. **Route exists in Kotlin?** -- Search for the `start_route` value in `Route.kt` sealed class
5. **testTag present in the contract?** -- check `app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt` and `docs/automation/selector-contract.md`, then confirm the tag is actually attached in Compose source
6. **Correct preset combination?** -- See preset tables in `appium-automation-contract` skill

## Failure Patterns

| Symptom | Cause | Fix |
|---------|-------|-----|
| `NoSuchElementException` | Element not on screen, wrong tag, or needs scroll | Verify tag matches `Modifier.testTag()` in Compose source. Use `scroll_to()` if below viewport. |
| `TimeoutException` from `wait_for` | Screen didn't load or element not rendered | Check automation contract params -- wrong `start_route` or `data_preset`. Increase timeout for slow emulators. |
| `StaleElementReferenceException` | Compose recomposition invalidated element reference | Re-find the element after any action that triggers recomposition. Don't store element references across interactions. |
| Session creation fails | Appium server down, UiAutomator2 driver missing, or no device | Run triage checklist steps 1-3. Install driver: `appium driver install uiautomator2`. |
| Passes locally, fails in CI | Animation timing, slower emulator, permission state | Verify `disable_motion=True` in marker. Check CI emulator specs. Increase timeout if needed. |
| Wrong screen appears | Incorrect `start_route` or route not handled in contract | Verify route exists in Kotlin `Route` class. Check `data_preset` matches screen requirements. |
| Element found but tap has no effect | Element overlapped by another, or animation in progress | Wait for animations to settle. Check if a dialog/overlay is blocking. Use `wait_for` before `tap`. |

## Flakiness Diagnosis

Step-by-step:

1. **Reproduce** -- Run the single test 5 times:
   ```bash
   for i in {1..5}; do pytest tests/test_XX.py::test_name -v; done
   ```
2. **Classify** -- Is the failure:
   - **Timing** (passes with longer timeout)? Increase specific timeout, not global.
   - **State** (depends on previous test)? Verify `reset_state=True` in marker.
   - **Animation** (element moves during interaction)? Verify `disable_motion=True`.
   - **Race condition** (element appears then disappears)? Check if Compose recomposes the element.
3. **Check screenshot** -- Failure screenshots in `appium/screenshots/{test_name}.png` show what was actually displayed.
4. **Check element tree** -- Add `print(driver.page_source)` temporarily to dump the XML tree and search for the expected resource-id.
5. **Check Appium logs** -- Server logs show the exact command sent and UiAutomator2's response.

## Debugging Locators

### Verify a tag exists on screen

```python
# In test or debug session:
source = driver.page_source
assert "com.poyka.ripdpi:id/{tag}" in source, f"Tag '{tag}' not in element tree"
```

### Dump element tree via ADB

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
rg "resource-id" ui.xml  # Search for specific IDs
```

### Match against Compose source

```bash
# Find which composable sets the testTag
rg '{tag}|RipDpiTestTags\\.' app/src/main/kotlin/
```

If the tag is not found, the composable is missing `Modifier.testTag("{tag}")` -- this is the root cause and must be fixed in the Kotlin source, not the test.

## Screenshot Analysis

The `conftest.py` fixture saves screenshots on failure:
- Location: `appium/screenshots/{test_name}.png`
- Created automatically by `launch_app` fixture when `rep_call.failed` is `True`
- Check the screenshot to verify: correct screen displayed, element visibility, dialog/overlay blocking

## Wait Strategy Fixes

| Problem | Solution |
|---------|----------|
| Element appears after animation | Use `wait_for(tag, timeout=10)` not `find(tag)` |
| Element appears after network call | Increase to `wait_for(tag, timeout=15)` |
| Element below viewport | Use `scroll_to(tag)` before interacting |
| Element disappears after action | Use `is_visible(tag, timeout=3)` with `assert not` |
| Screen takes long to load | Only increase timeout in `conftest.py` launch wait (15s), not in individual tests |
| Element exists in DOM but not rendered | Use `is_visible()` which checks presence, not just DOM |

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Adding `time.sleep()` to fix timing | Use `wait_for()` or `is_visible()` with appropriate timeout |
| Increasing all timeouts globally | Identify the specific slow element and adjust only that call |
| Ignoring `reset_state=True` | Stale state from previous test causes cascading failures |
| Catching exceptions to hide failures | Let exceptions propagate; fix the root cause |
| Re-running flaky test without diagnosis | Classify the failure type first (timing/state/animation) |

## See Also

- `.github/skills/appium-automation-contract/SKILL.md` -- Preset values and launch flow
- `.github/skills/appium-test-authoring/SKILL.md` -- Conventions for writing tests and page objects
- `.github/skills/android-device-debug/SKILL.md` -- ADB commands, logcat, emulator management
