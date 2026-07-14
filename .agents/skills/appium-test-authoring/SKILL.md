---
name: appium-test-authoring
description: Appium test authoring, page objects, locators, assertions, and new-screen coverage.
---

# Appium Test Authoring

Python pytest suite under `appium/`. Page Object Model with resource-id locators via `AppiumBy.ID`. Automation contract drives app state (see `appium-automation-contract` skill).

Tag source of truth:

- `app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt`
- `docs/automation/selector-contract.md`

## Directory Layout

```
appium/
  conftest.py                  # Session driver + per-test launch fixture
  pytest.ini                   # Pytest config and custom markers
  requirements.txt             # Pinned deps (Appium-Python-Client, pytest, selenium)
  lib/
    capabilities.py            # UiAutomator2Options builder
    launch_contract.py         # Automation contract constants + arg builder
    driver_helpers.py          # Shared wait/find helpers
  pages/
    base_page.py               # Base class -- all page objects inherit this
    {screen}_page.py           # One page object per screen
  tests/
    test_{NN}_{description}.py # Numbered test files
```

## Creating a Page Object

File: `appium/pages/{screen}_page.py`

Checklist:
1. Module docstring: `"""{Screen name} screen page object."""`
2. `from __future__ import annotations` + `from .base_page import BasePage`
3. Class inherits `BasePage`
4. `SCREEN = "{screen}-screen"` class constant
5. All locators as `SCREAMING_SNAKE` class constants
6. `is_loaded()` method: `return self.is_visible(self.SCREEN)`
7. Semantic action methods (no raw driver calls)
8. No assertions -- expose state via `is_*_visible()` methods

### Tag Naming Pattern

| Component | Pattern | Example |
|-----------|---------|---------|
| Screen root | `{screen}-screen` | `home-screen` |
| Button | `{screen}-{action}[-button]` | `home-connection-button` |
| Input field | `{screen}-{field}` | `dns-plain-address` |
| Toggle | `{screen}-{name}-toggle` | `settings-webrtc-toggle` |
| Card | `{screen}-{name}-card` | `home-approach-card` |
| Dialog | `{component}-dialog` | `vpn-permission-dialog` |
| Dialog action | `{component}-dialog-{action}` | `vpn-permission-dialog-continue` |
| Dynamic | `{screen}-{type}-{identifier}` | `dns-resolver-cloudflare` |

### Template

```python
"""{Screen name} screen page object."""

from __future__ import annotations

from .base_page import BasePage


class ExamplePage(BasePage):
    SCREEN = "example-screen"
    TITLE = "example-title"
    PRIMARY_BUTTON = "example-primary-button"
    INPUT_FIELD = "example-input-field"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_primary(self) -> None:
        self.tap(self.PRIMARY_BUTTON)

    def set_input(self, text: str) -> None:
        self.clear_and_type(self.INPUT_FIELD, text)

    def is_title_visible(self) -> bool:
        return self.is_visible(self.TITLE)
```

### Method Naming Conventions

| Action | Pattern | Return |
|--------|---------|--------|
| Tap/click | `tap_{component}()` | `None` |
| Type text | `set_{field}(value)` | `None` |
| Clear + type | `fill_{field}(value)` or `set_{field}(value)` | `None` |
| Check visibility | `is_{component}_visible()` | `bool` |
| Select option | `select_{option}(value)` | `None` |
| Toggle switch | `toggle_{setting}()` | `None` |
| Navigate away | `tap_{destination}()` | destination page object (optional) |
| Scroll to element | use `self.scroll_to(TAG)` internally | `None` or element |

## BasePage API

Source: `appium/pages/base_page.py`

| Method | Signature | Default | Notes |
|--------|-----------|---------|-------|
| `_resource_id` | `(tag: str) -> str` | -- | Prepends `com.poyka.ripdpi:id/` if tag has no `:` |
| `find` | `(tag: str) -> WebElement` | -- | Immediate lookup, no wait. Raises if not found. |
| `wait_for` | `(tag: str, timeout: int = 10) -> WebElement` | 10s | Explicit wait via `EC.presence_of_element_located` |
| `is_visible` | `(tag: str, timeout: int = 3) -> bool` | 3s | Returns `False` on timeout, never raises |
| `tap` | `(tag: str, timeout: int = 10) -> None` | 10s | `wait_for` then `.click()` |
| `clear_and_type` | `(tag: str, text: str, timeout: int = 10) -> None` | 10s | `.clear()` then `.send_keys(text)` |
| `swipe_horizontal` | `(direction: str = "left") -> None` | left | Swipes across screen center, 300ms duration |
| `scroll_to` | `(tag: str, max_swipes: int = 5) -> WebElement` | 5 swipes | Swipes down until visible, returns element |

