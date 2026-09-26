## ADDED Requirements

### Requirement: REQ-BOOT-BATTERY-SETTINGS — Guide without direct exemption

After the user enables Start on boot, the app MUST offer supported battery
optimization settings without invoking a direct exemption request that needs
an undeclared permission.

#### Scenario: Battery optimization remains active

- **GIVEN** the user enables Start on boot and battery optimization is active
- **WHEN** the app chooses an exemption guidance activity
- **THEN** it tries vendor autostart settings and general battery optimization settings before app details
- **AND** it does not request direct exemption
