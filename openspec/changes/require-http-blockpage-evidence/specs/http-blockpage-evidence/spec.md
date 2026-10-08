## Purpose

Prevent ordinary origin HTTP access refusals from becoming unsupported provider restriction claims.

## ADDED Requirements

### Requirement: REQ-HTTP-EVIDENCE-001 — Require blockpage evidence for HTTP 403

The classifier MUST preserve HTTP 403 as `http_status_403` when no body or fingerprint evidence identifies a blockpage.

#### Scenario: Empty forbidden response

- **WHEN** HTTP 403 has an empty body and no matching fingerprint
- **THEN** the outcome is `http_status_403`.

#### Scenario: Generic HTTP refusal page

- **WHEN** HTTP 403 contains only a generic Forbidden or Access Denied message
- **THEN** the outcome is `http_status_403`.

#### Scenario: Positive blockpage evidence

- **WHEN** a specific blocking message or known fingerprint identifies a blockpage
- **THEN** the outcome remains `http_blockpage`.

#### Scenario: Existing protocol and privacy contracts

- **WHEN** classification runs
- **THEN** it uses existing status tokens and performs no new logging, storage, or external request.
