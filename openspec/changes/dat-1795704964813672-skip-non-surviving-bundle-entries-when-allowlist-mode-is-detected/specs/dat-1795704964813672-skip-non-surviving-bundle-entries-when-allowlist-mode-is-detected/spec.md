## Purpose

Define the observable completion contract for Skip non-surviving bundle entries when allowlist mode is detected. When allowlist mode is detected, failover skips bundle entries whose allowlist-regime survivability label says they do not survive, and treats unlabeled entries as neutral.

## ADDED Requirements

### Requirement: REQ-DAT-1795704964813672-001 — The bundle parser reads the survivability label per entry, and golden tests cov...

The RIPDPI implementation MUST satisfy this portfolio criterion: The bundle parser reads the survivability label per entry, and golden tests cover labeled, unlabeled and malformed values.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the bundle parser reads the survivability label per entry, and golden tests cover labeled, unlabeled and malformed values

### Requirement: REQ-DAT-1795704964813672-002 — In allowlist mode, failover ordering excludes entries labeled as not surviving...

The RIPDPI implementation MUST satisfy this portfolio criterion: In allowlist mode, failover ordering excludes entries labeled as not surviving and keeps unlabeled entries neutral.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that in allowlist mode, failover ordering excludes entries labeled as not surviving and keeps unlabeled entries neutral

### Requirement: REQ-DAT-1795704964813672-003 — Outside allowlist mode, the label does not change ordering.

The RIPDPI implementation MUST satisfy this portfolio criterion: Outside allowlist mode, the label does not change ordering.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that outside allowlist mode, the label does not change ordering

### Requirement: REQ-DAT-1795704964813672-004 — The drift pin matches the vendored schema that carries the label.

The RIPDPI implementation MUST satisfy this portfolio criterion: The drift pin matches the vendored schema that carries the label.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the drift pin matches the vendored schema that carries the label
