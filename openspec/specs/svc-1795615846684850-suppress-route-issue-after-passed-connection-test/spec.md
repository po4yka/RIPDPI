# svc-1795615846684850-suppress-route-issue-after-passed-connection-test Specification

## Purpose

Define the observable completion contract for Stop showing Route issue right after a passed connection test. A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## Requirements

### Requirement: REQ-SVC-1795615846684850-001 — A regression test feeds start-up connection refusals like the observed ones and…

The RIPDPI implementation MUST satisfy this portfolio criterion: A regression test feeds start-up connection refusals like the observed ones and the route health stays healthy.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a regression test feeds start-up connection refusals like the observed ones and the route health stays healthy

### Requirement: REQ-SVC-1795615846684850-002 — A persistent route fault still shows Route issue within the existing detection…

The RIPDPI implementation MUST satisfy this portfolio criterion: A persistent route fault still shows Route issue within the existing detection window.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a persistent route fault still shows Route issue within the existing detection window

### Requirement: REQ-SVC-1795615846684850-003 — On a device, finishing onboarding with a passed test shows Connected without Ro…

The RIPDPI implementation MUST satisfy this portfolio criterion: On a device, finishing onboarding with a passed test shows Connected without Route issue.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that on a device, finishing onboarding with a passed test shows Connected without Route issue
