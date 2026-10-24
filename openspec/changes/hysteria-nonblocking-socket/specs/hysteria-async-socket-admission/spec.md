## Purpose

Ensure that Hysteria Salamander endpoints accept bound UDP sockets for asynchronous traffic without a runtime panic.

## ADDED Requirements

### Requirement: REQ-HY-SOCKET-ASYNC — Admit bound sockets for async traffic

The Salamander socket constructor MUST enable nonblocking operation before registration with Tokio. It MUST preserve the supplied socket address and propagate socket configuration errors to its caller.

#### Scenario: Blocking socket admission

- **WHEN** a bound blocking UDP socket enters the Salamander constructor
- **THEN** construction succeeds and real asynchronous request and response datagrams retain their payload and source address.

#### Scenario: Nonblocking socket admission

- **WHEN** a bound nonblocking UDP socket enters the same constructor
- **THEN** the same asynchronous datagram exchange succeeds without a second socket or a protocol change.

### Requirement: REQ-HY-SOCKET-ENDPOINT — Apply admission to initial and rebound endpoints

Initial endpoint creation and endpoint rebind MUST use the same safe socket admission. Admission MUST retain the protected socket and MUST NOT change socket protection policy, keys, TLS verification, or wire encoding.

#### Scenario: Rebind to a new local socket

- **WHEN** a Salamander endpoint is built and rebound to a new bound blocking UDP socket
- **THEN** both operations succeed and the endpoint reports the new bound address without a blocking-socket panic.

### Requirement: REQ-HY-SOCKET-DEVICE — Verify physical data transfer

Completion MUST require physical Hysteria traffic through an Android artifact built from the integrated fix, in addition to local regression checks.

#### Scenario: Physical acceptance

- **WHEN** the rebuilt artifact starts the authorized Hysteria profile and runs the payload and UDP checks
- **THEN** transfer completes without the socket admission panic and evidence identifies the source revision and artifact.
