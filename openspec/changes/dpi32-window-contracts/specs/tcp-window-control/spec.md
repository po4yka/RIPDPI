## Purpose

Keep TCP window requests within the socket API domain and show whether the operating system applied each request.

## ADDED Requirements

### Requirement: REQ-WINDOW-HINT — State the hint contract

The implementation MUST define scale as a buffer shift hint, without an exact negotiated scale or first-segment guarantee.

#### Scenario: Explicit shift

- **WHEN** window is 4096 and scale is 2
- **THEN** the buffer request is 16384, clamp is 4096, and the OS chooses negotiated scale.

### Requirement: REQ-WINDOW-BOUNDS — Reject invalid requests

Admission MUST reject zero active CLI requests, values outside the signed socket range, and overflowing products. UI zero MUST remain disabled. Setters MUST prevent signed wrap.

#### Scenario: Overflow

- **WHEN** window is 262144 and scale is 14
- **THEN** admission fails before a zero buffer is sent.

#### Scenario: Signed boundary

- **WHEN** clamp exceeds 2147483647
- **THEN** admission or the setter rejects it instead of applying a tiny clamp.

### Requirement: REQ-WINDOW-AUTO — Preserve automatic scale

A settings round trip MUST preserve absent or automatic scale without changing explicit zero.

#### Scenario: Save absent scale

- **WHEN** a configuration has no scale and settings are saved and read
- **THEN** scale remains automatic.

### Requirement: REQ-WINDOW-OUTCOME — Report option outcomes

The runtime MUST distinguish applied, unsupported, failed, and readback failure. Effective values MUST come from readback. Events MUST exclude payload, addresses, secrets, and file descriptors. Optional failure MUST preserve real payload.

#### Scenario: Unsupported OS

- **WHEN** the OS does not support clamp
- **THEN** an unsupported outcome is available and the stream can deliver its bytes.

### Requirement: REQ-WINDOW-ADMISSION — Reject unavailable YAML

Production admission MUST reject standalone wsize and wssize YAML until a socket executor exists. Library planning MUST remain available.

#### Scenario: Window-only YAML

- **WHEN** production YAML contains wssize
- **THEN** it fails with unsupported step rather than silently omitting it.

### Requirement: REQ-WINDOW-WIRE — Check effective positive windows

Wire assertions MUST use negotiated scale for the exact flow, exclude zero and closing windows, and compare clamp with control after peer data.

#### Scenario: Large effective window

- **WHEN** raw window is 64 and negotiated exponent is 10
- **THEN** the assertion treats it as 65536 bytes, not a tiny clamp.
