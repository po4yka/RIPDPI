## Purpose

Keep diagnostics usable through configured IP peers when fallback DNS is slow or unavailable.

## ADDED Requirements

### Requirement: REQ-AUDIT-PINNED-FIRST — Defer fallback DNS until pinned attempts fail

Diagnostics transports MUST attempt configured literal IP peers before resolving fallback hostnames. Direct TCP, direct UDP, route experiments, and SOCKS5 UDP MUST preserve this order. Hostname-only targets and hostname fallback after failed pinned attempts MUST remain supported within the existing scan deadline. The original TLS name and socket protection policy MUST remain unchanged.

#### Scenario: Slow fallback DNS with a working pinned peer

- **WHEN** a pinned peer is reachable and fallback hostname resolution would consume the remaining scan deadline
- **THEN** the operation succeeds through the pinned peer without invoking fallback resolution

#### Scenario: Failed pinned peer with hostname fallback

- **WHEN** pinned attempts fail and scan time remains
- **THEN** the transport resolves and attempts fallback hostnames

#### Scenario: Expired scan deadline

- **WHEN** the scan deadline expires before the next socket operation or hostname resolution
- **THEN** the transport reports deadline failure and does not grant a fresh operation budget
