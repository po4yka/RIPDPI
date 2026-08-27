## Purpose

Define how RIPDPI observes, classifies, and exports the Android VPN route when
the VPN owner is intentionally excluded from its own tunnel, without confusing
the owner's process-default underlay with absence of the owner-created VPN.

## ADDED Requirements

### Requirement: REQ-VPN-ROUTE-001 — Observe the owner-created VPN independently

The implementation MUST determine VPN-network presence from a public-API
network callback observation correlated to the current RIPDPI service lifecycle
and MUST NOT use
the calling UID's `activeNetwork` as the VPN existence oracle.

#### Scenario: Self-excluded owner sees the underlay

- **GIVEN** RIPDPI has established a VPN and intentionally excluded its own package
- **AND** RIPDPI's calling-UID default network is validated Wi-Fi or cellular with `NOT_VPN`
- **WHEN** the service-correlated callback reports a `TRANSPORT_VPN` network
- **THEN** VPN presence is reported as present
- **AND** the calling-UID underlay does not produce a Route warning

#### Scenario: Owned VPN is absent

- **GIVEN** the VPN lifecycle has passed its bounded startup observation window
- **AND** no current establish receipt or owned VPN callback observation exists
- **WHEN** Route state is classified
- **THEN** the route is reported unavailable rather than healthy

#### Scenario: Network-state observation is unavailable

- **GIVEN** public network-state observation cannot be registered or read
- **WHEN** Route state is classified
- **THEN** the result is unknown or unverified
- **AND** the result is not presented as an authoritative missing VPN

### Requirement: REQ-VPN-ROUTE-002 — Correlate intended and installed route shape

The implementation MUST compare the privacy-safe IPv4 and IPv6 route families
intended by the current VPN builder generation with the default-route families
observed on the owned VPN network.

#### Scenario: Intended route families are installed

- **GIVEN** the current VPN generation intends an IPv4 default route and no IPv6 default route
- **WHEN** the owned VPN link properties expose the same default-route families
- **THEN** route installation is classified consistent

#### Scenario: An intended family is missing

- **GIVEN** the current VPN generation intends IPv4 and IPv6 default routes
- **WHEN** the owned VPN link properties expose only an IPv4 default route
- **THEN** Route is classified degraded with a route-family mismatch reason
- **AND** relay success does not erase that mismatch

#### Scenario: Split routing limits route-coverage proof

- **GIVEN** the current builder uses an allow-only or disallow app-routing plan
- **WHEN** diagnostics exports route coverage
- **THEN** it reports the categorical plan and configuration-level coverage
- **AND** it does not claim the default route of an arbitrary third-party UID was observed

### Requirement: REQ-VPN-ROUTE-003 — Keep route, validation, and forwarding evidence separate

The implementation MUST classify installed-route presence, Android network
validation, and native TUN forwarding as separate evidence axes before
projecting the user-facing Route state.

#### Scenario: Installed route is healthy

- **GIVEN** the current lifecycle receipt and service-correlated VPN callback exist
- **AND** the observed route families match the intended receipt
- **WHEN** Route state is projected
- **THEN** Route is healthy and no Route warning is shown

#### Scenario: Route exists but Android validation fails

- **GIVEN** the current service-correlated VPN exists and its route families match
- **AND** Android reports validation absent or a captive portal
- **WHEN** connection state is projected
- **THEN** the installed Route remains healthy
- **AND** the validation failure is reported through the separate Network axis with its own provenance

#### Scenario: Route exists but data plane fails

- **GIVEN** the current service-correlated VPN exists and its route families match
- **AND** current-generation TUN evidence reports a terminal forwarding failure
- **WHEN** Route state is projected
- **THEN** the installed Route is not described as a missing VPN network
- **AND** the connection is degraded through the separate Tunnel/data-plane axis with its own provenance

#### Scenario: Callback is still converging

- **GIVEN** establish succeeded for the current generation
- **AND** the service-correlated VPN callback has not yet delivered a complete capabilities and link-properties snapshot
- **WHEN** Route state is projected inside the bounded startup window
- **THEN** Route is checking rather than unavailable
- **AND** checking does not produce a degraded Route warning during that window

### Requirement: REQ-VPN-ROUTE-004 — Bind evidence to one lifecycle generation

