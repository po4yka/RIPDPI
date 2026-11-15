## Purpose

Preserve the observable meaning of supported imported profiles before any durable update or explicit activation.

## ADDED Requirements

### Requirement: REQ-IMP03-F1 — Reject conditional package routes

A package rule with invert or an unsupported predicate MUST fail import without widening it to whole-app routing.

#### Scenario: Reject conditional package routes

- **WHEN** a package route contains invert or a domain condition
- **THEN** the import fails and stored package rules remain unchanged

### Requirement: REQ-IMP03-F2 — Retain Clash Trojan trust requirements

A Clash Trojan certificate pin or other unsupported handshake constraint MUST be retained or rejected before persistence.

#### Scenario: Retain Clash Trojan trust requirements

- **WHEN** a Trojan node includes a certificate fingerprint
- **THEN** the importer rejects the unsupported constraint instead of using ordinary CA trust

### Requirement: REQ-IMP03-F3 — Reject unsupported outbound chains

A supported sing-box node with an unrepresentable detour MUST be rejected.

#### Scenario: Reject unsupported outbound chains

- **WHEN** a Shadowsocks node names another outbound as detour
- **THEN** no direct replacement profile is saved

### Requirement: REQ-IMP03-F4 — Reject unrepresentable Xray Shadowsocks security

A native Shadowsocks translation MUST reject a TLS stream wrapper that the profile cannot retain.

#### Scenario: Reject unrepresentable Xray Shadowsocks security

- **WHEN** an Xray Shadowsocks outbound requires stream TLS
- **THEN** the node has a typed unsupported reason and cannot be activated as plain Shadowsocks

### Requirement: REQ-IMP03-F5 — Reject malformed outbound entries

Every malformed outbound entry MUST cause an atomic subscription parse error.

#### Scenario: Reject malformed outbound entries

- **WHEN** a bundle contains a valid node and a non-object or missing-type entry
- **THEN** the previous working member set remains intact

### Requirement: REQ-IMP03-F6 — Apply complete bootstrap admission

Bootstrap MUST reject partial, skipped, raw, or invalid native node results with a non-secret reason.

#### Scenario: Apply complete bootstrap admission

- **WHEN** a bootstrap payload contains a valid node and an invalid sibling
- **THEN** it does not save a partial selectable member set or report successful import

### Requirement: REQ-IMP03-F7 — Use pinned Xray credential precedence

Supported flat Xray credentials MUST take precedence over legacy servers as in the pinned upstream source.

#### Scenario: Use pinned Xray credential precedence

- **WHEN** flat and legacy credentials name different servers
- **THEN** the translated endpoint uses the flat credentials, or the mixed form is explicitly rejected

### Requirement: REQ-IMP03-F8 — Preserve bootstrap selector membership

Bootstrap MUST save the resolved selector members in declared order and map all member references to those saved identifiers.

#### Scenario: Preserve bootstrap selector membership

- **WHEN** a selector names only one of two concrete nodes
- **THEN** only the selected candidate belongs to the saved selector group

### Requirement: REQ-IMP03-F9 — Validate URL-test policy

URL-test imports MUST apply documented defaults or explicitly reject omitted parameters and MUST reject invalid URL, duration, or tolerance values.

#### Scenario: Validate URL-test policy

- **WHEN** a URL-test omits optional values or supplies out-of-range timing
- **THEN** its policy is valid and bounded or the whole import is rejected

### Requirement: REQ-IMP03-F10 — Save an initial selector preference

The imported selector default MUST survive persistence as an initial preference and MUST NOT start a connection or overwrite an explicit user selection.

#### Scenario: Save an initial selector preference

- **WHEN** a new selector names b as its default and later receives explicit selection a
- **THEN** the initial preference is b and the explicit selection remains a after refresh and reload

### Requirement: REQ-IMP03-F11 — Reject lossy subscription decoding

Subscription payload decoding MUST fail if bytes cannot be decoded without replacement.

#### Scenario: Reject lossy subscription decoding

- **WHEN** a password contains malformed UTF-8 bytes
- **THEN** the payload is rejected and the previous credential is unchanged
