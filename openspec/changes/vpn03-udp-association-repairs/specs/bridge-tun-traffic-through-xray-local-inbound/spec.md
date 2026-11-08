## Purpose

Keep the managed Xray local UDP hop compatible with the tunnel resolved-domain authority contract.

## ADDED Requirements

### Requirement: REQ-VPN03-XRAY-AUTHORITY — Preserve domain and pinned destination

The managed Xray UDP handoff MUST carry both the domain used for routing and the resolved numeric destination used for delivery. It MUST preserve each destination authority across multiplexing and return the correct synthetic source. A public or unmanaged SOCKS listener MUST NOT gain the managed private extension implicitly.

#### Scenario: Two domains share a numeric endpoint

- **WHEN** one TUN source sends opaque UDP through managed Xray to two mapped domains at the same IP and port
- **THEN** each domain selects its own routing policy and sends to its pinned numeric endpoint
- **AND** reverse-order replies retain the correct synthetic source without a new DNS resolution.
