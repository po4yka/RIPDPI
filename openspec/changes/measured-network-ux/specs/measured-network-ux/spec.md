## Purpose

Make network measurements and connection actions understandable while preserving factual evidence, local privacy and durable user intent.

## ADDED Requirements

### Requirement: REQ-SCOPE — Explain scan measurement scope

The app MUST localize direct/raw and active/in-path scope labels and explain actual lifecycle effects before a scan and on its result, while retaining machine-readable path contracts.

#### Scenario: Direct measurement

- **WHEN** a user selects a direct-path scan
- **THEN** the UI explains interruption of an active RIPDPI VPN or proxy before probing and only promises restoration supported by that workflow.

#### Scenario: Active path unavailable

- **WHEN** there is no eligible active RIPDPI runtime or its VPN route/local proxy listener is unavailable
- **THEN** in-path scanning has a factual unavailable reason rather than implying universal reachability.

### Requirement: REQ-METRICS — Explain measured metrics and freshness

The app MUST associate supported metrics with units, short consequences, applicable aggregation/sample window and evidence age. It MUST distinguish absent, partial, fresh and stale evidence without invented scores or samples.

#### Scenario: Empty and stale metrics

- **WHEN** telemetry is unavailable or old
- **THEN** the screen preserves no-data/partial states and labels old evidence instead of presenting it as current health.

### Requirement: REQ-CONFIG — Show factual configuration and recovery

The app MUST distinguish saved settings from the currently applied runtime configuration, show factual lifecycle states, and offer relevant repair or reconnect actions with truthful consequences.

#### Scenario: Configuration changes while connected

- **WHEN** saved configuration differs from the active runtime snapshot
- **THEN** the UI identifies the unapplied change; cancelling a reconnect preserves coherent saved and running state.

#### Scenario: Permission or startup failure

- **WHEN** activation is denied, cancelled, starting, stopping or failed
- **THEN** state and guidance describe the actual condition and do not claim protection or successful connectivity.

### Requirement: REQ-SEARCH — Search grouped profiles

The app MUST search actual diagnostic and saved relay profiles with meaningful existing group labels, preserve selection on dismissal and expose an accessible empty-filter state.

#### Scenario: Search and dismiss

- **WHEN** the user searches, filters or closes a profile picker without selecting
- **THEN** the prior selection remains and no runtime change occurs.

### Requirement: REQ-EXPORT — Preview and deliberately export evidence

The app MUST present export scope and supported redaction before diagnostic summary/archive share or save, default to privacy-preserving redaction, and surface preparation failure without launching export.

#### Scenario: Cancel or preparation failure

- **WHEN** the user cancels preview or export preparation fails
- **THEN** no share/save is launched and any sensitive preview state is cleared or retained only for an explicit retry as appropriate.

#### Scenario: Redacted export

- **WHEN** the user confirms a redacted export
- **THEN** preview and actual output use the fixed supported redacted/unlinkable policy; raw endpoint identifiers are not silently included.

#### Scenario: Prepared snapshot changes or delayed handoff

- **WHEN** source data changes after preview, a picker is recreated, or a receiving application reads the shared URI later
- **THEN** confirmation launches at most once with the exact prepared bytes and summary; a checked managed lease preserves eligible handoff files across process restart until its bounded expiry.

#### Scenario: Export clock eligibility

- **WHEN** the same-boot elapsed lifetime reaches three days, the observed wall clock moves backwards, or boot identity changes or cannot be verified
- **THEN** the old prepared lease cannot be consumed or opened; no clock adjustment extends its lifetime, and a new export can be prepared when the required clock proof is available.

#### Scenario: Replaced or late preparation

- **WHEN** preview is cancelled, replaced or cleared while preparation completes late
- **THEN** the stale generation cannot launch export, owned artifact/record cleanup is serialized, and cancellation or cleanup failure is preserved.

#### Scenario: Preparation finishes with a long log excerpt on a small display
- **WHEN** a local export sheet transitions from Preparing to Ready with a long log excerpt
- **THEN** its available height remains stable and its confirm/cancel actions remain visible
- **AND** the preview scrolls independently without truncating the saved file

### Requirement: REQ-PAUSE — Persist timed connection pause intent

The app MUST allow an active eligible connection to pause for a chosen bounded duration, preserve a local resume intent before stopping, display its real deadline, and safely resume or explain a failed resume after process interruption. Explicit disconnect/cancel MUST invalidate pending resume. It MUST respect revoked VPN consent and operating-system background limits.

