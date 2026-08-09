## Purpose

Define the fail-secure public configuration, signature verification, refresh, and release-evidence contract for activating shared priors in production builds.

## ADDED Requirements

### Requirement: REQ-SHARED-PRIORS-PRODUCTION-IDENTITY — Production releases use an approved verification identity

The implementation MUST embed exactly one owner-approved Ed25519 public verification key and non-empty HTTPS manifest and priors locations in production artifacts, while keeping the signing key outside the repository and artifact.

#### Scenario: Production artifact contains approved public configuration

- **GIVEN** an exact release artifact and its source commit
- **WHEN** the release configuration is inspected
- **THEN** the embedded public key and URLs match the owner-approved release receipt and no private signing material is present

### Requirement: REQ-SHARED-PRIORS-FAIL-CLOSED — Invalid configuration and content fail closed

The implementation MUST reject missing or all-zero production keys, non-HTTPS or malformed locations, invalid signatures, unsupported manifest versions, hash mismatches, oversized payloads, and malformed records without replacing the last accepted prior store.

#### Scenario: Tampered payload is rejected

- **GIVEN** a previously accepted shared-priors store
- **WHEN** the downloaded payload differs from the signed manifest hash
- **THEN** verification fails and the previously accepted store remains active

#### Scenario: Release configuration is absent

- **GIVEN** a build without the approved production configuration
- **WHEN** periodic refresh is initialized
- **THEN** refresh remains disabled or reports a typed blocked state and no unsigned fallback is attempted

### Requirement: REQ-SHARED-PRIORS-APPLY — Valid signed releases are applied atomically

The implementation MUST accept a compatible manifest and payload signed by the embedded public key, apply the complete validated prior set atomically, and expose an observable successful refresh result.

#### Scenario: Matching signed release is accepted

- **GIVEN** a compatible manifest and payload signed by the approved release key
- **WHEN** the Android refresh path downloads and submits them to native verification
- **THEN** the validated priors become the current store and success is observable without restarting the VPN process

### Requirement: REQ-SHARED-PRIORS-PRIVACY — Activation does not create an upload path

The implementation MUST keep release consumption download-only and MUST NOT transmit user diagnostics, learned priors, identifiers, or network observations as part of refresh.

#### Scenario: Periodic refresh runs

- **GIVEN** shared-priors consumption is enabled in a production build
- **WHEN** the scheduled refresh executes
- **THEN** network requests contain only the configured catalog requests and no device-derived payload

### Requirement: REQ-SHARED-PRIORS-EVIDENCE — Release readiness is tied to the exact artifact

The release process MUST record local verification, hosted CI, artifact inspection, and owner deployment evidence against one exact commit SHA before the capability is considered complete.

#### Scenario: Evidence is incomplete

- **GIVEN** the implementation passes local tests but the signed release payload or exact artifact has not been verified
- **WHEN** completion is evaluated
- **THEN** the task remains blocked or in review and the change cannot be archived as complete
