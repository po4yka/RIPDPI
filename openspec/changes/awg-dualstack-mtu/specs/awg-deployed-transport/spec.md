## Purpose

Admit a deployment-provided AWG transport without silently replacing its address families or MTU, and distinguish real consumer traffic from other evidence tiers.

## ADDED Requirements

### Requirement: REQ-AWG-TRANSPORT — Admit exact deployed transport

The bundle parser MUST accept exactly two numeric host CIDRs, IPv4 /32 followed by IPv6 /128, and a JSON integer MTU between 1280 and 1420 inclusive. It MUST preserve accepted values through activation and runtime resolution. Malformed or missing values MUST skip the AWG entry with its invalid field identified, without default substitution.

#### Scenario: Shared MTU boundaries

- **WHEN** a valid dual-stack deployment bundle supplies MTU 1280 or 1420
- **THEN** import and activation retain both supplied addresses and that exact MTU.

#### Scenario: Invalid supplied transport

- **WHEN** either address is missing, malformed, has an incorrect prefix or family order, or MTU is absent, non-integer or outside the allowed interval
- **THEN** no AWG profile is imported and skipped-entry evidence identifies address or mtu.

### Requirement: REQ-AWG-ACTIVATION — Reject mismatched native MTU

Activation MUST reject an MTU outside 1280..1420 before native startup. Standalone INI address-family selection MUST remain independent of input ordering.

#### Scenario: Unsafe activation

- **WHEN** a stored or directly supplied request has MTU 1279 or 1421
- **THEN** the service resolver rejects it without starting native work.

### Requirement: REQ-AWG-INTEROP — Measure scoped native traffic

Acceptance MUST exercise real bidirectional IPv4 and IPv6 TCP and UDP payloads against an independently implemented authenticated peer at MTUs 1280 and 1420. Android emulator acceptance MUST identify its ABI and native-core scope, reconnect three times, and MUST NOT claim physical-device or Android VpnService packet-policy acceptance.

#### Scenario: Emulator native-core evidence

- **WHEN** the Android arm64 native runtime exchanges and verifies payloads against the owned peer
- **THEN** acceptance records measured traffic, selected MTU, reconnect count and emulator scope; unavailable prerequisites fail visibly.
