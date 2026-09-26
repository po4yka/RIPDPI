## Purpose

Define admission behavior while an automatic scan starts a DNS-corrected re-probe.

## ADDED Requirements

### Requirement: REQ-DGN-1790433094681403-001 — Reserve admission across re-probe handoff

The implementation MUST keep an active scan admission slot from primary completion until the DNS-corrected re-probe has reserved its hidden slot or the attempt has failed and cleaned up.

#### Scenario: Re-probe preparation suspends

- **WHEN** DNS-corrected re-probe preparation suspends after the primary report completes
- **THEN** manual and automatic starts still observe an active scan and cannot start concurrently

#### Scenario: Re-probe registration succeeds

- **WHEN** the hidden re-probe bridge is registered
- **THEN** the primary bridge is cleaned before the re-probe starts and the hidden slot remains active

#### Scenario: Preparation or registration fails

- **WHEN** re-probe startup fails or is canceled
- **THEN** the primary bridge is cleaned and no stale admission slot remains
