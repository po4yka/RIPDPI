## Purpose

Keep the shared-priors release order across process restarts so a replayed, correctly signed old bundle cannot replace a newer accepted bundle.

## ADDED Requirements

### Requirement: REQ-SHARED-PRIORS-ORDER — Enforce signed release order

The native global apply path MUST reject a verified bundle with an issuance timestamp older than the last accepted bundle. It MUST reject a different payload at the same timestamp and MAY reapply the identical bundle.

#### Scenario: Older signed release

- **GIVEN** a newer signed bundle has been accepted
- **WHEN** an older correctly signed bundle is submitted
- **THEN** the native registry and release marker remain unchanged

#### Scenario: Equal issuance timestamp

- **GIVEN** a signed bundle has been accepted
- **WHEN** another signed bundle has the same timestamp and a different payload hash
- **THEN** the native registry and release marker remain unchanged

### Requirement: REQ-SHARED-PRIORS-DURABLE — Persist before publication

The native global apply path MUST durably store the verified release timestamp and payload hash before replacing the process registry. A failed or invalid marker read or write MUST leave the existing registry unchanged.

#### Scenario: Process restart

- **GIVEN** a signed bundle was accepted and the app process ended
- **WHEN** a fresh process receives an older signed bundle
- **THEN** the bundle is rejected using the saved marker

#### Scenario: Marker write failure

- **GIVEN** a valid newer bundle and an unwritable marker path
- **WHEN** native apply is called
- **THEN** apply fails and the registry does not change

### Requirement: REQ-SHARED-PRIORS-ANDROID — Use private local storage

The Android refresh worker MUST provide an app-private, non-backed-up marker path to native apply and MUST update its refresh-success cache only after native apply succeeds. No release marker or bundle data may be sent to a backend by this change.

#### Scenario: Native rejection

- **WHEN** the native verifier rejects a downloaded bundle
- **THEN** the worker does not mark its manifest as successfully applied

### Requirement: REQ-SHARED-PRIORS-COMPAT — Bootstrap existing installations

An installation with no release marker MUST accept its first valid signed bundle. An unreadable or malformed existing marker MUST fail closed; the implementation MUST NOT silently reset the release order.

#### Scenario: No marker after upgrade

- **GIVEN** an existing installation has no release marker
- **WHEN** its first valid signed bundle is applied
- **THEN** the marker is created and the bundle is published
