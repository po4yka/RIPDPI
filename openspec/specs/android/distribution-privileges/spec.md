# android/distribution-privileges Specification

## Purpose

Define which Android distributions include superuser features and make their build artifacts verifiable.

## Requirements

### Requirement: REQ-DISTRIBUTION-ARTIFACT — Package only supported capabilities

Google Play builds MUST exclude Android superuser code, requests, checks, assets, and application-originated text. F-Droid and GitHub builds MUST include these features behind explicit activation.

#### Scenario: Build all three channels

- **WHEN** the three release variants are built in the same checkout
- **THEN** the Play bundle excludes privileged code and content, and both extended APKs contain the expected files and entry points

### Requirement: REQ-DISTRIBUTION-RUNTIME — Enforce Play capability rules

The Play runtime MUST exclude privileged packet operations, process launches, and probes, including when the device has elevated access or the app receives an old privileged configuration.

#### Scenario: Open an old configuration

- **WHEN** a Play user restores an old configuration or upgrades with a previously selected privileged mode
- **THEN** the resulting supported configuration starts without privileged operations or disclosures

#### Scenario: Use PCAP in Play

- **WHEN** the user requests packet capture with separate consent and an active VPN
- **THEN** the app records packets and lets the user view them after disconnect

#### Scenario: Check superuser access in an extended build

- **WHEN** the user enables the privileged mode and the access check does not grant permission
- **THEN** the mode stays off, its controls stay disabled, and ordinary runtime startup does not request superuser access again
- **AND** an upgrade does not treat the old stored opt-in as a successful access check

### Requirement: REQ-DISTRIBUTION-RELEASE — Verify release package content

Release CI MUST verify the selected Play bundle and APK splits and prevent release promotion if privileged content is present.

#### Scenario: Invalid Play bundle

- **WHEN** the bundle includes an extended executable, resource, class, or native privilege capability
- **THEN** the release gate fails before promotion
