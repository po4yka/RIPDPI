# ECH client safety

## Purpose

Keep ECH connection privacy, DNS discovery, rejection recovery and cancellation consistent across owned clients.

## ADDED Requirements

### Requirement: REQ-ECH-REAL — Preserve Real ECH intent

An unusable Real configuration MUST fail before a cleartext inner SNI is sent. Accepted connections MUST prove ECH acceptance.

#### Scenario: Preserve Real ECH intent

- **WHEN** Only an unsupported KEM is configured
- **THEN** the connection fails without an ordinary inner-name ClientHello

### Requirement: REQ-ECH-REJECT — Read only authenticated retry evidence

A TLS failure MUST NOT expose ECH retry data unless the backend authenticated ECH rejection.

#### Scenario: Read only authenticated retry evidence

- **WHEN** The peer sends an ordinary fatal TLS alert
- **THEN** the original failure is returned without an abort or retry

### Requirement: REQ-ECH-SVCB — Select compatible ECH records

Service selection MUST ignore HTTPS records with unsupported or invalid mandatory parameters.

#### Scenario: Select compatible ECH records

- **WHEN** The preferred HTTPS record has an unknown mandatory key and another record is compatible
- **THEN** only the compatible record supplies ECH

### Requirement: REQ-ECH-ALIAS — Follow bounded HTTPS aliases

ECH discovery MUST follow HTTPS AliasMode with bounded hops and cycle detection.

#### Scenario: Follow bounded HTTPS aliases

- **WHEN** The origin aliases a ServiceMode name with an ECH config
- **THEN** the config is returned; cycles and unavailable services terminate

### Requirement: REQ-ECH-RETRY — Retry ECH once

A connection with authenticated ECH retry configs MUST retry at most once on a new transport and preserve trust, endpoint, deadline, and cancellation. Failed authentication MUST NOT authorize ECH retry configs or cleartext fallback. Existing carrier fallback MUST preserve Real ECH intent and certificate verification.

#### Scenario: Retry ECH once

- **WHEN** A trusted server rejects a stale key and supplies a fresh config
- **THEN** the second transport accepts ECH before application data; a second rejection fails

### Requirement: REQ-ECH-CANCEL — Cancel active ECH I/O

Cancellation MUST stop DoH, system DNS and native ECH work, release owned sockets, and prevent a later retry.

#### Scenario: Cancel active ECH I/O

- **WHEN** A DNS or TLS fixture stalls and the caller cancels
- **THEN** the operation stops promptly without a continued network operation or reusable request handle
