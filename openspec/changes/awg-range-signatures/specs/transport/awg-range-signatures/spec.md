## Purpose

Carry and execute the AWG 3.1 range-and-signature profile without changing the physical ARM64 padding safety policy.

## ADDED Requirements

### Requirement: REQ-AWG-RANGE — Canonical authenticated header ranges

The client MUST accept canonical decimal uint32 strings as singleton or inclusive header ranges for `awg-3.1-ranges-v1`. It MUST reject overlapping, reversed, malformed or out-of-range intervals before activation. Each transmitted packet MUST use one selected header from its range. Received packets MUST match the corresponding range and existing MAC and cookie authentication.

#### Scenario: Ranged upstream peer

- **WHEN** the client exchanges traffic with the exact AWG 3.1 peer using four disjoint ranges
- **THEN** authenticated traffic succeeds and captured headers belong to their declared intervals

#### Scenario: Invalid ranges

- **WHEN** an import contains numeric JSON headers, overlapping intervals or a noncanonical string
- **THEN** activation fails without selecting a fallback wire shape

### Requirement: REQ-AWG-SIGNATURE — Bounded signature programs

The client MUST compile I1 through I5 from the documented `b`, `r`, `rd`, `rc` and `t` tag grammar. It MUST bound each generated packet to 1280 bytes, reject malformed programs and generate fresh dynamic values for each handshake. Packets MUST precede Jc packets in slot order. Timestamp bytes MUST use the upstream big-endian uint32 Unix time format.

#### Scenario: Dynamic signature

- **WHEN** a valid mixed static and dynamic signature is configured
- **THEN** each handshake emits the declared byte shape in slot order without modifying the authenticated handshake

### Requirement: REQ-AWG-CONTRACT — Complete client contract

The client MUST preserve the wire profile, header strings and signature programs through import, storage, sharing and activation. The cohort fingerprint MUST include the profile and canonical strings. It MUST reject unsupported AWG 3 options rather than discard them. A stale scalar contract MUST require refreshed profile data.

#### Scenario: Producer profile

- **WHEN** the producer supplies a valid profile and matching fingerprint
- **THEN** client activation uses exactly those parameters and a share/import round trip preserves them

### Requirement: REQ-AWG-SAFETY — Preserve padding and evidence boundaries

The client MUST retain S3=S4=0 and cookie authentication. Header protection, custom timers, content padding, random trailers and cookie disabling are outside this profile. Native interoperability MUST use real upstream code. Physical ARM64 acceptance MUST remain unverified until the required device run is observed.

#### Scenario: Unsafe padding

- **WHEN** any import or native configuration sets nonzero S3 or S4
- **THEN** the client rejects it before network activation
