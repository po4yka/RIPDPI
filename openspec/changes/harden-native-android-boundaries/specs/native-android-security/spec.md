## Purpose

Protect saved relay credentials and bound work from network peers and Android content providers.

## ADDED Requirements

### Requirement: REQ-HELPER-AUTH - Authenticate local helper clients

A helper frontend that uses stored user configuration MUST require per-session local credentials before it opens an upstream connection. This includes NaiveProxy SOCKS and HTTP CONNECT, native wrappers, and the fixed-target PT bridge.

#### Scenario: An unrelated application connects

- **WHEN** a local client omits credentials or supplies an incorrect pair
- **THEN** the helper rejects the request without an upstream connection.

#### Scenario: The service connects

- **WHEN** the client supplies the current session pair
- **THEN** traffic and readiness checks work, and credentials do not enter argv or logs.

### Requirement: REQ-LEGACY-UDP - Require SS2022 for Shadowsocks UDP

The runtime MUST reject legacy Shadowsocks UDP and MUST preserve supported legacy TCP. It MUST NOT downgrade UDP or advertise unavailable UDP capability.

#### Scenario: A legacy profile requests UDP

- **WHEN** UDP is requested through a legacy Shadowsocks profile
- **THEN** the request fails with an explicit unsupported result and no datagram is sent.

### Requirement: REQ-SS2022-BOUND - Bound server replay state

An SS2022 UDP client MUST retain at most two server sessions and MUST reject excess rotation until the old session has been inactive for strictly more than 60 seconds. Invalid packets MUST NOT change replay state.

#### Scenario: A relay sends many authenticated session IDs

- **WHEN** a third server session arrives while the previous session is still recent
- **THEN** it is rejected and the retained sessions continue to reject duplicate packets.

### Requirement: REQ-IMPORT-BOUND - Bound shared import work

Shared file import MUST bound concurrent provider opens and reads, MUST stop pipe reading at the deadline, and MUST not deliver a result after its Activity is destroyed. A provider that ignores cancellation MUST not permit unbounded worker creation.

#### Scenario: A provider stalls

- **WHEN** a provider never supplies bytes or never returns from open
- **THEN** the UI completes by its deadline and repeated imports cannot grow worker or queued task counts without bound.

### Requirement: REQ-RELEASE-READ - Preserve fixed-read results

Fixed-read completion MUST update the logical buffer length in debug and release builds, including shorter reads after reuse and empty reads.

#### Scenario: An optimized build completes a read

- **WHEN** the completion reports a valid byte count
- **THEN** the returned buffer exposes exactly those bytes and no stale tail.
