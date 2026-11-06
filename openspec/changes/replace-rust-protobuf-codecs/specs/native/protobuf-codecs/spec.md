## Purpose

Read standard GeoSite and Hunk Protobuf messages without custom wire decoders, while preserving local catalog and stream safety rules.

## ADDED Requirements

### Requirement: REQ-PB-GEOSITE — Standard catalog decoding

The GeoSite reader MUST use the existing geosite.proto schema and accept valid unknown fields, field order, and default values. It MUST reject malformed messages, invalid UTF-8, and unsupported domain enum values. It MUST retain sing-box database support without accepting aliased offsets or truncated records.

#### Scenario: Catalog extension

- **WHEN** a catalog has unknown fixed-width, varint, or length-delimited fields before or inside entries
- **THEN** known rules remain available with the same matching behavior

#### Scenario: Invalid catalog

- **WHEN** a field is truncated, a varint overflows, or a domain kind is unknown
- **THEN** loading fails without publishing a partial catalog

### Requirement: REQ-PB-HUNK — Standard Hunk messages

The Hunk codec MUST use library encoding and decoding. It MUST accept empty messages, unknown fields, field order, and last-value semantics for repeated data fields. It MUST reject invalid field tags, malformed lengths, and overflowing varints.

#### Scenario: Extended Hunk

- **WHEN** a Hunk includes an unknown field before or after data
- **THEN** the decoder returns the data without the extension bytes

#### Scenario: Default message

- **WHEN** an uncompressed frame has a zero-length Protobuf message
- **THEN** the decoder returns an empty payload without panic or stream termination

### Requirement: REQ-PB-FRAMING — Bounded and explicit stream failures

The transport MUST accept only uncompressed gRPC frames, enforce its message-size bound on encoding and decoding, retain incomplete input until more bytes arrive, and report malformed or truncated input as an I/O error rather than clean EOF. It MUST NOT log payload bytes.

#### Scenario: Unsupported compression or excessive size

- **WHEN** a frame declares a nonzero compression flag or an excessive message size
- **THEN** decoding fails before waiting for its body

#### Scenario: Truncated stream

- **WHEN** the incoming stream ends with an incomplete prefix or body
- **THEN** the reader receives an unexpected-EOF error after any complete earlier payload

#### Scenario: Split messages

- **WHEN** frames arrive one byte at a time or several frames arrive together
- **THEN** complete payloads are returned in order without losing buffered data
