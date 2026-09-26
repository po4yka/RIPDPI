# Legacy network snapshot redaction

## ADDED Requirements

### Requirement: REQ-LEGACY-SNAPSHOT-REDACTION

On upgrade from database version 13, existing network snapshot rows MUST receive the capture-time storage-safe projection. The migration MUST replace Wi-Fi SSID, BSSID, gateway, DHCP server, local IP, subnet mask, and network ID; cellular carrier and operator names, codes, and IDs; DNS and local addresses; and private DNS hostnames. It MUST preserve address counts and non-identifying transport and signal evidence for valid rows. An unreadable payload, a payload missing required version 13 fields, or a payload with an unknown field or invalid field type MUST be removed without deleting unrelated history.

#### Scenario: Existing network snapshot is upgraded

- **GIVEN** a version 13 database with Wi-Fi and cellular snapshots containing raw identifiers and addresses
- **WHEN** Room upgrades the database to version 14
- **THEN** their payloads contain only redacted or coarse values for those fields
- **AND** valid rows retain their session links, timestamps, address counts, and non-identifying evidence

#### Scenario: A malformed stored snapshot is upgraded

- **GIVEN** one malformed network snapshot beside valid snapshots and unrelated history
- **WHEN** Room upgrades the database to version 14
- **THEN** only the malformed snapshot row is removed
- **AND** valid snapshots and unrelated history remain readable
