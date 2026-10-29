## Purpose

Complete AmneziaWG 3.1 behavior across the native runtime, profile input, editor, and interoperability evidence without weakening authentication or bounded input validation.

## MODIFIED Requirements

### Requirement: REQ-AWG3-WIRE — Match the upstream AWG 3.1 wire format

The native codec MUST interoperate with amneziawg-go v3.1.20260828 for header protection, random trailers, content padding and header ranges. All outgoing transport packets, including persistent and automatic keepalives, MUST use the same padding policy. Custom content padding MUST take precedence over random-trailer padding; when both are off, standard 16-byte plaintext alignment MUST apply within the effective MTU. Prefixes, authentication overhead, trailers, and padding MUST fit the bounded carrier size.

#### Scenario: Live upstream peer

- **WHEN** RIPDPI connects to the pinned upstream peer with header protection, padding, trailers and S1-S4 = 12
- **THEN** IPv4/IPv6 TCP and UDP transfers succeed, the observed wire properties match the profile, and a wrong header protection key fails authentication.

#### Scenario: Idle transport and default padding

- **WHEN** a persistent or automatic keepalive is sent with each supported padding mode, or a non-aligned payload is sent with custom padding off
- **THEN** independently derived wire lengths satisfy that mode and the effective MTU, and the peer accepts the packet.

### Requirement: REQ-AWG3-HOSTILE — Hostile datagrams cannot panic or inflate the tunnel

The codec MUST NOT panic on any inbound datagram. The padding window MUST grow only from authenticated transport and MUST remain bounded by the supported payload and carrier overhead. Wrong keys, corrupted packets, and replays MUST NOT update the window or deliver payload. Endpoint and session reset behavior MUST be explicit and tested.

#### Scenario: Spoofed large datagram

- **WHEN** an unauthenticated 65 KB datagram with a valid transport header arrives
- **THEN** it is dropped and the padding window does not change.

#### Scenario: Authenticated size learning

- **WHEN** a valid transport packet arrives near the supported MTU and is then replayed across a reset
- **THEN** size learning stays within its bound and reset and replay behavior follow the documented session policy.

### Requirement: REQ-AWG3-CONTRACT — Carry 3.x keys without breaking stored data

Every new field MUST be optional with a safe default. The header protection key MUST stay out of the Room blob, logs, exports without explicit secret inclusion, and `toString`. Known 3.x keys MUST survive supported import, edit, save, reload, and activation paths. Existing stored off values MUST retain their meaning. Validation corrections MUST reject invalid new input with an actionable error instead of reconstructing values lost by older parsers.

#### Scenario: Older profile

- **WHEN** a valid profile saved before this change loads
- **THEN** its stored values and secret references retain their meaning and it can activate.

#### Scenario: Full parameter round trip

- **WHEN** a profile with all supported 3.1 fields is imported, edited, saved, reloaded, and activated
- **THEN** the native request contains the intended values and secret material is absent from non-secret storage and diagnostic text.

## ADDED Requirements

### Requirement: REQ-AWG31-TIMERS — Apply the complete timer policy

The runtime MUST apply RekeyAfterTime, RekeyTimeout, RejectAfterTime, KeepaliveTimeout, MaxHandshakeAttempts, and persistent keepalive ranges through one coherent backend policy. It MUST define units, bounds, defaults, session start, initiator and responder behavior, retry reset, and key expiry. A fixed backend timer or local guard MUST NOT silently override accepted settings. Unsupported settings during staged delivery MUST be identified before activation.

#### Scenario: Timer boundaries and retry exhaustion

- **WHEN** a deterministic clock crosses configured rekey, retry, idle, expiry, and retry-count boundaries for both session roles
- **THEN** exactly the documented actions occur, expired keys are not used, and successful authentication resets the appropriate retry state.

#### Scenario: Keepalive interval range

- **WHEN** authenticated traversal reschedules a ranged persistent keepalive
- **THEN** the sampled interval stays in the configured range, scalar zero remains off, and unrelated fixed timer ceilings do not alter the policy.

### Requirement: REQ-AWG31-CLASSIFY — Authenticate ambiguous receive candidates

Receive classification MUST handle plausible packet types at different S offsets without dropping valid transport because an earlier candidate failed authentication. Candidate attempts MUST be bounded and MUST preserve MAC, AEAD, replay, and session-state checks. Failed candidates MUST NOT consume replay state or cause protocol side effects.

#### Scenario: False handshake candidate

- **WHEN** a real authenticated transport packet also matches a handshake header range at another prefix offset
- **THEN** it is delivered exactly once after authenticated classification, and a replay is rejected.

