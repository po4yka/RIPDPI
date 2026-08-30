## Purpose

Define the observable completion contract for a per-exit-IP TLS cap with true mux preference in the relay-core backend. The shared `ripdpi-session-limit` primitive provides independent counters for proxy-runtime's direct path and relay-core's physical VLESS+Reality carriers.

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917184-001 — Cap physical Reality TLS carriers per exit IP

The relay-core VLESS+Reality path MUST cap concurrent physical TLS carriers by the resolved foreign-exit IP and transport. Logical streams opened inside an existing mux carrier MUST NOT consume additional slots. The default cap for `vless_reality` on port 443 MUST be 8, and the shared policy MUST support per-transport overrides.

#### Scenario: Non-mux carrier admission reaches the cap

- **WHEN** eight non-mux VLESS+Reality port-443 carriers to one resolved exit IP are concurrently alive
- **THEN** a ninth physical TCP/TLS carrier MUST NOT be opened
- **AND** releasing one carrier MUST allow the next carrier to be admitted

### Requirement: REQ-TRN-1786264762917184-002 — Prefer an existing compatible mux carrier

For a mux-enabled VLESS+Reality backend, relay-core MUST open logical streams through `RelayMux::open_stream` and reuse the cached compatible carrier before attempting to create another physical carrier.

#### Scenario: Ninth logical stream reuses the mux carrier

- **WHEN** nine logical streams are concurrently opened through a mux-enabled VLESS+Reality backend
- **THEN** all nine streams MUST use the same physical TCP/TLS carrier
- **AND** the carrier slot count MUST remain one

### Requirement: REQ-TRN-1786264762917184-003 — Share policy without cross-path double-counting

The proxy-runtime direct-path gate and relay-core foreign-exit path MUST consume one shared limiter implementation but MUST own separate counter instances. A physical carrier MUST hold exactly one slot for its lifetime, and logical mux streams MUST NOT be counted as carriers.

#### Scenario: Independent direct and relay accounting

- **WHEN** direct-path and relay-path limiters observe the same IP and transport token
- **THEN** acquiring a direct-path slot MUST NOT change the relay-path count
- **AND** one relay carrier MUST increment the relay-path count exactly once until that carrier is dropped

### Requirement: REQ-TRN-1786264762917184-004 — cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clip…

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path).

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path)