#### Scenario: Process interruption

- **WHEN** the app process restarts with a persisted unexpired pause
- **THEN** the countdown and resume scheduling are restored from local state without fabricating an active connection.

#### Scenario: Saved edits while paused

- **WHEN** the user edits ordinary saved settings or profiles during a pending pause
- **THEN** the pause remains and resumes its chosen mode using current saved configuration. Explicit Start/Stop, activation/deletion and reset supersede the pending intent.

#### Scenario: Deadline or cancellation

- **WHEN** the deadline arrives or the user explicitly cancels the pause
- **THEN** cancellation atomically invalidates that intent; deadline delivery starts only a matching eligible lease, and the intent is cleared only after a positive matching applied-runtime acknowledgement. A stale callback never restarts a newer or cancelled intent, and Android-delayed or denied recovery is displayed honestly.

#### Scenario: Checked pause and cleanup

- **WHEN** pause persistence or native cleanup fails
- **THEN** an unpersisted request keeps the active runtime; cleanup-pending never claims Paused. Paused foreground reconstruction creates no native/TUN/protect/selector/probe resources.

#### Scenario: Journal recovery and newer intent

- **WHEN** profile mutation recovery runs across a process interruption or legacy journal migration
- **THEN** required typed provenance and a durable expected-generation fence protect the current intent before replay. Recovery and compensation cannot invalidate or resurrect a newer pause; unknown legacy provenance is not silently defaulted.

#### Scenario: Explicit resume after clock or boot change

- **WHEN** automatic timing proof is unavailable after a clock or boot change
- **THEN** automatic recovery remains deferred, while an eligible explicit Resume now action can resume the recorded chosen mode using current settings without declaring the old deadline valid. Only its positive matching applied acknowledgement clears the pending pause.

### Requirement: REQ-PROFILES — Persist useful selections and measure automatic choice

The app MUST persist favorite and successfully used recent relay profiles locally, and offer automatic selection using successful actual payload URL-tests with a visible measurement basis. It MUST handle no profiles, failed/unavailable/stale measurements and deleted profiles honestly, without synthetic latency or runtime events.

#### Scenario: Successful measured choice

- **WHEN** the user requests a measured automatic choice
- **THEN** eligible actual profiles are probed through the existing protected payload probe contract and selection is based on successful observed latency.

#### Scenario: Compare successful HTTP measurements in one environment

- **WHEN** the user requests Check and select fastest for the current saved catalog and probe URL
- **THEN** the app checks candidates sequentially, selects the lowest successful observed HTTP latency from that one operation, preserves catalog order for equal results, and applies only the exact winning measurement lease. Cancellation, cleanup pending, a changed network or policy, a newer command, or no successful result preserves the current selection and records no successful history.

#### Scenario: Failed measurement or deleted profile

- **WHEN** all measurements fail or a saved profile is deleted
- **THEN** the current valid selection is preserved, unavailable choice is explained, and favorite/recent metadata does not resurrect the deleted profile.

#### Scenario: Activation authority and successful history

- **WHEN** an explicit profile or selector activation follows a pause
- **THEN** only its original typed, mode-bound start-capable receipt can authorize the new attempt; a Stop/Reset receipt or forged same-generation envelope cannot start it. The exact positive acknowledgement atomically confirms Running and records the actually applied profile once. Failure terminates that claim and rejects another attempt's replay; an identical completed acknowledgement remains idempotent.

#### Scenario: Automatic selection and active group

- **WHEN** a measured automatic result, DNS refresh, handover or inactive group change is delivered
- **THEN** it cannot acquire a newer manual activation receipt or manufacture recent-use history. Only the persisted active selector group may reload the active runtime, and a newer manual choice or catalog revision rejects an older result.

#### Scenario: Provider replacement and interrupted persistence

- **WHEN** an explicit selector, standalone AmneziaWG or WARP activation replaces another provider
- **THEN** one original activation reservation owns the provider after-images and selector publication; recovery cannot replay those pointers over a newer command or manufacture a runtime acknowledgement.

#### Scenario: Cancellation after activation or stop reservation

- **WHEN** the caller is cancelled after its activation is reserved but before the receipt is returned, or after its conditional Stop is reserved
- **THEN** activation cleanup retains the original receipt and restores only its owned before-image, and the captured Stop completes its dispatch even if metadata clearing fails; stale deactivation cannot reserve Stop against a newer full authority snapshot.

