# awg-network-policy-import Specification

## Purpose
Preserve the network policy that the user imports from WireGuard and AWG subscriptions through the activation request and saved profile.

## Requirements

### Requirement: REQ-AWG-POLICY-MAP — Preserve explicit network policy

The application MUST copy supplied DNS servers and AllowedIPs into the activation request without replacing them with defaults. It MUST select IPv4 and IPv6 interface addresses by family.

#### Scenario: Dual-stack import

- **WHEN** a profile supplies IPv6 before IPv4, two DNS families, and limited IPv4/IPv6 routes
- **THEN** the activation request retains both address families, DNS values, and routes

### Requirement: REQ-AWG-POLICY-REFRESH — Respect field presence on refresh

The application MUST apply supplied DNS and route lists to the existing subscription profile and MUST preserve saved policy when the corresponding field is absent.

#### Scenario: Server changes policy

- **WHEN** a refresh supplies new DNS servers or routes
- **THEN** the same saved profile receives the supplied values without changing its local private key

#### Scenario: Server omits policy

- **WHEN** a refresh omits DNS or AllowedIPs
- **THEN** the saved value for that field remains unchanged

### Requirement: REQ-AWG-POLICY-EMPTY — Do not broaden empty routes

The application MUST distinguish omitted fields from explicit empty lists. Empty DNS SHALL select the existing app DNS fallback. Empty AllowedIPs SHALL remain invalid for activation and MUST NOT become a default route.

#### Scenario: Explicit empty policy

- **WHEN** a profile contains explicit empty DNS and AllowedIPs lists
- **THEN** the saved DNS override is cleared and runtime validation rejects the empty route list

#### Scenario: First import without policy

- **WHEN** a new profile omits both fields
- **THEN** the established app DNS fallback and IPv4 default route remain the defaults

### Requirement: REQ-AWG-POLICY-FAMILY — Derive safe effective interface policy

The application MUST retain all valid source CIDRs and MUST require at least one route for a configured interface family. It MUST install only routes for configured families. Effective profile or fallback DNS MUST belong to a configured family and MUST be validated before VPN establishment.

#### Scenario: Server IPv4 profile contains both default routes

- **WHEN** the unchanged CI bundle supplies an IPv4 interface address and IPv4/IPv6 default routes
- **THEN** Simple seeding succeeds, storage retains both routes, and the Android route plan contains no IPv6 route or DNS server

#### Scenario: No effective route

- **WHEN** a profile has only IPv6 routes and no IPv6 interface address
- **THEN** runtime readiness rejects the profile

#### Scenario: Unsupported fallback DNS family

- **WHEN** profile DNS is empty and fallback DNS is IPv6 without an IPv6 interface address
- **THEN** the interface policy fails before VPN establishment

#### Scenario: Malformed supplied list

- **WHEN** DNS or AllowedIPs is present but is not a JSON array of nonblank strings
- **THEN** the AWG entry is rejected with a typed warning containing only the field name, and saved profile policy remains unchanged
