## Purpose

Keep local app authentication controls effective when a user changes the device
clock or restarts the device during a timed security interval.

## ADDED Requirements

### Requirement: REQ-APP-LOCK-PIN-MONOTONIC — Preserve PIN lockout duration

The app MUST reject PIN attempts until the configured lockout delay has elapsed
on the active boot, regardless of wall clock changes.

#### Scenario: Clock moves forward during lockout

- **GIVEN** the third invalid PIN triggered a 30-second lockout
- **WHEN** the device wall clock moves forward by one hour after five seconds
- **THEN** the app still rejects PIN attempts for 25 more seconds

#### Scenario: Device restarts during lockout

- **GIVEN** a PIN lockout is active
- **WHEN** the device restarts or boot identity cannot be read
- **THEN** the app retains at least the configured delay before another PIN attempt

#### Scenario: Expiry was observed before restart

- **GIVEN** the app observed that a PIN lockout expired
- **WHEN** the device restarts before the next PIN attempt
- **THEN** the app permits that attempt without a new lockout

### Requirement: REQ-APP-LOCK-RELOCK-MONOTONIC — Preserve background relock

The app MUST relock an authenticated session after the background grace period
without depending on wall time continuity.

#### Scenario: Clock moves backward while app is backgrounded

- **GIVEN** the app is authenticated and enters the background
- **WHEN** over five seconds elapse and the wall clock moves backward
- **THEN** the app requires authentication when it returns to the foreground

#### Scenario: Device restarts while app is backgrounded

- **GIVEN** an authenticated app entered the background
- **WHEN** the device restarts or boot identity cannot be read
- **THEN** the app requires authentication when it returns to the foreground