#### Scenario: Recovery of a previously applied runtime

- **WHEN** a stopped runtime is reconstructed or a startup fallback is requested
- **THEN** continuation requires the original verified applied lineage or the exact permitted ordinary-start fallback lease, and cannot borrow a later Start, measured activation, Stop, Reset or Pause intent.

### Requirement: REQ-ACCESSIBILITY — Preserve Android presentation and privacy

The implementation MUST use existing RDS tokens, support all ten locales, readable light/dark and large-font/RTL layouts, accessible actions and Android Back/dismiss semantics. Persisted state MUST remain app-private and excluded from system backup; exported sensitive information MUST remain user-controlled.

#### Scenario: Large font and RTL

- **WHEN** the new controls are rendered at accessibility font scale or in RTL
- **THEN** labels, consequences, selection and actions remain readable and operable without relying on color alone.


### Requirement: Measured selection cannot gain fresh authority during recovery

A measured selection SHALL validate its captured scope and reserve the original explicit activation before preparing its persistence journal. Journal replay SHALL preserve that exact reservation and SHALL NOT mint, bind or dispatch activation or record recent use after the transient measurement lease is lost. A new explicit user action SHALL be required for activation after reconstruction. The linearized reservation SHALL perform only synchronous state checks and persistence; profile DAO and native work SHALL remain outside it.

#### Scenario: Process stops between measurement reservation and journal preparation
- **WHEN** the measured selection has reserved its explicit activation but its journal has not been prepared
- **THEN** persisted intent remains Stopped/Unbound and reconstruction does not start a runtime or add recent use

#### Scenario: Process stops after measured journal preparation
- **WHEN** recovery completes a measured selection journal without its original transient measurement lease
- **THEN** it may finish selection/catalog persistence and does not create or dispatch another activation capability

#### Scenario: Failed preparation races with a newer command
- **WHEN** measured journal preparation or persistence fails after another command supersedes the reservation
- **THEN** compensation preserves the newer command and reports the original failure without dispatch or recent use


### Requirement: Measured activation preserves its original physical network fence

Measured activation SHALL use one process-owned physical INTERNET + NOT_VPN observer and one immutable transient token with a nonzero registration generation and monotonic event epoch. It SHALL require complete current callback state and a usable, unblocked, nonsuspended physical path. Android validation/captive flags SHALL remain diagnostic fingerprint evidence and SHALL NOT independently prevent explicit local/LAN checks. Measurement completion, reservation, bind/claim and positive ACK SHALL compare the original token; no fresh baseline SHALL replace it.

#### Scenario: The measured command establishes its own VPN
- **WHEN** the exact measured command creates a VPN default network or initializes its underlay binder while physical network A is unchanged
- **THEN** those owned changes do not invalidate the physical token and the exact positive ACK may record use once

#### Scenario: Physical network changes and returns
- **WHEN** physical callbacks observe A to B to A during a measured attempt
- **THEN** the advanced event epoch rejects the old result or ACK even when the final fingerprint equals A

#### Scenario: Android validation fails but an explicit target is reachable
- **WHEN** an unvalidated or captive physical path remains usable and an explicit local/LAN check receives a complete successful HTTP response
- **THEN** validation/captive flags alone do not reject measurement or the exact measured activation

#### Scenario: Physical observation is unusable or belongs to an old registration
- **WHEN** callback initialization is incomplete, the path is blocked/suspended/unusable, or a callback belongs to a retired registration
- **THEN** no valid current token is manufactured and no activation or recent-use ACK is accepted


### Requirement: Measured activation uses the exact checked policy and consumed configuration

Native and selector measurement SHALL return a private identity of its exact native start configuration. Only owned SOCKS endpoint/socket-protection differences SHALL be normalized. TLS, QUIC, experiment, profile/secret-derived and transient helper launch inputs SHALL remain significant. Binding SHALL capture full immutable post-selection requested inputs outside authority/store locks; service resolution SHALL consume those captured inputs. Claim SHALL compare the captured requested identity and positive ACK SHALL compare actual native consumption with the measurement proof before confirmation or recent use.

#### Scenario: Policy changes after binding
- **WHEN** TLS, QUIC, relevant experiment flags or DNS policy differ in the captured service attempt after registry binding
- **THEN** claim/native start rejects the stale proof without adding history

