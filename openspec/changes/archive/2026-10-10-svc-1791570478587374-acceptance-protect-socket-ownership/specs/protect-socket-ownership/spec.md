## Purpose

Keep the active VPN descriptor protection endpoint usable across overlapping service session startup and teardown without weakening protection or changing the native ACK contract.

## ADDED Requirements

### Requirement: REQ-PROTECT-OVERLAP — Preserve the new endpoint

The implementation MUST keep a newer session endpoint reachable when an older session completes cleanup.

#### Scenario: Old teardown completes after new startup

- **GIVEN** an old session is held before its final endpoint cleanup
- **WHEN** a new session binds its endpoint and the old cleanup resumes
- **THEN** a client can connect to the new endpoint and receive the expected protection ACK
- **AND** the old server and its workers are closed

### Requirement: REQ-PROTECT-OWNERSHIP — Clean only owned resources

The implementation MUST close and remove only resources owned by that server operation. Failed startup and repeated cleanup MUST NOT remove a different live endpoint or withdraw a newer session active path and native protect registration. Internal registration ownership MUST retain the existing native generation-token contract.

#### Scenario: Repeated stop follows replacement

- **GIVEN** a newer endpoint is live after old server shutdown
- **WHEN** old shutdown runs again
- **THEN** the newer endpoint remains reachable

#### Scenario: Startup fails after partial allocation

- **GIVEN** startup allocated owned socket resources
- **WHEN** bind or listen fails
- **THEN** its owned resources close without deleting a different live endpoint
- **AND** no active endpoint is advertised for the failed operation

#### Scenario: A duplicate path is requested

- **GIVEN** a live server owns a filesystem endpoint
- **WHEN** another server attempts to bind the same path
- **THEN** the duplicate operation is rejected before platform bind can replace the endpoint
- **AND** duplicate cleanup leaves the owner endpoint reachable with the expected ACK

#### Scenario: Old lifecycle releases a superseded registration

- **GIVEN** a new session owns the active advertised path and native protection registrations
- **WHEN** the old lifecycle clears its protection ownership
- **THEN** the new path and registrations remain active
- **AND** captured direct protection from the old session fails closed

### Requirement: REQ-PROTECT-CONTRACT — Preserve protection and native compatibility

The implementation MUST preserve the filesystem socket, SCM_RIGHTS descriptor transfer, and one-byte ACK contract. Protection failures MUST close the outbound connection and propagate failure. The fix MUST work on non-root devices and MUST NOT introduce direct bypass, raw endpoint logging, a public schema change, or a new production dependency.

#### Scenario: Descriptor protection fails

- **WHEN** the service cannot protect a received descriptor
- **THEN** the client receives a failure ACK and the outbound use fails closed

#### Scenario: Native traffic after session restart

- **WHEN** an actual Android VPN session restarts with a protected endpoint
- **THEN** TCP and UDP payloads match and the owned fixture records both receipts
- **AND** the original no-bypass, negative-path, recovery, and cleanup assertions remain active

#### Scenario: Permission preflight before service creation

- **GIVEN** the app is halted and no active protect endpoint exists
- **WHEN** local-network permission preflight assesses a VPN start
- **THEN** it preserves VPN mode, selected profile, settings, DNS, and the existing typed local-network permission result
- **AND** it does not launch native runtimes, root helpers, or NFQUEUE
- **AND** an actual runtime capture with that empty owner still fails closed

### Requirement: REQ-PROTECT-EVIDENCE — Keep causal and repeat evidence separate

The implementation MUST retain the original failed report and prove the ownership defect with a failing deterministic test before the fix. It MUST report source, device, artifact, and published CI evidence separately.

#### Scenario: A corrected run passes

- **WHEN** a fresh complete Android profile and required repeats pass on clean prepared artifacts
- **THEN** each complete report passes the verifier
- **AND** the old failure remains retained with its actual source SHA and causal limits
