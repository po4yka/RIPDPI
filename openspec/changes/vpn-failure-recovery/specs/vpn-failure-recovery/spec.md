## Purpose

Keep Android VPN traffic blocked when connection engines fail, and restore
forwarding through the VPN after a network handover failure.

## ADDED Requirements

### Requirement: REQ-VPN-ENGINE-BARRIER — Honor the engine-failure policy

The VPN MUST apply the engine-failure setting when a running Xray engine or
listener fails or the native proxy unexpectedly returns exit code zero.

#### Scenario: Blocking is enabled

- **WHEN** either exit is reported for the current session with blocking enabled
- **THEN** native forwarding stops and the TUN remains installed as a barrier

#### Scenario: Stop is explicitly selected

- **WHEN** either exit is reported for the current session with stopping selected
- **THEN** the VPN stops and releases its TUN

### Requirement: REQ-VPN-HANDOVER-RECOVERY — Recover on network return

The VPN MUST retry on a new usable network event after it has retained a TUN
as a barrier on handover exhaustion. The retry MUST keep that barrier until
the replacement TUN is established.

#### Scenario: Network returns after retry exhaustion

- **WHEN** a new usable network event arrives for an active retained session
- **THEN** the service replaces forwarding in that session and reports Connected after success

#### Scenario: The new attempt fails or the session ends

- **WHEN** retries fail again or a user stops the retained session
- **THEN** failure keeps the barrier and an explicit stop ends recovery ownership

#### Scenario: Network loss supersedes a replacement in progress

- **WHEN** a newer loss event invalidates a recovery that has begun replacing the bridge
- **THEN** the service quiesces replacement forwarding, retains the installed TUN and waits for a usable network

#### Scenario: A new command supersedes a failed continuation

- **WHEN** a retry attempts to replace the exact prior handover claim
- **THEN** the newer revision can proceed only while the same command owns the claim and its predecessor
- **AND** a newer stop or activation prevents the retry from claiming that authority
