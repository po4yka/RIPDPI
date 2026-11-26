# Change: Use the server term in all remaining strings

Task ID: `UIX-1795661374629256`

## Why

Every UI string that names a saved relay or AmneziaWG entry calls it a server, and every string that names DPI settings calls it a preset, in all ten locales.

## What Changes

- Each remaining English string that contains "profile" is classified as server, preset, or other, and the classification is recorded in the change design.
- Strings classified as server or preset use that term in all ten locales; strings classified as other keep their wording.
- `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` and `:core:service:lintDebug` pass.
- Roborazzi baselines that change because of the new wording are re-recorded under the golden bless rules.

## Capabilities

### New Capabilities

- `uix-1795661374629256-rename-remaining-profile-strings`: Use the server term in all remaining strings

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
