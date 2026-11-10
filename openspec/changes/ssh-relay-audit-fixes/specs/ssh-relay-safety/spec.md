## Purpose

Keep SSH relay trust decisions, credentials, address attempts, and owned connection work consistent from profile admission through connection shutdown.

## ADDED Requirements

### Requirement: REQ-SSH-REKEY-PIN — Check the pin at rekey

The implementation MUST apply the configured raw host-key pin to every key exchange, including rekey, before it resumes payload transfer.

#### Scenario: Server changes its key

- **GIVEN** a connection accepted key A under Strict or pinned TOFU
- **WHEN** the server presents key B during rekey
- **THEN** the connection fails without accepting B or resuming payload transfer

#### Scenario: Server retains its key

- **WHEN** the server rekeys with the accepted key
- **THEN** the connection preserves ordered payload transfer

### Requirement: REQ-SSH-IMPORT-TRUST — Admit only usable trusted profiles

The implementation MUST reject invalid SSH endpoints, authentication selectors, required credentials, and pins before durable activation. An unpinned imported profile MUST require an explicit user decision through the SSH trust flow before activation.

#### Scenario: Invalid imported profile

- **WHEN** a profile has an invalid port, selector, required secret, or fingerprint
- **THEN** admission fails without changing saved groups or the active profile

#### Scenario: Unpinned profile

- **WHEN** a user imports a valid profile without a pin
- **THEN** the app offers the SSH trust flow and does not activate the profile before explicit acceptance

#### Scenario: User declines the key

- **WHEN** the user declines or dismisses the observed key
- **THEN** the previous active profile remains selected

### Requirement: REQ-SSH-PASSPHRASE — Preserve the passphrase

The implementation MUST preserve every nonempty private-key passphrase, including whitespace, through sharing and import.

#### Scenario: Whitespace passphrase

- **WHEN** a key profile with passphrase consisting of one space is shared and imported
- **THEN** the imported passphrase is exactly one space

### Requirement: REQ-SSH-DISCOVERY-FALLBACK — Bound each address attempt

The implementation MUST reserve time for later resolved addresses within the overall host-key discovery budget after a recoverable transport failure or timeout.

#### Scenario: First address stalls

- **GIVEN** a stalled first address and a working second address
- **WHEN** the app observes the host key
- **THEN** it can return the second address's key within the overall deadline

#### Scenario: Operation is revoked

- **WHEN** cancellation, a changed underlay, or denied protection revokes the observation
- **THEN** no later address attempt starts

### Requirement: REQ-SSH-CLOSE-BACKPRESSURE — Close without channel consumption

The implementation MUST terminate owned SSH connection work after cancellation even when a retained channel contains unread data.

#### Scenario: Retained unread channel

- **GIVEN** a peer has filled the channel's receive queue
- **WHEN** the owner closes while its caller retains the unread channel
- **THEN** the connection worker terminates within the bounded close test deadline without requiring the caller to read or drop the channel
