## ADDED Requirements

### Requirement: REQ-TRN-1794383863211672-F1 — Replace manual import fields

A manual import SHALL replace all source-owned fields. Omitted AllowedIPs SHALL use the fresh editor default; explicitly empty AllowedIPs SHALL be invalid.

#### Scenario: Replace manual import fields

- **WHEN** a populated editor imports a second configuration
- **THEN** the result matches a fresh import of that second configuration, including empty and omitted policy

### Requirement: REQ-TRN-1794383863211672-F2 — Pad plain WireGuard transport

Plain WireGuard SHALL zero-pad transport plaintext to 16-byte buckets without exceeding the inner MTU. AWG padding SHALL keep its own policy.

#### Scenario: Pad plain WireGuard transport

- **WHEN** an established plain peer sends a 20-byte IP packet
- **THEN** the WireGuard data message has 64 bytes, and near-MTU data stays within the configured bound

### Requirement: REQ-TRN-1794383863211672-F3 — Learn authenticated UDP endpoints

The UDP tunnel SHALL update the endpoint only after an authenticated, replay-accepted protocol event. It SHALL support both available outer IP families and SHALL keep actual-source cookie binding.

#### Scenario: Learn authenticated UDP endpoints

- **WHEN** the authenticated peer changes address or port
- **THEN** traffic continues at the new endpoint, while rejected, replayed and provisional cookie traffic cannot redirect it

### Requirement: REQ-TRN-1794383863211672-F4 — Enforce profile AllowedIPs

The native single-peer tunnel SHALL reject incoming source and outgoing destination IP addresses outside the supplied AllowedIPs. DNS routing SHALL NOT silently broaden that policy.

#### Scenario: Enforce profile AllowedIPs

- **WHEN** a profile has a restricted IPv4 or IPv6 subnet
- **THEN** in-range data works and out-of-range data is refused; incompatible DNS is rejected before VPN setup

### Requirement: REQ-TRN-1794383863211672-F5 — Preserve empty UDP

The userspace stack SHALL deliver successful UDP receives even when the payload is empty.

#### Scenario: Preserve empty UDP

- **WHEN** a reference peer echoes an empty UDP payload
- **THEN** the SOCKS association receives the empty datagram and remains usable

### Requirement: REQ-TRN-1794383863211672-F6 — Resolve either endpoint family

Endpoint hostname resolution SHALL accept AAAA-only results, preserve bounded cancellation and prefer IPv4 when both families are available.

#### Scenario: Resolve either endpoint family

- **WHEN** a hostname resolves only to IPv6
- **THEN** the selected endpoint is IPv6 instead of an IPv4-only resolution error

### Requirement: REQ-TRN-1794383863211672-F7 — Bound message counters

The backend SHALL rekey at the WireGuard message threshold and reject counters at the rejection threshold without wrapping a nonce or replay arithmetic.

#### Scenario: Bound message counters

- **WHEN** a synthetic session reaches each counter boundary
- **THEN** the backend requests rekey or rejects the packet before encryption or replay-state mutation

### Requirement: REQ-TRN-1794383863211672-F8 — Use WireGuard retry jitter

Default WireGuard handshake retries SHALL include the specified bounded jitter and preserve rate limiting.

#### Scenario: Use WireGuard retry jitter

- **WHEN** a handshake timer schedules a retry
- **THEN** the deadline includes a sampled bounded offset and shutdown remains bounded

### Requirement: REQ-TRN-1794383863211672-F9 — Keep cookie clocks and secrets safe

Cookie secret rotation SHALL use the two-minute contract. Test clocks SHALL advance consistently, and secret Debug output SHALL redact PSK if the risk is confirmed.

#### Scenario: Keep cookie clocks and secrets safe

- **WHEN** a cookie crosses its lifetime or a test retries a handshake
- **THEN** expiry and timestamp behavior follow the same clock, and Debug output does not expose PSK