The implementation MUST correlate callback, applied-tunnel, route-plan, and
forwarding evidence to the current VPN lifecycle generation and MUST reject
stale or cross-generation combinations.

#### Scenario: Late loss from the retired VPN

- **GIVEN** generation N has been replaced by generation N+1
- **WHEN** a late `onLost` event for generation N arrives
- **THEN** it does not remove or degrade the owned VPN observation for generation N+1

#### Scenario: Handover changes network shape

- **GIVEN** the owned VPN remains established across an underlay handover
- **WHEN** callback capabilities or link properties change
- **THEN** a new coherent callback revision is published for the same VPN lifecycle generation
- **AND** the latest callback-delivered value of the unchanged axis is retained
- **AND** initial evidence remains checking until both callbacks have arrived for that network and generation

#### Scenario: Synchronous network getters are stale or unavailable

- **GIVEN** capabilities and link properties arrive in separate callbacks for the owned VPN
- **AND** synchronous network getters return null or stale data
- **WHEN** callback evidence is reduced
- **THEN** only the delivered callback arguments determine the observation
- **AND** getter results cannot manufacture a mismatch, replace validation, or discard an owned VPN

### Requirement: REQ-VPN-ROUTE-005 — Export causal, privacy-safe provenance

The diagnostics archive MUST export enough bounded categorical evidence to
distinguish owner-observer false negatives, route-plan mismatches, validation
failures, and native forwarding failures without exporting network or app
identifiers.

#### Scenario: False-negative owner observation is exportable

- **GIVEN** the calling-UID default is non-VPN
- **AND** a current service-correlated VPN observation and matching route receipt exist
- **WHEN** a diagnostic archive is created
- **THEN** it records distinct calling-default and owned-VPN observer roles
- **AND** it records the healthy owned-VPN conclusion instead of `vpnPresent=false`

#### Scenario: Required provenance fields

- **WHEN** current-generation route evidence is available
- **THEN** the archive includes observer source, callback state, evidence freshness, intended and observed route families, categorical app-routing shape, applied-tunnel receipt generation, Android validation state, and forwarding-correlation outcome

#### Scenario: Privacy projection

- **WHEN** route evidence is persisted, logged, shared, or archived
- **THEN** it excludes package names, UIDs, IP and DNS addresses, interface names, raw network handles, SSID/BSSID, endpoints, profile secrets, and stable device or network identifiers
- **AND** it uses only enums, booleans, bounded counts or bands, route-family tokens, and ephemeral generations

### Requirement: REQ-VPN-ROUTE-006 — Preserve additive archive compatibility

The implementation MUST preserve decoding of existing diagnostic snapshots and
MUST govern any archive schema or golden change through the repository's
versioning and fixture workflow.

#### Scenario: Legacy snapshot without owned-VPN provenance

- **GIVEN** a legacy snapshot omits the new route-observation fields
- **WHEN** the current application decodes it
- **THEN** the route provenance is reported unavailable or legacy
- **AND** decoding succeeds without manufacturing a healthy or missing-VPN conclusion

#### Scenario: Schema 10 route evidence is additive

- **GIVEN** diagnostics archive schema 9 is the current legacy contract
- **WHEN** `vpnRouteEvidence` is added
- **THEN** the archive schema is version 10
- **AND** the exact schema-10 fixture family is updated only through the governed golden workflow

#### Scenario: No unrelated wire break

- **WHEN** this capability is implemented
- **THEN** the diagnostics-engine JNI wire schema, protobuf settings schema, and external configuration contracts remain unchanged unless a separately reviewed contract change is proven necessary

### Requirement: REQ-VPN-ROUTE-007 — Verify false and true failure paths

The implementation MUST have behavioral regression coverage and device-level
evidence that distinguish an owner-process false negative from a real route or
forwarding failure.

#### Scenario: API 36 device acceptance

- **GIVEN** RIPDPI excludes its own package and a third-party test client is routed through the VPN
- **WHEN** the VPN is started, validated, handed over once, and stopped
- **THEN** observed Route transitions are checking to healthy to unavailable without a false degraded interval from the owner's underlay
- **AND** the diagnostic archive correlates the callback, route receipt, and TUN forwarding generation

#### Scenario: Local validation is not device proof

- **WHEN** JVM tests and static analysis pass without the API 36 scenario
- **THEN** the change reports local validation separately
- **AND** it does not claim physical-device or hosted-CI proof
