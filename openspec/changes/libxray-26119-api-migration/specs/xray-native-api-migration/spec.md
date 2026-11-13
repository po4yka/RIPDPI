## Purpose

Keep the current libXray upstream API usable through the Android managed-runtime
boundary without losing socket protection, cleanup, or private UDP isolation.

## ADDED Requirements

### Requirement: REQ-XRAY-API — Support the current native runtime API

The adapter MUST start, query, and stop a runtime built from the pinned release.
It MUST report an incompatible or absent native API as an error.

#### Scenario: Runtime control succeeds

- **WHEN** a valid configuration starts through the Android adapter
- **THEN** the runtime reports running and can stop through that adapter.

#### Scenario: An API is incompatible

- **WHEN** the loaded artifact lacks the required native entry point
- **THEN** activation fails with an actionable error before traffic is admitted.

### Requirement: REQ-XRAY-PROTECT — Preserve fail-closed socket protection

The native patches MUST protect each non-loopback direct socket before outbound
use when protection is active. A protection failure MUST close the socket and
propagate an error. Loopback and inactive-protection behavior MUST stay valid.

#### Scenario: Protection fails

- **WHEN** an active protection callback refuses a direct socket
- **THEN** that socket sends no outbound traffic and the operation fails.

### Requirement: REQ-XRAY-OWNERSHIP — Retain failed-cleanup ownership

The managed runtime MUST retain ownership and the cleanup error after a failed
start or stop. It MUST reject replacement while cleanup is incomplete and MUST
NOT repeat a consumed native close operation.

#### Scenario: Cleanup fails

- **WHEN** native instance close reports a failure
- **THEN** the owner retains the failure and a later stop reports that failure
  without claiming that the instance was released.

### Requirement: REQ-XRAY-UDP — Keep private UDP sessions isolated

Managed RUF1 UDP associations MUST retain control authentication, exact source
endpoint, nonce, request-ID, DNS-pin, authority-cap, and reply-source checks.
Closing the control or runtime MUST terminate its registered session resources.
Ordinary SOCKS inbounds MUST retain standard protocol behavior.

#### Scenario: A different local sender tries to use an association

- **WHEN** a packet has the wrong source endpoint or nonce
- **THEN** the private association rejects it without forwarding payload.

#### Scenario: A control connection closes

- **WHEN** an authenticated control connection closes
- **THEN** its private UDP socket and rays are closed with bounded cleanup.

### Requirement: REQ-XRAY-PROVENANCE — Verify the new source and artifact

The producer MUST verify source commits, Go module sums, current patch digests,
and the AAR API. It MUST explicitly retire an upstreamed patch and reject drift.

#### Scenario: Source or artifact provenance differs

- **WHEN** a source, patch, module sum, or AAR digest differs from its manifest
- **THEN** preparation or artifact verification fails before packaging.

### Requirement: REQ-XRAY-PLATFORM — Use supported Android dialing APIs

The Android STUN default dialer MUST build with the pinned Go linker checks.
It MUST resolve named IPv6 scopes without root or Android-restricted netlink.
It MUST preserve numeric scopes, caller-owned UDP addresses, custom networks,
and non-Android behavior.

#### Scenario: An IPv6 destination names an interface

- **WHEN** the Android default dialer receives a scoped IPv6 destination
- **THEN** it resolves the interface through a public unprivileged API and dials
  with its numeric scope without changing the caller's address.

#### Scenario: A caller supplies its own network

- **WHEN** a STUN caller supplies a custom network
- **THEN** dialing continues through that network.

### Requirement: REQ-XRAY-VERSION-POLICY — Preserve version compatibility guards

The profile validator MUST recognize the pinned canonical Go pseudo-version.
It MUST accept a successor only when its base release contains the known
REALITY/XHTTP fix. It MUST preserve stable release policy and reject unknown
or malformed version metadata.

#### Scenario: A fixed release has a canonical successor build

- **WHEN** the core module uses a valid successor pseudo-version of a known fixed release
- **THEN** the existing validator accepts that version for REALITY/XHTTP.

#### Scenario: A broken base has a later commit timestamp

- **WHEN** a pseudo-version descends from a release before the known fix
- **THEN** the validator still rejects REALITY/XHTTP regardless of the timestamp.
