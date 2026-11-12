## Purpose

Bound native peer startup after carrier setup so that a blocked initial write cannot prevent a stop or exceed the peer startup deadline.

## ADDED Requirements

### Requirement: REQ-AWG-STARTUP-STOP — Stop interrupts the first write

The runtime MUST observe Stop while the initial initiation write is pending, then close the tunnel and join its owned tasks without publishing readiness.

#### Scenario: Stop during a pending initial write

- **WHEN** a connected carrier cannot complete its first initiation write and Stop is requested
- **THEN** startup completes without readiness and no carrier task remains active

### Requirement: REQ-AWG-STARTUP-DEADLINE — One peer startup deadline

The runtime MUST apply the peer startup deadline to both the initial initiation write and the authenticated peer response.

#### Scenario: Initial write remains pending

- **WHEN** the first write cannot complete before the peer startup deadline
- **THEN** startup returns TimedOut and closes its owned tasks
