# relay-pool-lifecycle Specification

## Purpose
Define relay pool admission, cached-session idle accounting, independent datagram
ownership, and terminal cleanup. These contracts keep resource use bounded and
preserve cleanup ownership when opens or shutdown are cancelled.

## Requirements

### Requirement: REQ-TRN08-OPEN-BOUND — Bound establishment attempts

The pool MUST admit at most eight concurrent establishment attempts across stream and datagram opens until each attempt completes, fails, or is cancelled. Established leases MUST release their establishment slot.

#### Scenario: Bound establishment attempts

- **WHEN** More than eight opens wait for a peer handshake
- **THEN** Only eight attempts reach the peer; a completed or cancelled attempt admits another without waiting for established traffic to end.

### Requirement: REQ-TRN08-GENERATION-IDLE — Keep generation-specific idle time

The pool MUST measure cached idle time from the last lease of that cached session generation. Leases of retired generations MUST NOT alter that time. Cache selection and lease reservation MUST be atomic with expiry.

#### Scenario: Keep generation-specific idle time

- **WHEN** An old lease overlaps a replacement carrier
- **THEN** The replacement remains reusable until its own idle deadline and expires independently of the old lease.

### Requirement: REQ-TRN08-CLEANUP-RETENTION — Release completed cleanup owners

A completed owner close MUST finish all joins even when it returns an error. The registry MUST release completed owners and preserve the first cleanup failure for repeated shutdown. Cancellation before close completion MUST preserve join ownership.

#### Scenario: Release completed cleanup owners

- **WHEN** Released owners repeatedly finish with a terminal error
- **THEN** Old owners are destroyed, history does not grow with the failures, and shutdown still reports the original error.

### Requirement: REQ-TRN08-DATAGRAM-ISOLATION — Isolate independent datagram carriers

Independent XUDP opens MUST NOT construct, retain, invalidate, or change idle usage of an unrelated cached yamux carrier. Datagram admission limits MUST remain enforced.

#### Scenario: Isolate independent datagram carriers

- **WHEN** A pending independent datagram open is cancelled beside a live TCP carrier
- **THEN** The next TCP open reuses that carrier and the independent datagram returns its permit.

### Requirement: REQ-TRN08-TERMINAL-SHUTDOWN — Close pool admission before cleanup

Once shutdown starts, the pool MUST reject new opens, cancel queued and active opens, prevent late cache publication, and drain establishment attempts before factory cleanup. Repeated or cancelled shutdown MUST retain cleanup ownership.

#### Scenario: Close pool admission before cleanup

- **WHEN** Shutdown races a pending carrier constructor and queued opens
- **THEN** No open succeeds after terminal closure and no carrier is published after cleanup.

### Requirement: REQ-TRN08-RUNTIME-TEARDOWN — Verify the owning runtime teardown

The relay runtime owner MUST stop asynchronous session tasks before its start call reports completed runtime teardown. Pool cleanup MUST NOT claim a stronger session-driver join guarantee than the owning boundary provides.

#### Scenario: Verify the owning runtime teardown

- **WHEN** An asynchronous task still owns a carrier when the session exits
- **THEN** The runtime teardown releases the carrier before the owner returns; blocking resolver timeout semantics remain unchanged.
