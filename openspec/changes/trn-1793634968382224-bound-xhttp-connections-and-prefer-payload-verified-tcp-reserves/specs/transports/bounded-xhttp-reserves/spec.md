## Purpose

Preserve delivered XHTTP connection bounds and recover from a failed UDP carrier through verified TCP reserves.

## ADDED Requirements

### Requirement: REQ-XHTTP-POOL — Preserve bounded connection tuning

The client MUST retain an explicit stream-up XHTTP pool bound of 1–4 connections through import, storage and native activation. The client MUST reject unknown tuning, other modes, and nonzero retirement fields before storage. Untuned profiles MUST retain the native default.

#### Scenario: Bounded pool reaches native activation

- **WHEN** a bundle contains stream-up XHTTP with maxConnections 2 and all three retirement fields zero
- **THEN** the stored profile and native configuration retain the bound 2 and concurrent transfer uses at most two TCP carriers

#### Scenario: Invalid tuning refuses

- **WHEN** tuning has unknown keys, bounds outside 1–4, a noninteger value, nonzero retirement, or a mode other than stream-up
- **THEN** import refuses without replacing accepted profiles or printing credentials

### Requirement: REQ-TCP-RESERVE — Prefer verified TCP recovery

After a selected Hysteria2 carrier fails payload verification, the client MUST test available TCP reserves before other UDP reserves. It MUST retain bounded rotation, consecutive recovery thresholds, cooldown and the selected encrypted exit when all candidates fail. Healthy active quality comparisons MUST retain the existing candidate order.

#### Scenario: UDP stalls while TCP transfers

- **WHEN** a selected Hysteria2 member cannot transfer a control payload and an XHTTP reserve repeatedly verifies it
- **THEN** TCP is tested before other UDP candidates and selected only after the configured failure and recovery thresholds

#### Scenario: Every member fails

- **WHEN** neither the selected member nor any reserve verifies a payload
- **THEN** the encrypted selection remains and probes retain bounded backoff
