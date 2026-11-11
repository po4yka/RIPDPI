## ADDED Requirements

### Requirement: REQ-TRN04-OPEN — Open streaming upload without a response heartbeat

The native xHTTP client MUST return a writable stream-up session after request submission without requiring upload response headers. A rejected or reset upload MUST produce an observable error.

#### Scenario: Server does not flush upload response headers

- **WHEN** the peer accepts the GET and waits for upload payload before flushing POST response headers
- **THEN** the client returns a writable stream and completes a payload exchange.

### Requirement: REQ-TRN04-DROP — Close each stream's HTTP resources

The client MUST terminate the HTTP requests and tasks owned by a fully dropped logical stream while keeping active sibling streams usable. Write-half shutdown MUST preserve the download half.

#### Scenario: Drop an idle stream on a reused carrier

- **WHEN** a completed stream is dropped while the peer leaves its responses open without data
- **THEN** its peer response bodies and tasks close within a bounded interval and a sibling still exchanges bytes.

### Requirement: REQ-TRN04-URL — Preserve configured URL semantics

The client MUST preserve path slashes and query data and encode the decoded path as the pinned Xray URL implementation does.

#### Scenario: Path contains percent characters and repeated slashes

- **WHEN** both peers use a path with a literal percent character, repeated terminal slash or a query ending in slash
- **THEN** the request matches the peer path and preserves the query value in both supported modes.

### Requirement: REQ-TRN04-POOL — Use capacity on any carrier

A queued pool acquisition MUST use any carrier that becomes available without exceeding physical or logical limits.

#### Scenario: Second carrier becomes free first

- **WHEN** two carriers are full and only the second carrier releases capacity
- **THEN** the pending acquisition completes on that carrier while the first remains busy.

### Requirement: REQ-TRN04-DOCS — Describe the supported xHTTP contract

The documentation MUST distinguish mode selectors from download legs and record the verified upstream commit and cancellation behavior.

#### Scenario: Read the support contract

- **WHEN** a maintainer checks supported modes and cleanup ownership
- **THEN** the documents agree with the current code and pinned source.
