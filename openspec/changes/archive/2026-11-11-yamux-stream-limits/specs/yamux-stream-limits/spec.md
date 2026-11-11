## Purpose

Prevent stream-limit input from causing a yamux configuration panic while retaining bounded receive memory and closing-stream capacity.

## ADDED Requirements

### Requirement: REQ-YAMUX-LIMIT — Bound working streams before connection

The client MUST accept zero as the default of 256 working streams and values 1 through 2048. It MUST reject larger working limits before opening a carrier. It MUST retain twice the working limit as session capacity without integer overflow.

#### Scenario: Boundary profile admission

- **WHEN** a profile sets 0, 1, or 2048 working streams
- **THEN** the client accepts it and uses session capacity 512, 2, or 4096 respectively

#### Scenario: Oversized profile

- **WHEN** a profile sets 2049 or u32::MAX working streams
- **THEN** admission returns an input error without opening a socket or changing the receive budget

### Requirement: REQ-YAMUX-CONSTRUCTOR — Reject invalid direct configuration

The client MUST return InvalidInput instead of panicking for invalid directly constructed limits, including zero session capacity and usize::MAX. Valid session capacity MUST fit the existing 1 GiB receive budget at the upstream initial credit.

#### Scenario: Direct constructor guard

- **WHEN** session construction receives 0, 4097, or usize::MAX capacity
- **THEN** it returns InvalidInput before writing a preamble and releases the owned carrier

#### Scenario: Largest supported session

- **WHEN** session construction receives capacity 4096
- **THEN** it creates a session without a configuration panic
