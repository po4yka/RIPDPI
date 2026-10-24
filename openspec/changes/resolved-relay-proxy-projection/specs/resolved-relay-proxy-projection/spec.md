## Purpose

Keep the proxy upstream configuration consistent with the resolved relay runtime that owns its SOCKS endpoint, without weakening UDP admission or startup cleanup.

## ADDED Requirements

### Requirement: REQ-RELAY-PROJECTION-IDENTITY — Project the ready relay

The service MUST configure proxy startup from the started relay's resolved kind, profile ID, UDP policy, and bound SOCKS endpoint. Normal startup and race promotion MUST apply the same rendering checks. These values MUST come from the current runtime slot without reloading mutable profile settings or copying credentials.

#### Scenario: Saved UDP profile overrides stale global preferences

- **GIVEN** global relay UDP is disabled and the saved Hysteria2 profile enables UDP
- **WHEN** the normal relay runtime becomes ready and the proxy requires UDP
- **THEN** the serialized proxy envelope enables relay UDP and uses that runtime's bound endpoint and resolved identity

#### Scenario: Captured selection and race keep runtime identity

- **GIVEN** a captured selector member or a race winner starts a resolved relay
- **WHEN** the proxy starts
- **THEN** the proxy uses that relay's identity and endpoint, without changing the captured selection or race outcome

#### Scenario: Subprocess endpoint authority

- **GIVEN** a subprocess relay confirms its configured IPv4, hostname, or IPv6 SOCKS endpoint through its readiness probe
- **WHEN** telemetry reports that listener
- **THEN** the endpoint parser receives a valid URI authority with the confirmed host and port, including brackets for IPv6

### Requirement: REQ-RELAY-PROJECTION-FAIL-CLOSED — Preserve admission and ownership

The service MUST reject a resolved relay that lacks required UDP before starting the proxy. It MUST reject missing or invalid bound endpoints and inconsistent rendering, and MUST retain or complete cleanup ownership for every started runtime.

#### Scenario: Resolved UDP policy refuses a stale enabled global flag

- **GIVEN** global relay UDP is enabled and the resolved profile disables UDP
- **WHEN** proxy startup requires UDP
- **THEN** startup fails before relay or proxy runtime creation

#### Scenario: Invalid readiness endpoint

- **GIVEN** the relay reports ready without a valid bound endpoint
- **WHEN** normal startup tries to project the relay
- **THEN** it fails before proxy startup and the started relay is stopped or remains owned for cleanup retry

#### Scenario: Renderer loses a runtime field

- **GIVEN** the renderer does not preserve a resolved identity, UDP flag, or bound endpoint
- **WHEN** the shared startup path validates the rendered preferences
- **THEN** startup fails before the proxy starts and relay cleanup remains possible

#### Scenario: Other egress owners remain isolated

- **GIVEN** AWG owns the egress, or an existing WARP or cancellation path is active
- **WHEN** startup or stop runs
- **THEN** the existing egress precedence, callbacks, and stop-and-join ownership remain unchanged

### Requirement: REQ-RELAY-PROJECTION-IMPORTED-CAPABILITY — Preserve supported imported UDP

Profile mapping and service admission MUST use one shared pure predicate for VLESS sub-mode UDP support. Reality TCP with Vision or Vision UDP443 MUST retain UDP eligibility. XHTTP, flowless Reality, plain TLS VLESS, SSH, and Mieru MUST remain UDP-ineligible.

#### Scenario: Supported imported Reality profile

- **GIVEN** an imported Reality TCP profile uses Vision or Vision UDP443
- **WHEN** it becomes a selector runtime profile and starts a UDP-required session
- **THEN** mapping and service admission agree that UDP is supported, and the proxy uses the resolved runtime policy

#### Scenario: Unsupported imported sub-mode

- **GIVEN** an imported profile uses XHTTP, flowless Reality, plain TLS VLESS, SSH, or Mieru
- **WHEN** mapping and service admission inspect its UDP support
- **THEN** both report no UDP support and a UDP-required start fails before proxy creation

### Requirement: REQ-RELAY-PROJECTION-NORMALIZATION — Preserve supported resolved UDP

Engine preference normalization MUST preserve the resolved UDP flag for every supported relay kind. Shared kind/profile capability policy MUST agree with imported mapping and service admission. It MUST inspect raw Reality transport and flow before defaults can make an unsupported profile appear UDP-capable.

#### Scenario: Supported kind reaches the proxy envelope

- **GIVEN** a ready Hysteria2, MASQUE, TUIC v5, AnyTLS, Trojan, Shadowsocks, or supported Reality TCP relay enables UDP
- **WHEN** UI or remembered JSON preferences project the resolved runtime
- **THEN** normalization and the serialized proxy envelope retain UDP enabled

#### Scenario: Unsupported or disabled policy remains disabled

- **GIVEN** a resolved profile disables UDP or its kind or raw sub-mode has no UDP capability
- **WHEN** preferences are normalized and projected
- **THEN** no default value enables UDP, and UDP-required startup remains blocked before proxy creation