### Requirement: REQ-AWG31-EMPTY — Distinguish padded keepalives from malformed payloads

The backend boundary MUST identify authenticated empty transport with valid zero padding separately from malformed authenticated IP data. It MUST NOT treat every authenticated InvalidPacket result as a successful keepalive. Metrics and window updates MUST follow the authenticated result without bypassing replay checks.

#### Scenario: Authenticated malformed payload

- **WHEN** the peer sends an authenticated padded empty packet, a nonzero malformed IP payload, and a replay of each
- **THEN** only the first packet is accepted as a keepalive; malformed payload and replay are rejected and reported correctly.

### Requirement: REQ-AWG31-COOKIES — Apply explicit cookie behavior under load

DisableCookies MUST control the supported cookie policy, including the backend load path for initiation and response packets. The policy MUST retain MAC1 and authenticated session checks, define both flag states against the pinned upstream behavior, and bound unauthenticated work. The flag MUST NOT become a blanket authentication or resource-limit bypass.

#### Scenario: Load and cookie policy

- **WHEN** initiation, response, and cookie messages arrive below and above the load threshold with each flag value
- **THEN** cookie challenge, acceptance, or rejection matches the specified policy, invalid authentication still fails, and work remains bounded.

### Requirement: REQ-AWG31-INPUT — Validate profile input consistently

The editor, configuration importer, and URI importer MUST share keepalive grammar and bounds. They MUST accept scalar zero and 0-0 as off and positive ordered scalar or range values through 65535. They MUST reject 0-N for N greater than zero, reversed ranges, extra separators, malformed numbers, and overflow without silently changing the value. Header Protection key and prefix constraints MUST be checked before save and activation readiness.

#### Scenario: Invalid keepalive input

- **WHEN** input contains 35-25, 25-bad, 25-35-45, 0-25, or an out-of-range value in any supported entry path
- **THEN** that path reports the invalid value and does not convert it to a scalar or off.

#### Scenario: Invalid protected prefix

- **WHEN** Header Protection is enabled with an insufficient prefix such as S1 = 8
- **THEN** the editor explains the constraint and does not present the profile as ready to activate.

### Requirement: REQ-AWG31-EDITOR — Expose v3 settings and explicit preset replacement

The editor MUST display and edit supported v3 settings and identify any accepted setting that the current runtime does not apply. Preset application that replaces server-coordinated parameters or clears v3 fields MUST be explicit before the user accepts replacement. Secret values MUST retain protected storage and redacted presentation. New strings MUST cover all ten supported locales.

#### Scenario: Imported profile and cohort selection

- **WHEN** a user opens an imported v3 profile and selects a cohort preset
- **THEN** the UI exposes the current settings and the replacement effect, and values change only through the explicit preset action.

### Requirement: REQ-AWG31-SUBSET — Document and retain safe compatibility limits

The implementation MUST document its bounded subset separately from unsupported 3.1 behavior. It MUST preserve bounded numeric values, CPS sizes and tag counts, strict CPS parsing, header constraints, and safe junk-range validation. Inclusive junk-size bounds and unsupported undocumented CPS tags MUST have explicit compatibility notes and regression coverage. Upstream races or invalid-input allocation behavior MUST NOT be copied as compatibility requirements.

#### Scenario: Boundary and malformed configuration

- **WHEN** CPS input exceeds bounds, uses an unsupported tag, or a junk or header configuration violates the documented subset
- **THEN** input is rejected without panic, overflow, or unbounded allocation, and the user receives a precise reason.

### Requirement: REQ-AWG31-EVIDENCE — Prove each feature on pinned peers and artifacts

Acceptance MUST map changed behavior to regression tests and independent wire observations where applicable. Feature validators MUST fail when the feature is absent. Host, emulator, physical device, hosted CI, and artifact evidence MUST remain separate and identify exact revisions. This change MUST NOT lift the shared arm64 deployment guard automatically.

#### Scenario: Pinned peers and physical artifacts

- **WHEN** interoperability and physical arm64 acceptance results are recorded
- **THEN** Go 3.1 and Linux peer revisions and known upstream limitations are explicit, and physical receipts include the source, APK and native artifact hashes, device, peer, profile, and result.

#### Scenario: Payload succeeds but a feature is missing

- **WHEN** a transfer succeeds while padding, timer behavior, or cookie behavior does not match the configured profile
- **THEN** the corresponding feature check fails and cannot be replaced by a payload-success counter.

#### Scenario: Historical emulator evidence

- **WHEN** an earlier source revision has a successful emulator record
- **THEN** it is retained as historical evidence and does not satisfy current-source, physical-device, or exact-artifact acceptance.
