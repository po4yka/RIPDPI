## ADDED Requirements

### Requirement: REQ-VPN-1795264616970883-001 — Confirm the automatic reload against the running configuration

An automatic reload SHALL require a newer applied revision of the same runtime and the requested group and member. Reconciliation SHALL preserve later selection changes and the running VPN barrier.

#### Scenario: The service rejects a reload during handover

- **WHEN** the reload is rejected and the runtime is still applying a revision
- **THEN** the selector reports a timeout if the revision is still applying when the wait window ends, and reconciles when a settled revision becomes available for the current selection

#### Scenario: A reload times out

- **WHEN** the reload times out
- **THEN** the outcome is distinct from rejection and rollback requires a confirmed live member

### Requirement: REQ-VPN-1795264616970883-002 — Confirm recovery through the applied VPN

The selector SHALL start its cooldown for a VPN payload move after it observes the applied replacement. The selector SHALL confirm recovery after the replacement's live VPN route delivers the verified payload under the current probe scope.

#### Scenario: The applied reserve cannot complete the body

- **WHEN** a fresh reserve succeeds and the VPN applies it but its active path stalls
- **THEN** the cooldown protects against rapid switching and the selector keeps recovery unconfirmed
