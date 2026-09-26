## ADDED Requirements

### Requirement: REQ-APP-OPTIONAL-HARDWARE — Keep hardware optional

The app MUST declare camera, autofocus, location, GPS, and network location
features as optional so its permission requests do not imply mandatory
hardware for distribution.

#### Scenario: Device lacks a camera or GPS receiver

- **GIVEN** a supported Android device lacks a camera or GPS receiver
- **WHEN** the app manifest is evaluated for feature requirements
- **THEN** neither missing feature is required by the app manifest
