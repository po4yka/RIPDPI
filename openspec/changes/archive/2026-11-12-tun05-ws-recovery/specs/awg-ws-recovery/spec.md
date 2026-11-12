## Purpose

Keep an established WireGuard WebSocket session available after a transient carrier failure without an unprotected routing window.

## ADDED Requirements

### Requirement: REQ-AWG-WS-RECOVERY — Bound fresh carrier recovery

The supervisor MUST retry an established WS session with fresh native runtimes, at most three attempts, with increasing delays. It MUST report one terminal failure after exhaustion. UDP exits MUST retain fail-stop behavior.

#### Scenario: WS carrier exits after readiness

- **WHEN** a ready WS runtime exits unexpectedly
- **THEN** a fresh runtime is started after backoff and the terminal exit callback is withheld if readiness succeeds

#### Scenario: Recovery fails

- **WHEN** all three fresh runtimes fail readiness
- **THEN** one terminal failure reaches the session exit handler

#### Scenario: UDP carrier exits

- **WHEN** a ready UDP runtime exits
- **THEN** the existing exit callback is invoked without recovery

### Requirement: REQ-AWG-WS-STOP — Preserve lifecycle ownership

Stop MUST cancel pending recovery and wait for owned runtime cleanup. A replaced session MUST ignore old completion callbacks. Recovery MUST preserve the installed TUN/proxy barrier and the WS carrier selection; it MUST NOT downgrade to UDP or log credentials.

#### Scenario: Stop during backoff

- **WHEN** Stop is called before the next recovery delay ends
- **THEN** no replacement native runtime is created

#### Scenario: Stop during recovery startup

- **WHEN** Stop is called while a replacement runtime awaits readiness
- **THEN** that runtime is stopped and the recovery coroutine completes before Stop returns

### Requirement: REQ-AWG-WS-BOOTSTRAP — Resolve on the VPN underlay

VPN WS starts MUST supply numeric dial addresses from the current underlying Network, reject empty or invalid results, and preserve the URL host for TLS and HTTP identity. Each fresh attempt MUST resolve again. Stop MUST discard unfinished bootstrap. Null addresses are reserved for deliberate non-VPN legacy callers; an explicit empty list MUST fail closed.

#### Scenario: Current underlay resolves hostname

- **WHEN** a VPN WS profile is started
- **THEN** native setup receives numeric addresses without changing the original WSS URL

#### Scenario: Stop while bootstrap is pending

- **WHEN** Stop cancels setup before resolution completes
- **THEN** no native runtime is created from a late result
