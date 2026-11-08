## Purpose

Preserve the originating application identity across the local VPN data plane without stale owners or destination collisions.

## ADDED Requirements

### Requirement: REQ-VPN08-UDP-AGE — Bound ordinary UDP owner age

The implementation MUST expire UDP UID results after an absolute bounded age. Traffic MUST NOT renew this age.

#### Scenario: Bound ordinary UDP owner age

- **WHEN** A permitted owner closes a socket and a denied owner reuses the exact UDP tuple
- **THEN** admission requests a fresh UID after the age bound and does not forward before its verdict.

#### Scenario: Stop obsolete responses before the successor verdict

- **WHEN** a new exact-tuple registration is Pending or Denied
- **THEN** the prior registration has no live response or error context, while neighboring destinations remain valid.

#### Scenario: Preserve UDP transport when the owner is unchanged

- **WHEN** a UID refresh confirms the same UID and app scope for a live UDP tuple
- **THEN** the flow reuses its association, proxy socket and source port with fresh destination context. Empty transports remain bounded while resolution is pending.

### Requirement: REQ-VPN08-DESTINATION — Keep concurrent owners separate

The implementation MUST NOT infer app identity from destination IP. Learning state MUST separate opaque app scopes while preserving TCP fallback across flows of the same app scope.

#### Scenario: Keep concurrent owners separate

- **WHEN** two apps use the same destination and one app succeeds over TCP
- **THEN** the other app cannot receive that success or its package mark.

#### Scenario: Separate equal packages owned by different UIDs

- **WHEN** distinct UIDs resolve to the same package name and version
- **THEN** they receive distinct opaque transport scopes.

### Requirement: REQ-VPN08-SESSION — Fence session and package results

The implementation MUST reject results from a retired TUN session or superseded package revision. It MUST clear transient app identity on session end.

#### Scenario: Fence session and package results

- **WHEN** a blocked resolver returns after stop or package invalidation
- **THEN** its result cannot populate the current cache or current qualification counters.

### Requirement: REQ-VPN08-START — Register before packet processing

The implementation MUST register the owner resolver before starting TUN packet processing and release it on cancellation or startup failure.

#### Scenario: Register before packet processing

- **WHEN** a first packet is available at native readiness
- **THEN** its live registration is not cleared by later resolver registration.

### Requirement: REQ-VPN08-MAPDNS — Preserve identity across address translation

The implementation MUST retain the original kernel tuple for UID lookup and carry app scope independently of synthetic or real target IP.

#### Scenario: Preserve identity across address translation

- **WHEN** TCP or UDP targets a synthetic address mapped to a real peer
- **THEN** learning uses the original app scope without an IP alias or cross-app join.

#### Scenario: Keep a queued mapping during owner resolution

- **WHEN** a mapped UDP packet waits for its owner verdict during cache pressure or an underlay reset
- **THEN** its mapping remains leased until forwarding acquires its own lease or the queued packet is dropped. Queue expiry and capacity limits release unused leases.

#### Scenario: Reject an old unleased mapping

- **WHEN** an unleased synthetic mapping belongs to an older underlay generation
- **THEN** generation synchronization removes it before packet pin acquisition, and the packet cannot create a SOCKS transport through that stale mapping.

### Requirement: REQ-VPN08-RAW — Retire obsolete context before UDP policy drops

The implementation MUST retire an obsolete exact-tuple response context before a consuming UDP raw hook or STUN policy filter returns for a valid UDP packet. A disarmed consuming hook or STUN drop MUST NOT wait for attribution grace. Root-only Lua remains opt-in.

#### Scenario: Consume a successor packet in an opt-in Lua path

- **WHEN** Lua passes a UDP packet through SOCKS and consumes a later packet of the same tuple after its owner registration changes
- **THEN** the previous registration cannot deliver data or errors to TUN, and the consumed packet creates no SOCKS transport.

#### Scenario: Drop a successor STUN packet without a stale response path

- **WHEN** a valid STUN packet is policy-dropped after the exact tuple receives a new owner registration
- **THEN** the prior registration cannot deliver data or errors to TUN, and the STUN packet creates no SOCKS transport or owner-wait queue entry.

### Requirement: REQ-VPN08-UNKNOWN — Keep unknown owners unattributed

The implementation MUST keep unknown or ambiguous packages unattributed. A disarmed policy MAY forward after a bounded attribution grace without an app scope. It MUST NOT upgrade an already unscoped transport with another flow identity.

#### Scenario: Keep unknown owners unattributed

- **WHEN** the owner resolver is unavailable or slow
- **THEN** armed admission remains fail-closed and disarmed traffic remains usable without guessed app identity.

#### Scenario: Preserve disarmed UDP when the attribution queue has no room

- **WHEN** a valid reassembled UDP datagram exceeds the attribution wait-buffer capacity, or the queue is full, while admission is disarmed
- **THEN** it forwards without waiting for app scope, without growing the wait buffers, evicting an earlier queued packet or recording a false traffic drop. Armed admission keeps its fail-closed limits.

### Requirement: REQ-VPN08-PRIVACY — Keep identity opaque

The implementation MUST keep package names and UID values out of emitted app-scope tokens and runtime exports. Scope state MUST remain bounded and session-local.

#### Scenario: Keep identity opaque

- **WHEN** an app scope crosses the local SOCKS and telemetry boundary
- **THEN** only an opaque token crosses and package identity remains transient in Kotlin.
