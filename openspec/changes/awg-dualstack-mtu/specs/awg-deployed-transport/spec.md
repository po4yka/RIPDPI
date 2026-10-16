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

Acceptance MUST verify real IPv4/IPv6 TCP, UDP and DNS against an independent authenticated peer at MTUs 1280 and 1420. Android evidence MUST use arm64 emulator/native_core scope and include an initial session plus three reconnects per MTU. Physical-device and Android TUN/no-leak checks MUST NOT gate this change, and unmeasured capabilities MUST NOT be claimed. The producer owns Linux forwarding/no-leak verification.

#### Scenario: Emulator native-core evidence

- **WHEN** the Android arm64 native runtime exchanges and verifies payloads against the owned peer
- **THEN** acceptance records measured traffic, selected MTU and emulator scope for cycle 0 baseline plus native session restarts 1 through 3 at each MTU, retaining one peer and endpoint across those four sessions; unavailable prerequisites fail visibly.

### Requirement: REQ-AWG-FEDERATION — Resolve the qualified consumer natively

The client task CLI MUST export its validated native graph using the shared federation envelope. Unassessed risk MUST remain null, native records and receipt hashes MUST remain unchanged, and all exported references MUST be qualified. Export MUST refuse uncommitted tasking inputs. Historical resolution MUST use native committed lifecycle and receipt validation and include the terminal and deletion revisions; missing or invalid proof MUST fail.

#### Scenario: Producer resolves the consumer task

- **WHEN** the producer requests the qualified AWG consumer task through the client-owned export
- **THEN** the response identifies the committed client revision, exact shared contract digest, validated status/progress and native task identity.

#### Scenario: Invalid historical proof

- **WHEN** a requested purged task has a tampered receipt or lacks a committed review-to-terminal transition
- **THEN** export fails without fabricating a historical terminal node.
