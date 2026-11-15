## Purpose

Keep UDP flow ownership and raw packet sidecars safe across their supported callers.

## ADDED Requirements

### Requirement: REQ-DPI34-ISOLATION — isolate socket ownership

Each outgoing UDP tuple MUST have one owner. Responses MUST return to that owner. A source-port rebind MUST use a different local tuple.

#### Scenario: isolate socket ownership

- **WHEN** two flows share a remote endpoint
- **THEN** each outgoing tuple is exclusive and responses return to the owner; rebind uses a different tuple.

### Requirement: REQ-DPI34-REBIND — gate heuristic rebind

A generic UDP flow MUST keep its local tuple. A heuristic source-port rebind MUST require a positive QUIC response context.

#### Scenario: gate heuristic rebind

- **WHEN** ordinary UDP receives two replies
- **THEN** the tuple stays stable; only a positive QUIC context permits heuristic rebind.

### Requirement: REQ-DPI34-DELTA — validate sidecar delta

Zero delta MUST send no sidecar. Negative YAML, TOML or Lua delta MUST fail before emission. A positive delta MUST advertise more UDP bytes than the packet carries.

#### Scenario: validate sidecar delta

- **WHEN** zero or negative delta is supplied
- **THEN** zero sends no packet and negative YAML or Lua input returns an error before emission; positive delta exceeds carried UDP bytes.

### Requirement: REQ-DPI34-IPV6 — handle safe IPv6 options

The converter MUST use the correct UDP offset and checksum. It MUST preserve the original packet. Fragment, AH, Routing headers and jumbograms remain unsupported.

#### Scenario: handle safe IPv6 options

- **WHEN** UDP follows safe Hop-by-Hop or Destination Options
- **THEN** the UDP offset and checksum are correct; original bytes remain intact; fragments AH routing and jumbograms stay unsupported.

### Requirement: REQ-DPI34-LOWPORT-DOC — describe low-port range

The documentation MUST state the range 1024 through 4096 and the OS-assigned fallback.

#### Scenario: describe low-port range

- **WHEN** a user reads the low-port contract
- **THEN** it states 1024 through 4096 and OS-assigned fallback.

### Requirement: REQ-DPI34-PATH-DOC — describe path validation

The documentation MUST describe rebind-only and the RFC 9000 roles of endpoints.

#### Scenario: describe path validation

- **WHEN** a reader evaluates rebind
- **THEN** the text reports rebind-only and correct RFC 9000 endpoint roles.