#### Scenario: Live settings return but a different configuration was consumed
- **WHEN** settings return from A to B to A but the native ready evidence contains B
- **THEN** positive ACK rejects B and cleans the owned attempt without adding history

#### Scenario: Exact measured configuration is applied
- **WHEN** the exact intended profile and policy are consumed with only owned SOCKS/socket-protection differences
- **THEN** the exact measured command may acknowledge once and an identical completed ACK remains idempotent

#### Scenario: A mode binding is replaced or cancelled
- **WHEN** an attempt is superseded, rejected, cancelled, failed, stopped or replaced
- **THEN** its exact process-local measured proof is retired and a delayed ACK cannot reuse it

### Requirement: REQ-TUN-REFRESH — Ignore unused direct DNS inputs during refresh

The runtime MUST normalize direct resolver candidates and underlay generation away when no usable DIRECT rule can select direct DNS. It MUST retain the split policy, encrypted bootstrap pins and proxy DNS behavior. Effective DIRECT rules MUST retain their underlay guards and refresh on changed consumed inputs. A retained descriptor or VPN capability MUST NOT be described as proof of installed routes or absence of a platform replacement gap.

Malformed or oversized unused underlay input MUST NOT affect policy coverage or effective signature. Validation of consumed DIRECT underlay and encrypted bootstrap inputs MUST remain intact.

#### Scenario: Underlay epoch changes without effective DIRECT rules
- **WHEN** an empty, tunneled, blocked or constrained non-direct-DNS policy receives different underlay candidates or generation
- **THEN** its effective DNS signature remains unchanged, no no-op TUN rebuild is requested and proxy DNS policy and bootstrap pins remain present

#### Scenario: Underlay epoch changes for usable DIRECT rules
- **WHEN** a DIRECT rule with BOTH network and no IP/port constraints can select direct DNS and its consumed underlay inputs change
- **THEN** the runtime retains candidate and generation guards and requests the actual required refresh

#### Scenario: Effective Android Builder inputs change
- **WHEN** routes, DNS, app policy, proxy, MTU, metered state, DHT exclusions or applied underlying networks require a different Android interface
- **THEN** the runtime uses the actual replacement path and keeps native readiness separate from generation-matched route evidence without promising an unverified zero-gap handover

### Requirement: REQ-OWNED-XRAY-DNS — Isolate native Xray acceptance DNS

Native Xray acceptance MUST use the independently owned peer for its encrypted resolver and MUST retain default-deny routing for every unowned target. The fixture MUST validate bounded DNS-message requests and return deterministic DNS responses without public upstream access. Production resolver and failover behavior MUST remain unchanged.

#### Scenario: Owned background DNS preserves the tested producer
- **WHEN** the positive native Xray case performs its original DNS payload check
- **THEN** its configured resolver is the owned DoH endpoint and the original UDP receipt and packet-counter assertions run without an unrelated public-resolver failure

#### Scenario: Malformed or unowned request
- **WHEN** a malformed, oversized or unsupported DoH request arrives, or Xray traffic targets an unowned endpoint
- **THEN** the handler rejects the invalid request and the peer retains its existing default blackhole routing

### Requirement: REQ-PROXY-READY — Publish the listener before readiness

The native proxy SHALL publish its listener address and running metadata before a runtime-ready event or callback can be observed. Snapshot readers SHALL acquire the running publication before reading dependent listener metadata. Startup timeouts and endpoint validation SHALL remain unchanged.

#### Scenario: A synchronous readiness callback immediately reads telemetry
- **WHEN** the actual native readiness observer reads the proxy snapshot before `mark_running` returns
- **THEN** the snapshot reports the running state and bound listener address
- **AND** callback execution holds no telemetry or observer lock

### Requirement: REQ-PROBE-LAN-GRANT — Own distinct-UID probe permissions

The Android test probe APK SHALL declare and obtain its own local-network permission on API37 before the owned LAN acceptance begins. The grant helper SHALL check permission for the named package, not the instrumentation caller UID. Production permission policy and negative routing assertions SHALL remain unchanged.

#### Scenario: A distinct test UID probes the owned LAN sentinel
- **WHEN** the API37 test probe is prepared for the owned Xray acceptance
- **THEN** its manifest declares ACCESS_LOCAL_NETWORK and its package permission is granted
- **AND** the probe remains distinct from the target app UID
