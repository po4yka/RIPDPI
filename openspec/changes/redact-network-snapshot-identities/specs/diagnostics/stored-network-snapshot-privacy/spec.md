# Stored network snapshot privacy

## ADDED Requirements

### Requirement: REQ-SNAPSHOT-NETWORK-IDENTITIES

The Android diagnostics network metadata provider MUST replace raw Wi-Fi SSID, BSSID, gateway, DHCP server, local IP, subnet mask, and local network ID before a snapshot can be persisted. It MUST replace raw cellular carrier and operator names and codes and remove carrier IDs. It MUST replace DNS and local address values while preserving their counts, and reduce a private DNS hostname to a coarse mode. The snapshot MUST retain non-identifying transport and signal context.

#### Scenario: Wi-Fi snapshot is serialized

- **GIVEN** a Wi-Fi snapshot with raw identifiers, DNS servers, and local addresses
- **WHEN** the provider returns the snapshot for persistence
- **THEN** its serialized form contains no raw identifier or address
- **AND** it retains transport, address counts, and signal context

#### Scenario: Cellular snapshot is serialized

- **GIVEN** a cellular snapshot with operator names, codes, and carrier IDs
- **WHEN** the provider returns the snapshot for persistence
- **THEN** its serialized form contains no operator name, code, or carrier ID
- **AND** it retains network type and signal context
