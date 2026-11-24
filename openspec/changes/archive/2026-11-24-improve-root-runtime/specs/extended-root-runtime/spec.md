## Purpose

Give users control and accurate runtime status for optional root features in extended Android builds.

## ADDED Requirements

### Requirement: REQ-ROOT-ACCESS — Explicit access and runtime status

The extended settings MUST request access on an explicit user action and show saved authorization separately from the active helper. They MUST distinguish an access timeout from a refusal and allow an explicit retry.

#### Scenario: Reopen settings during a connection

- **WHEN** a user opens root settings during a connection
- **THEN** the screen reports the service state without invoking the root manager

### Requirement: REQ-ROOT-LIFECYCLE — On-demand helper

The service MUST bound helper startup and only request privileged execution for a selected strategy that uses it. It MUST reconcile disabling and helper failure with the active runtime and preserve the VPN barrier through a compatible fallback.

#### Scenario: Disable root with an active VPN

- **WHEN** root mode is disabled during a connection with privileged operations
- **THEN** the service stops those operations and retains the VPN while switching to a compatible ordinary strategy, or reports a safe failure if cleanup cannot finish

### Requirement: REQ-ROOT-ASSETS — Trusted privileged assets

The privileged nfqws2 supervisor MUST verify the executable and the shipped script content against a build-owned trust source before execution. Replacing a file that the app can write MUST NOT substitute the content that runs.

#### Scenario: Asset is changed on disk

- **WHEN** the extracted executable or an executable script has been replaced
- **THEN** the root supervisor refuses the changed content before startup

### Requirement: REQ-ROOT-EDITION — Distribution and capture boundaries

The Google Play package MUST exclude the root settings and privileged code. Packet capture MUST continue to require separate consent and an active VPN to record, with no dependency on root for recording or viewing.

#### Scenario: Use Play on an ordinary device

- **WHEN** the user grants capture consent and connects the VPN
- **THEN** the recording and viewing entry points work without root
