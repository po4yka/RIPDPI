## Purpose

Keep WARP WireGuard profiles, peer readiness, account state, and endpoint
selection consistent from explicit setup through shutdown.

## ADDED Requirements

### Requirement: REQ-WARP-F1 — Authenticate before readiness

The runtime MUST require an authenticated WireGuard response before readiness. Startup timeout and later peer expiry MUST reach health and failure without losing cleanup ownership.

#### Scenario: Authenticate before readiness

- **WHEN** a silent, wrong, or valid local peer is selected
- **THEN** only a valid authenticated peer permits readiness; timeout fails and stop releases tasks.

#### Scenario: Authenticate through address-family fallback

- **WHEN** the IPv4 primary is silent and the configured IPv6 peer can authenticate
- **THEN** bounded startup permits the backend retry and publishes readiness only after that peer authenticates.

### Requirement: REQ-WARP-F2 — Carry explicit socket protection policy

Runtime and probe callers MUST carry captured inactive or VPN-required policy. Inactive operation MUST work without a callback; required operation MUST fail closed on missing or rejected protection.

#### Scenario: Carry explicit socket protection policy

- **WHEN** the same target is used with each policy
- **THEN** inactive needs no callback and required cannot bypass missing or failed protection.

#### Scenario: Preserve the original probe JNI ABI

- **WHEN** the original one-String native probe entrypoint is called
- **THEN** its signature remains unchanged and it returns null without network I/O; current guarded callers use a separate entrypoint.

### Requirement: REQ-WARP-F3 — Separate endpoint selection from account refresh

Automatic selection MUST use captured network scope and frozen credentials and persist only through matching profile revision. Enabled scanning MUST probe stale endpoints. Disabled scanning MUST NOT start probes; the runtime still MUST authenticate before readiness. Missing endpoint alone MUST NOT require consumer refresh.

#### Scenario: Separate endpoint selection from account refresh

- **WHEN** a complete profile changes network or its saved endpoint fails
- **THEN** a valid current-network endpoint can be selected without changing account identity or overwriting a later user mutation.

### Requirement: REQ-WARP-F4 — Normalize endpoint address forms

Parsing MUST accept bare IPv4, bare IPv6, and numeric socket addresses. The service port MUST remain authoritative; malformed numeric input MUST NOT enter system DNS.

#### Scenario: Normalize endpoint address forms

- **WHEN** the provider returns IPv4:0 or bracketed IPv6:0 and service port 2408
- **THEN** resolution uses the numeric address with port 2408 and invalid forms fail before lookup.

### Requirement: REQ-WARP-F5 — Keep account state truthful

Local license attach and removal MUST NOT change verified account kind. Registration and refresh MUST use returned account state, including downgrade. A license alone MUST NOT prove Plus.

#### Scenario: Keep account state truthful

- **WHEN** a free account contains a license or refresh downgrades a Plus account
- **THEN** persisted and displayed kind remains or becomes free without losing unrelated user data.

### Requirement: REQ-WARP-F6 — Parse GET and POST separately

POST MUST return a nonblank access token. GET MAY omit a new token and MUST retain the existing one. Provider response bodies MUST NOT expose secrets through errors.

#### Scenario: Parse GET and POST separately

- **WHEN** an authenticated GET returns configuration without a token
- **THEN** refresh succeeds with the saved token; invalid POST remains rejected.

### Requirement: REQ-WARP-F7 — Activate only complete Zero Trust imports

Local import MUST validate keys, required interface addresses, and endpoint before activation. It MUST replace obsolete endpoints for reused IDs and MUST NOT use consumer refresh.

#### Scenario: Activate only complete Zero Trust imports

- **WHEN** incomplete material is imported or a complete profile reuses an ID
- **THEN** invalid input changes no stores; valid input has its own usable endpoint without consumer API.

### Requirement: REQ-WARP-F8 — Expose explicit profile setup

The user MUST have explicit consumer registration and ready-profile import actions. Key generation MUST support Android API 27 with existing dependencies. Setup MUST handle busy, cancellation, success, and failure without exposing secrets or network work during rendering.

#### Scenario: Expose explicit profile setup

- **WHEN** the user confirms registration, selects a complete import, or cancels setup
- **THEN** only explicit actions start work; validated results persist through existing mutation ownership; cancellation never claims remote rollback.

### Requirement: REQ-WARP-F9 — Bound endpoint probe stack use

Endpoint probes MUST keep packet buffers out of the async future's inline storage. Heap buffers MUST retain the same packet limit and wire behavior.

#### Scenario: Probe from a small worker stack

- **WHEN** a complete local probe runs on a worker with a 256 KiB stack
- **THEN** it emits the standard 148-byte initiation and reaches the bounded timeout without stack overflow or a larger process stack setting.

### Requirement: REQ-WARP-F10 — Expand explicit endpoint families without DNS

Candidate expansion MUST include a separate candidate for each explicit IP family before DNS results. An IPv6-only candidate MUST NOT retain an IPv4 address. Existing candidate limits and scope MUST remain in force.

#### Scenario: Select explicit IPv6 after IPv4 fails

- **WHEN** an enabled scanner receives both addresses, IPv4 is unavailable, and DNS has no IPv6 answer
- **THEN** the expanded pool includes the explicit IPv6 candidate and can select it within the configured probe budget.
