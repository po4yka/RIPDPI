# multidisorder-delivery Specification

## Purpose
Keep native multidisorder TCP delivery safe across configuration, privileged emission and helper transport failures.

## Requirements

### Requirement: REQ-MD-REPLAY — Stop retry after possible delivery

The runtime MUST prevent automatic payload replay when a multidisorder send can have delivered genuine bytes. Known progress MUST be preserved when available.

#### Scenario: Failure after delivery starts

- **WHEN** raw emission or a delivered helper command fails after payload delivery can have started
- **THEN** the execution reports possible payload commit and retry classification surfaces the failure without replay.

### Requirement: REQ-MD-BULK — Bound helper payloads with peer negotiation

The helper transport MUST accept negotiated multidisorder payloads up to its advertised byte and segment limits. It MUST reject oversized or invalid requests before mutation. A peer that does not advertise this command MUST retain the control-frame limit.

#### Scenario: Large request to a current helper

- **WHEN** a valid 16384-byte payload with at least three contiguous regions is sent to a supporting helper
- **THEN** request framing and receipt preserve all payload bytes.

#### Scenario: Legacy peer or invalid range

- **WHEN** the peer lacks bulk multidisorder support or a request exceeds its limits or has invalid ranges
- **THEN** the command is rejected before privileged dispatch.

### Requirement: REQ-MD-SETTINGS — Make grouped settings deterministic

The configuration MUST reject conflicting settings within a multidisorder run before sending. Accepted settings MUST be independent of resolved marker order, duplicate positions and empty marker regions. Existing first-marker-only grouped settings MAY remain valid if defaults on other markers mean inheritance.

#### Scenario: Conflicting marker settings

- **WHEN** markers contain incompatible original flags or delay settings
- **THEN** configuration validation returns an explicit error instead of discarding a value.

#### Scenario: Reordered or duplicate markers

- **WHEN** markers are sorted, duplicated or filtered to nonempty regions
- **THEN** accepted grouped settings retain their declared meaning.

### Requirement: REQ-MD-QUEUE — Preserve original flags and stream state

The privileged sender MUST account for genuine bytes in the repair send queue without an immediate ordinary payload write after raw emission. It MUST report repair-restoration failures and preserve the original sequence binding and stream continuation.

#### Scenario: Genuine raw regions with explicit flags

- **WHEN** the sender emits a valid reversed run
- **THEN** it queues the original bytes as sent while repair remains active and restores repair state before returning.

### Requirement: REQ-MD-GATES — Describe the actual capability failure

Documentation MUST state that native multidisorder requires Linux or Android raw sockets and TCP_REPAIR, and that absent capabilities do not imply automatic split fallback. It MUST distinguish a planning fallback from successful multidisorder execution.

#### Scenario: Missing privileges

- **WHEN** a configured multidisorder chain lacks required capabilities
- **THEN** documented behavior matches the explicit capability failure and does not promise unperformed raw emission.
