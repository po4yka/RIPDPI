## Purpose

Preserve WebTunnel configuration boundaries when Tor passes transport options through SOCKS authentication fields.

## ADDED Requirements

### Requirement: REQ-TUN09-BOUNDARIES — Preserve decoded arguments

The implementation MUST decode escaped keys and values without serializing them into a whitespace-delimited bridge line. Unknown option values MUST remain opaque to known options.

#### Scenario: Receive extension arguments

- **WHEN** a well-formed unknown option contains spaces or escaped separators
- **THEN** known WebTunnel options keep their original values and the extension is ignored.

### Requirement: REQ-TUN09-GRAMMAR — Match the reference argument grammar

The implementation MUST find the first unescaped key delimiter, accept escaped bytes and raw equals signs in values, and reject empty entries, empty keys, missing delimiters and incomplete escapes. Repeated option keys MUST use the first value, as goptlib Args.Get does.

#### Scenario: Parse repeated or malformed arguments

- **WHEN** a key occurs twice or an entry has invalid structure
- **THEN** the first value is used or parsing fails before a bridge connection is attempted, respectively.

### Requirement: REQ-TUN09-SCOPE — Retain the supported transport path

The implementation MUST retain valid bridge-line parsing, managed PT startup, TLS verification and HTTP Upgrade behavior. Existing standalone PT rejection and Tor bridge activation MUST remain unchanged.

#### Scenario: Use an existing Tor WebTunnel bridge

- **WHEN** valid transport arguments are supplied through the supported Tor path
- **THEN** the helper receives the same endpoint, TLS identity, pin and secret path as before.
