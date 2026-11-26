## Purpose

Define the observable completion contract for Classify mobile allowlist mode as its own diagnostics verdict. Diagnostics reports mobile allowlist mode as its own verdict, separate from a generic outage and from DPI blocking, and narrows the candidate transports for that regime.

## ADDED Requirements

### Requirement: REQ-DGN-1795704952088416-001 — A network where permitted-domain targets are reachable while foreign-address ta...

The RIPDPI implementation MUST satisfy this portfolio criterion: A network where permitted-domain targets are reachable while foreign-address targets and UDP targets fail is classified as allowlist mode, distinct from a generic outage and from a DPI block.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a network where permitted-domain targets are reachable while foreign-address targets and UDP targets fail is classified as allowlist mode, distinct from a generic outage and from a DPI block

### Requirement: REQ-DGN-1795704952088416-002 — A network where every target fails is not classified as allowlist mode.

The RIPDPI implementation MUST satisfy this portfolio criterion: A network where every target fails is not classified as allowlist mode.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a network where every target fails is not classified as allowlist mode

### Requirement: REQ-DGN-1795704952088416-003 — The allowlist verdict narrows the candidate transports to those that can comple...

The RIPDPI implementation MUST satisfy this portfolio criterion: The allowlist verdict narrows the candidate transports to those that can complete standard HTTPS toward a permitted destination, and the narrowing is visible in the diagnostics result.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the allowlist verdict narrows the candidate transports to those that can complete standard HTTPS toward a permitted destination, and the narrowing is visible in the diagnostics result

### Requirement: REQ-DGN-1795704952088416-004 — The verdict, its probe evidence and the narrowing reason appear in history and...

The RIPDPI implementation MUST satisfy this portfolio criterion: The verdict, its probe evidence and the narrowing reason appear in history and redacted exports without addresses of user networks.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the verdict, its probe evidence and the narrowing reason appear in history and redacted exports without addresses of user networks
