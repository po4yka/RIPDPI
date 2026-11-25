## Purpose

Define the observable completion contract for Keep the working tunnel when a new server fails to start. A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## ADDED Requirements

### Requirement: REQ-SVC-1795615848338894-001 — When a server switch fails, the service restores the previous working mode, or…

The RIPDPI implementation MUST satisfy this portfolio criterion: When a server switch fails, the service restores the previous working mode, or the UI states that the device is now unprotected and offers to restore it.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that when a server switch fails, the service restores the previous working mode, or the UI states that the device is now unprotected and offers to restore it

### Requirement: REQ-SVC-1795615848338894-002 — An AmneziaWG readiness timeout shows a user message that names the likely cause…

The RIPDPI implementation MUST satisfy this portfolio criterion: An AmneziaWG readiness timeout shows a user message that names the likely causes (endpoint, keys, port) and a next step; the raw state stays in diagnostics only.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that an AmneziaWG readiness timeout shows a user message that names the likely causes (endpoint, keys, port) and a next step; the raw state stays in diagnostics only

### Requirement: REQ-SVC-1795615848338894-003 — A start failure while the app is in the background posts a notification

The RIPDPI implementation MUST satisfy this portfolio criterion: A start failure while the app is in the background posts a notification.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a start failure while the app is in the background posts a notification

### Requirement: REQ-SVC-1795615848338894-004 — Service lifecycle tests cover a failed switch from a running tunnel

The RIPDPI implementation MUST satisfy this portfolio criterion: Service lifecycle tests cover a failed switch from a running tunnel.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that service lifecycle tests cover a failed switch from a running tunnel
