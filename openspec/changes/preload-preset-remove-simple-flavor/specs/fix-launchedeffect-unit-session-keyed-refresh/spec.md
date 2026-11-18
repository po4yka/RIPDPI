## RENAMED Requirements

- FROM: `### Requirement: REQ-UIX-1786264762917972-004 — /gradlew :app:testGithubFullDebugUnitTest --locked green; goldens unchanged`
- TO: `### Requirement: REQ-UIX-1786264762917972-004 — /gradlew :app:testGithubDebugUnitTest --locked green; goldens unchanged`

## MODIFIED Requirements

### Requirement: REQ-UIX-1786264762917972-004 — /gradlew :app:testGithubDebugUnitTest --locked green; goldens unchanged

The RIPDPI implementation MUST satisfy this portfolio criterion: /gradlew :app:testGithubDebugUnitTest --locked green; goldens unchanged.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that /gradlew :app:testGithubDebugUnitTest --locked green; goldens unchanged
