## Purpose

Define the observable completion contract for Keep universal DNS and ECH check failures out of block verdicts. The ECH and DNS survey and the CDN ECH updater do not treat a failure of every measurement as a signal of blocking.

## ADDED Requirements

### Requirement: REQ-DNS-1795705017215412-001 — A survey outcome where every resolver or measurement fails is classified as uni...

The RIPDPI implementation MUST satisfy this portfolio criterion: A survey outcome where every resolver or measurement fails is classified as universal failure, separate from a block signal.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a survey outcome where every resolver or measurement fails is classified as universal failure, separate from a block signal

### Requirement: REQ-DNS-1795705017215412-002 — The ECH updater does not mark a config as blocked, downgrade it or suppress it...

The RIPDPI implementation MUST satisfy this portfolio criterion: The ECH updater does not mark a config as blocked, downgrade it or suppress it when every measurement fails.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the ECH updater does not mark a config as blocked, downgrade it or suppress it when every measurement fails

### Requirement: REQ-DNS-1795705017215412-003 — Regression tests cover a universal failure and a partial failure, and only the...

The RIPDPI implementation MUST satisfy this portfolio criterion: Regression tests cover a universal failure and a partial failure, and only the partial failure can produce a block signal.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that regression tests cover a universal failure and a partial failure, and only the partial failure can produce a block signal

### Requirement: REQ-DNS-1795705017215412-004 — History and redacted exports show the universal-failure class.

The RIPDPI implementation MUST satisfy this portfolio criterion: History and redacted exports show the universal-failure class.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that history and redacted exports show the universal-failure class
