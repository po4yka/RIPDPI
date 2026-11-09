## Purpose

Keep privileged root-helper IPC bounded and preserve socket ownership across malformed input, replacement, timeout, and shutdown.

## ADDED Requirements

### Requirement: REQ-IPC-CLOEXEC — Preserve private descriptors

The implementation MUST keep replacement sockets close-on-exec and close the transferred temporary descriptor once on each distinct-descriptor exit path.

#### Scenario: A socket is replaced before a child starts

- **WHEN** a helper replacement fd is installed over a live socket
- **THEN** the socket remains usable, has FD_CLOEXEC, and is absent after child exec

#### Scenario: Descriptor replacement fails or uses the same number

- **WHEN** replacement fails or source and target refer to the same descriptor number
- **THEN** caller-owned target lifetime is preserved and ownership is not released twice

### Requirement: REQ-IPC-LIMITS — Bound scalar-derived memory

The implementation MUST validate genuine ordered TCP coverage and range arithmetic before allocation. Allocation MUST be bounded by the supplied genuine payload data.

#### Scenario: A small request advertises an impossible original length

- **WHEN** a valid frame advertises usize::MAX, an overflowing offset, or impossible payload coverage
- **THEN** the request returns an error without panic or unbounded allocation, received fd copies close, and a later request can complete

#### Scenario: A legitimate fake-only or overlapping batch arrives

- **WHEN** original length is zero with fake segments or genuine segments overlap within valid coverage
- **THEN** the existing permitted packet semantics remain intact

### Requirement: REQ-IPC-DEADLINE — Enforce total frame deadlines

The implementation MUST reject requests completed at or after their deadline even when the watchdog is delayed. A response frame MUST complete within its total response budget despite incremental peer progress.

#### Scenario: Last request bytes arrive after the stored deadline

- **WHEN** the reader completes after the deadline before the watchdog runs
- **THEN** no command is dispatched

#### Scenario: A peer trickles response bytes

- **WHEN** each byte arrives before a per-read timeout but the total frame deadline expires
- **THEN** the caller receives a bounded timeout and any received descriptor copies close

### Requirement: REQ-IPC-SOCKET — Reject incorrect socket types

The implementation MUST reject an fd that is not the network socket type required by a command before it adopts that fd as a TCP or UDP resource.

#### Scenario: A caller sends a file or another socket family

- **WHEN** a TCP or UDP command receives a regular file, Unix socket, or mismatched transport socket
- **THEN** it returns an error, closes the received copy, and retains a responsive helper

### Requirement: REQ-IPC-COMPAT — Preserve activation and session isolation

The implementation MUST retain non-root fallback, exact v3 version checks, nonce validation, and scoped endpoint shutdown. Tests MUST distinguish host evidence from Android root acceptance.

#### Scenario: Root is absent or a foreign endpoint exists

- **WHEN** a standard non-root session starts or another installation owns a helper endpoint
- **THEN** standard paths remain available and cleanup does not terminate the foreign helper
