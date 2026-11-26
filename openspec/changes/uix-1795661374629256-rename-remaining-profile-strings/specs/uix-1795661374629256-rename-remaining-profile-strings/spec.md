## Purpose

Define the observable completion contract for Use the server term in all remaining strings. Every UI string that names a saved relay or AmneziaWG entry calls it a server, and every string that names DPI settings calls it a preset, in all ten locales.

## ADDED Requirements

### Requirement: REQ-UIX-1795661374629256-001 — Each remaining English string that contains "profile" is classified as server, …

The RIPDPI implementation MUST satisfy this portfolio criterion: Each remaining English string that contains "profile" is classified as server, preset, or other, and the classification is recorded in the change design.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that each remaining English string that contains "profile" is classified as server, preset, or other, and the classification is recorded in the change design

### Requirement: REQ-UIX-1795661374629256-002 — Strings classified as server or preset use that term in all ten locales; string…

The RIPDPI implementation MUST satisfy this portfolio criterion: Strings classified as server or preset use that term in all ten locales; strings classified as other keep their wording.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that strings classified as server or preset use that term in all ten locales; strings classified as other keep their wording

### Requirement: REQ-UIX-1795661374629256-003 — `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` a…

The RIPDPI implementation MUST satisfy this portfolio criterion: `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` and `:core:service:lintDebug` pass.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` and `:core:service:lintDebug` pass

### Requirement: REQ-UIX-1795661374629256-004 — Roborazzi baselines that change because of the new wording are re-recorded unde…

The RIPDPI implementation MUST satisfy this portfolio criterion: Roborazzi baselines that change because of the new wording are re-recorded under the golden bless rules.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that roborazzi baselines that change because of the new wording are re-recorded under the golden bless rules