## Locator Conventions

- **Only use `AppiumBy.ID`** with resource-id tags. Never XPath, class name, or accessibility-id.
- Tags without `:` are auto-prefixed by `_resource_id()` to `com.poyka.ripdpi:id/{tag}`.
- Tags map to `Modifier.testTag("{tag}")` in Jetpack Compose source.
- Keep tags short and descriptive. Use hyphens, not underscores (except for screen names that mirror route names like `dns_settings`).
- For diagnostics strategy reports, prefer the dedicated stable tags rather than visible text, for example `diagnostics-strategy-winning-path`, `diagnostics-strategy-full-matrix-toggle`, and `diagnostics-workflow-restriction-action`.

## Creating a Test File

File: `appium/tests/test_{NN}_{description}.py` (use next sequential number)

Checklist:
1. Module docstring: `"""Test {NN}: {description}."""`
2. `import pytest` + import page object(s)
3. `@pytest.mark.automation(...)` with keyword params
4. Function: `def test_{descriptive_name}(driver):`
5. Instantiate page object: `page = PageClass(driver)`
6. Assert screen loaded: `assert page.is_loaded(), "Screen should be visible"`
7. Interact via page object methods
8. Assert outcomes with descriptive failure messages
9. For multi-page flows: instantiate new page object after navigation

### Template

```python
"""Test NN: Description of what this test covers."""

import pytest

from pages.example_page import ExamplePage


@pytest.mark.automation(
    start_route="example",
    data_preset="settings_ready",
)
def test_example_feature(driver):
    page = ExamplePage(driver)
    assert page.is_loaded(), "Example screen should be visible"

    page.set_input("test value")
    page.tap_primary()

    assert page.is_title_visible(), "Title should remain visible after action"
```

### Multiple Tests per File

When testing different flows on the same screen, use separate functions with their own markers:

```python
@pytest.mark.automation(start_route="dns_settings", data_preset="settings_ready")
def test_doh_resolver_selection(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings should be visible"
    dns.select_doh_resolver("cloudflare")

@pytest.mark.automation(start_route="dns_settings", data_preset="settings_ready")
def test_custom_doh_url(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings should be visible"
    dns.set_custom_doh_url("https://example.com/dns-query")
    assert dns.is_custom_save_visible(), "Save button should appear"
```

## Assertion Patterns

```python
# Visibility check -- always include failure message
assert page.is_visible(TAG), "Description of what should be true"

# Negative check
assert not page.is_visible(TAG), "Element should not be visible after action"

# Screen transition -- assert new page loaded after navigation
page.tap_settings()
settings = SettingsPage(driver)
assert settings.is_loaded(), "Settings screen should appear"
```

Rules:
- Always `assert` (not `unittest` assertions)
- Always include a failure message string
- Assertions only in test functions, never in page objects

## Wait Strategy

| Tier | Timeout | Use For |
|------|---------|---------|
| Short | 3s | `is_visible()` probes, elements expected on current screen |
| Medium | 10s | `wait_for()`, `tap()`, normal element appearance |
| Long | 15s | Screen launch in `conftest.py` only |

- Never use `time.sleep()` in test files
- Never use implicit waits
- Override timeout per-call when needed: `page.wait_for("slow-element", timeout=15)`

## Updating Existing Tests

| Change | Action |
|--------|--------|
| Locator tag renamed | Update the class constant in the page object only |
| New element on existing screen | Add constant + semantic method to page object, add test |
| Screen added | Create new page object + new test file |
| Screen removed | Delete page object + test file |
| Navigation path changed | Update test flow, not page object API |
| Preset values changed | Update marker params in affected tests |

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using XPath or class name locators | Use `AppiumBy.ID` with resource-id tags only |
| Assertions in page objects | Move to test functions; page objects expose state |
| `time.sleep()` in tests | Use `wait_for()` or `is_visible()` with timeout |
| Missing failure message on assert | Always provide descriptive string |
| Hardcoding `com.poyka.ripdpi:id/` prefix | Use tag shorthand; `_resource_id()` adds prefix |
| Skipping `is_loaded()` check | Always verify screen loaded as first assertion |
| Monolithic tests testing many features | One behavior per test function |
| Missing `@pytest.mark.automation` | Every test needs the marker for the launch contract |

## See Also

- `.github/skills/appium-automation-contract/SKILL.md` -- Preset values and marker parameters
- `.github/skills/appium-test-debug/SKILL.md` -- Troubleshooting failures and flakiness
