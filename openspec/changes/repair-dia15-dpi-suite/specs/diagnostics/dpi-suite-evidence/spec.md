## ADDED Requirements

### Requirement: REQ-DIA15-TCP — Preserve TCP train observations

The suite MUST distinguish unsupported targets, invalid targets, HTTP rejection, connection changes, and measured interruptions. A train interruption alone MUST NOT establish DPI. Byte counts MUST state their measurement scope.

#### Scenario: Unsupported and rejecting targets

- **WHEN** cleartext is unavailable or the peer returns HTTP 431
- **THEN** the result reports the applicable cause without reporting a dead or clean network

### Requirement: REQ-DIA15-SNI — Compare SNI on a measured peer

The finder MUST require a usable TLS baseline and preserve the target peer and HTTP authority for candidate requests.

#### Scenario: No eligible comparison

- **WHEN** TCP16 is unselected or the completed train has no eligible TLS interruption
- **THEN** the suite reports the applicable prerequisite or inapplicable outcome

### Requirement: REQ-DIA15-TRANSFER — Measure Telegram legs independently

Each transfer MUST retain its own payload count, timing, HTTP outcome, and failure. Progress delivery MUST NOT change counts. Cancellation MUST close active I/O. DC connection attempts MUST run off the UI thread. A transfer failure or short response MUST NOT establish throttling.

#### Scenario: The upload peer returns HTTP 413

- **WHEN** the upload is rejected and the download finishes
- **THEN** the suite retains both outcomes and both measured payload counts

### Requirement: REQ-DIA15-ECH — Preserve scoped handshake evidence

ECH success MUST require acceptance. The result MUST distinguish authenticated rejection, transport, TLS, trust, and setup failure. An incomplete JNI response MUST be a contract error. The suite MUST retain bounded binding attempts and MUST NOT use ordinary HTTPS GET as an ECH-off control.

#### Scenario: Ordinary reachability fails before TLS

- **WHEN** the ordinary DNS path fails and a separate ECH exchange succeeds
- **THEN** the suite preserves both outcomes without claiming ordinary TLS was blocked

### Requirement: REQ-DIA15-SUITE — Honor selection and show missing results

TCP16 MUST NOT depend on unused DNS preview. The suite MUST retain failures and unknown results. An inapplicable conditional probe MUST NOT invalidate successfully measured required probes.

#### Scenario: TCP16 is the only selected probe

- **WHEN** the suite starts
- **THEN** TCP16 runs without a DNS preview
