## ADDED Requirements

### Requirement: REQ-VPN-1795255851577459-001 — Active VPN payload evidence follows the applied route

A selector with a payload policy SHALL measure the current member through its authenticated applied VPN route. It SHALL verify the expected body length and digest and SHALL use isolated candidate probes for reserves.

#### Scenario: The running member stalls after response headers

- **WHEN** the active VPN sends headers but fails to deliver the full body and an isolated candidate can deliver the expected payload
- **THEN** the active result is a failure and the selector can select a verified reserve after the configured failure threshold

### Requirement: REQ-VPN-1795255851577459-002 — Selection requires current route evidence

The selector SHALL require the same physical network and applied VPN route when writing an automatic selection from a payload pass.

#### Scenario: The network or route changes during a pass

- **WHEN** the physical network, applied member or route lease changes before the selection write
- **THEN** the pass does not authorize an automatic selection
