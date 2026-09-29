## Purpose

Define full root-backend parity against zapret2 ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b and preserve the safe ABI 5 non-root payload contract.

## ADDED Requirements

### Requirement: REQ-LUA-COMPAT-001 — Position helpers

The non-root implementation MUST match pinned ABI 5 position, multiple-position, and range semantics for its original payload. It MUST reject unsupported host-relative alternate blobs and MUST NOT reuse markers across payload types. It MUST reject malformed markers, limit lists to 128 markers, and keep existing RIPDPI marker aliases.

#### Scenario: Signed and repeated markers

- **WHEN** a valid marker list contains absolute offsets, negative end offsets, marker offsets, and duplicates
- **THEN** multi-position results use one-based Lua positions by default and are sorted and unique; optional zero-based results use byte offsets.

#### Scenario: Invalid or unresolved range

- **WHEN** range endpoints are malformed, missing, reversed, or outside the payload
- **THEN** malformed input fails; unresolved endpoints follow pinned strict or expanding range semantics without a socket write.

### Requirement: REQ-LUA-COMPAT-002 — Bundled segmentation

The implementation MUST execute unmodified bundled multisplit and complete tcpseg replacements across MSS boundaries with exact ordered payload coverage. Sequence arithmetic MUST wrap modulo 2^32. Unsupported packet edits MUST still fail before emission.

#### Scenario: More than one MSS

- **WHEN** a bundled segmentation function processes a payload larger than 1460 bytes
- **THEN** it produces ordered Write segments whose concatenation equals the input; default multisplit pos=2 places the first boundary after two bytes.

### Requirement: REQ-LUA-COMPAT-003 — Payload classification and selected function checks

The implementation MUST expose upstream HTTP request/reply and TLS hello payload names without changing the coarse protocol-family API. It MUST validate drop, http_domcase, http_hostcase, http_methodeol, and http_unixeol through the existing non-root socket path and preserve per-flow track state.

#### Scenario: HTTP request rewrite

- **WHEN** an outbound HTTP request passes the bundled function filter
- **THEN** the planner yields the exact expected modified bytes; inbound packets and HTTP replies follow the existing no-action policy.

#### Scenario: Flow state isolation

- **WHEN** two flows call Lua functions and one flow closes
- **THEN** track state persists only within its owner flow and is released with that flow, without payload logging.

### Requirement: REQ-LUA-COMPAT-004 — Complete upstream runtime

The root backend MUST provide all 101 native globals and all six pinned upstream scripts with real packet headers, conntrack state, reassembly/replay, timers, cutoffs, execution-plan cancellation, and verdict flags. No-op substitutes MUST NOT count as compatibility.

#### Scenario: Native and strategy behavior

- **WHEN** pinned upstream ABI tests and packet strategies run through the enabled backend
- **THEN** their return values, packet bytes, scheduling, and state transitions match the pinned upstream runtime.

### Requirement: REQ-LUA-COMPAT-005 — Android root lifecycle and fallback

Activation MUST require root_mode_enabled and usable root/NFQUEUE capability. The service MUST supervise the backend and remove its own queue rules on normal stop and failed start. Unexpected exit MUST preserve connectivity through queue bypass and trigger cleanup. Non-root operation MUST remain available without privilege escalation.

#### Scenario: Failed or interrupted root session

- **WHEN** root is absent, the queue cannot be bound, startup fails, or the process exits during a session
- **THEN** the service reports a defined capability or runtime failure, leaves no active owned queue rule, and permits the existing non-root path.

### Requirement: REQ-LUA-COMPAT-006 — Reproducible native delivery

The build MUST pin upstream and dependency source, retain license notices and corresponding source references, produce executable artifacts for all four supported Android ABIs, and verify 16 KiB load alignment. The payload sandbox MUST retain its jail and resource limits.

#### Scenario: Android artifact and security checks

- **WHEN** the affected native and Android gates run
- **THEN** artifacts contain the pinned backend/scripts and notices, all ABI binaries meet alignment requirements, and non-root sandbox regression tests pass.
