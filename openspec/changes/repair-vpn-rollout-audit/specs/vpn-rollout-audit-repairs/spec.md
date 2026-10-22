## Purpose

Prevent credential disclosure and preserve correct VPN runtime and subscription behavior during rollout.

## ADDED Requirements

### Requirement: REQ-SHARE-CREDENTIALS Remove subscription credentials from public exports
The SHARE exporter MUST remove subscription links, bearer tokens, mirrors and member credentials. FULL exports MUST preserve their original values.

#### Scenario: Subscription URL contains a credential in an arbitrary component
- **GIVEN** a subscription URL with a path, query, fragment, userinfo or opaque credential
- **WHEN** the user creates a SHARE export
- **THEN** the export contains no subscription URL or bearer credential and retains non-secret group metadata

### Requirement: REQ-RELAY-DNS Resolve relay names through the protected underlay
Native relay VPN mode MUST resolve hostnames with the existing Android underlying-network callback and MUST propagate resolution failures without a system DNS fallback.

#### Scenario: Resolve a named relay while VPN protection is required
- **GIVEN** an active VPN callback and a hostname relay endpoint
- **WHEN** the native relay resolves the endpoint
- **THEN** it uses callback addresses, rejects invalid or empty results, and propagates stale-underlay errors

### Requirement: REQ-RUNTIME-CLEANUP Retain runtime ownership until shutdown completes
An unexpected component exit MUST stop and join all surviving VPN runtimes. Pending cleanup MUST retain ownership and prevent unsafe restart.

#### Scenario: An upstream exits while the local proxy is active
- **GIVEN** a VPN session with a running proxy and upstream
- **WHEN** either component exits unexpectedly
- **THEN** all surviving components stop and release resources before a replacement session starts

### Requirement: REQ-SUBSCRIPTION-REFRESH Schedule new long-lived subscriptions
New ordinary subscriptions MUST enable automatic refresh and schedule periodic work after import. Existing explicit settings MUST remain unchanged. Bootstrap tokens MUST NOT be fetched by automatic refresh.

#### Scenario: First subscription import after application startup
- **GIVEN** no previously scheduled subscription work
- **WHEN** a new ordinary subscription is imported
- **THEN** its persisted auto-update flag is true and periodic work uses the persisted group state

### Requirement: REQ-RECIPIENT-COMPATIBILITY Preserve supported server candidates
Recipient import MUST resolve bounded acyclic selector references or return an explicit unsupported result. Unsupported candidates MUST NOT silently remove supported relay members. An unsupported-only payload MUST NOT be reported as an active supported VPN profile.

#### Scenario: Deployment bundle includes a nested research selector
- **GIVEN** supported VLESS candidates and a nested Snell evaluation selector
- **WHEN** the bundle is imported or refreshed
- **THEN** supported candidates are retained with unsupported candidates surfaced, or the whole update is rejected without changing the prior working group

### Requirement: REQ-COMPATIBILITY-EVIDENCE Record backend protocol coverage
Compatibility evidence MUST identify the exact deployment source and distinguish parser tests, host protocol tests, Android tests and live deployment acceptance.

#### Scenario: Evaluate all deployment protocol surfaces
- **GIVEN** the current deployment registry and emitters
- **WHEN** compatibility is checked
- **THEN** each core, tactical and research transport has an explicit supported or unsupported boundary without claiming unrun device or server acceptance
