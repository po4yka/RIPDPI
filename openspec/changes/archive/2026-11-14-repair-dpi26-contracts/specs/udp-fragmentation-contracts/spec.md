## Purpose

Keep UDP fragmentation errors and configuration consistent across the UI, native groups and helper transport.

## ADDED Requirements

### Requirement: REQ-DPI26-F1 — Preserve safe fragment failure boundaries

The implementation MUST preserve UDP error kind and emission boundary through helper IPC. Plain fallback MUST require a confirmed pre-send InvalidInput. Unknown or possibly emitted failures MUST remain terminal.

#### Scenario: Invalid split before emission

- **WHEN** a UDP split is rejected before any packet can leave
- **THEN** direct and helper execution retain a safe InvalidInput for the existing plain fallback.

#### Scenario: Uncertain or legacy failure

- **WHEN** a fragment may have left or the reply lacks valid boundary metadata
- **THEN** the runtime does not replay the original datagram.

### Requirement: REQ-DPI26-F2 — Preserve the UDP IPv4 ID policy

The converter MUST retain the selected IPv4 ID policy in the synthetic UDP group without bypassing sender ID safety.

#### Scenario: Selected policy

- **WHEN** a UI profile selects seq, seqgroup, rnd or zero
- **THEN** the UDP group retains that policy and the final sender retains ID safety.

### Requirement: REQ-DPI26-F3 — Require root-enabled fragmentation drafts

UI validation MUST reject TCP and UDP IP fragmentation when root mode is off. It MUST NOT enable root. Ordinary non-root strategies MUST remain valid.

#### Scenario: Root disabled

- **WHEN** a VPN draft contains fragmentation with root mode off
- **THEN** validation returns an explicit root requirement before startup.

#### Scenario: Valid draft

- **WHEN** root is enabled for fragmentation or an ordinary strategy uses no raw step
- **THEN** existing valid drafts remain valid.
