## Purpose

A build can carry a preset so that a recipient installs the full application and finds servers, routing rules, and settings already in place, without an import step and without a separate reduced application.

## ADDED Requirements

### Requirement: REQ-PRESET-APPLY — Apply a packaged preset on first launch

When a build packages a preset and the install is fresh, the application MUST restore the preset's groups, profiles, credentials, routing rules, and settings before startup reports ready. The first screen the user sees MUST reflect the restored state without a manual import and without a user-visible restart.

#### Scenario: Fresh install of a build with a preset

- **WHEN** a build that packages a valid preset is installed and launched for the first time
- **THEN** the first screen shows the preset's server groups and the preset's settings are in effect

#### Scenario: Preset that marks onboarding complete

- **WHEN** the preset's settings record onboarding as complete
- **THEN** the first launch opens the home destination, not onboarding

### Requirement: REQ-PRESET-ONCE — Never re-apply a preset or overwrite user data

The application MUST apply a preset at most once per install. It MUST NOT apply a preset to an install that already completed onboarding. After a user-initiated full reset the install counts as fresh again.

#### Scenario: User edits survive a relaunch

- **WHEN** the user deletes a preloaded profile and relaunches the application
- **THEN** the profile stays deleted

#### Scenario: Application update with a different preset

- **WHEN** an install that already applied a preset is updated to a build that packages a different preset
- **THEN** the stored groups, profiles, rules, and settings are unchanged

#### Scenario: Existing install updated to a preset build

- **WHEN** an install that completed onboarding without a preset is updated to a build that packages a preset
- **THEN** the stored groups, profiles, rules, and settings are unchanged

#### Scenario: Full reset on a preset build

- **WHEN** the user resets the application on a build that packages a preset
- **THEN** the next launch applies the preset again

### Requirement: REQ-PRESET-ABSENT — Behave as the standard application without a preset

A build MUST NOT require a preset. Without one, startup, onboarding, and every screen MUST behave as they do in the current full experience.

#### Scenario: Build without a preset

- **WHEN** a build that packages no preset is installed and launched
- **THEN** the application opens onboarding with no preloaded groups

#### Scenario: Pull-request build without secrets

- **WHEN** CI builds and tests a variant with no preset secret available
- **THEN** the build and its tests pass without a substitute fixture

### Requirement: REQ-PRESET-INVALID — Reject an unusable preset without partial state

When the packaged preset is encrypted, malformed, or of an unsupported backup version, the application MUST write none of it, MUST record a startup warning, and MUST continue as a build without a preset. A later build with a usable preset MUST still apply it to that install while onboarding is incomplete.

#### Scenario: Preset of an unsupported version

- **WHEN** the packaged preset has a backup schema version the application does not support
- **THEN** no group, profile, rule, or setting from it is stored and the application opens onboarding

#### Scenario: Interrupted first launch

- **WHEN** the process dies while the preset is being applied and the application is launched again
- **THEN** the stored state is either the complete preset or none of it

### Requirement: REQ-PRESET-CONFIDENTIAL — Keep preset contents out of source control and logs

The preset asset path MUST be excluded from source control. The application and the build MUST NOT write preset credentials, endpoints, or subscription URLs to logs, startup reports, or diagnostics exports.

#### Scenario: Preset placed in a checkout

- **WHEN** an operator writes a preset to the asset path in a checkout
- **THEN** `git status` does not list the file

#### Scenario: Startup log after a preset is applied

- **WHEN** a preset is applied or rejected
- **THEN** the log records only the outcome and item counts

### Requirement: REQ-PRESET-PREPARE — Convert a FULL export into a preset with one command

The repository MUST provide one command that turns an encrypted FULL backup export into the plaintext preset asset. The command MUST fail, and MUST NOT write the asset, when the passphrase is wrong, the archive is not a FULL export with credentials, or the backup version is unsupported.

#### Scenario: Valid FULL export

- **WHEN** the operator runs the command with a FULL export and its passphrase
- **THEN** the preset asset exists and a build packages it

#### Scenario: SHARE export

- **WHEN** the operator runs the command with a SHARE export
- **THEN** the command fails and names the missing credentials

### Requirement: REQ-PRESET-SINGLE-EXPERIENCE — Ship one application per distribution channel

Each distribution channel MUST produce exactly one application variant with the standard application ID and name. No variant MUST carry the `.simple` application ID suffix or the Quick Connect name.

#### Scenario: Release identity manifest

- **WHEN** the release identity manifest is generated
- **THEN** it lists exactly one release variant for each of `play`, `fdroid`, and `github`, and no application ID ends in `.simple`

#### Scenario: Import and navigation entry points

- **WHEN** any built variant receives a supported import link or navigation deep link
- **THEN** it handles the link as the full experience does today
