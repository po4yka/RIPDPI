# vless-wire-input-validation Specification

## Purpose
Validate VLESS addon input before payload delivery and enforce the body mode supported by each consumer.

## Requirements

### Requirement: REQ-ADDONS — Validate bounded protobuf addons

Every VLESS request and response decoder MUST validate the complete addons protobuf block before it accepts the header. The decoder MUST reject invalid tags, malformed lengths, varint overflow, invalid UTF-8 strings according to the pinned Xray protobuf behavior. It MUST treat known fields with an unexpected wire type as unknown fields, accept valid unknown fields, keep the outer 255-byte limit, and apply the last correctly encoded Flow value.

#### Scenario: Malformed addons do not become payload

- **WHEN** a complete outer block contains an invalid tag, truncated known or unknown field, invalid Flow UTF-8 or overflowing varint
- **THEN** header decoding fails without delivering body bytes or logging peer addon contents.

#### Scenario: Valid extensions remain compatible

- **WHEN** a valid block contains Seed or unknown varint, fixed-width, length-delimited or group fields
- **THEN** the header is accepted and the following payload is unchanged.

### Requirement: REQ-PARTIAL — Preserve lazy header state

The production lazy response stream MUST preserve incomplete header and addon bytes across partial reads and read cancellation. It MUST return a terminal error before payload on malformed addons or truncated EOF and keep writes available before the peer sends a response.

#### Scenario: Cancel a partial addon read

- **WHEN** a read is cancelled after a header or addon prefix and then resumed
- **THEN** the same stream validates the completed block once and yields exactly the original payload.

### Requirement: REQ-ORIGIN-FLOW — Reject unsupported body modes

The plain TCP origin MUST accept only an empty decoded wire Flow. It MUST reject every nonempty Flow before the authenticated callback, upstream connect or success response. Configuration aliases MUST NOT be applied to wire Flow.

#### Scenario: An authenticated peer requests an unsupported flow

- **WHEN** a valid UUID and TCP command carry Vision, a configuration alias, whitespace or an unknown nonempty Flow
- **THEN** the origin returns a fixed error, opens no target connection and emits no success response.

#### Scenario: Empty flow and valid extensions relay normally

- **WHEN** a valid UUID and TCP command have empty Flow and valid unknown addon fields
- **THEN** the origin relays application bytes and retains UUID, command and destination checks.

### Requirement: REQ-EAGER-CANCEL — State eager cancellation limits

The eager response helper MUST document that cancellation can consume a partial header and that its stream MUST be discarded instead of retried. Production callers MUST retain the lazy stateful wrapper.

#### Scenario: A caller needs resumable response parsing

- **WHEN** the caller can cancel a response read and keep the stream
- **THEN** the documented API directs it to the stateful response wrapper.
