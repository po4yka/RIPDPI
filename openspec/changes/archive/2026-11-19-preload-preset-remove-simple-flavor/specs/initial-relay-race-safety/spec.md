## ADDED Requirements

### Requirement: REQ-RACE-PRODUCT-SCOPE — Describe the connected product scope without a flavor

The specifications MUST describe the core one-or-two-candidate API and the single selected profile that the product starts. They MUST NOT promise an unbound winner cache or product race.

#### Scenario: The application starts its selected profile

- **WHEN** the application starts a selected relay profile, whether imported or preloaded
- **THEN** its contract describes one configured candidate and explicit inconclusive verification without promising two concurrent product candidates

## REMOVED Requirements

### Requirement: REQ-RACE-SCOPE — Describe the connected product scope

**Reason**: The requirement and its only scenario name the Simple flavor's single-profile preflight, which this change deletes.

**Migration**: `REQ-RACE-PRODUCT-SCOPE` carries the same scope limits for the single application.
