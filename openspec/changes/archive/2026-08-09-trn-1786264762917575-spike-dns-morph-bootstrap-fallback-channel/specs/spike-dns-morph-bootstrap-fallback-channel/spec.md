## Purpose

Define the observable completion contract for Spike: DNS-Morph bootstrap as fallback bootstrap channel. DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the transport model: the handshake uses DNS port 53 while the data plane uses any underlying transport. This provides a distinct bootstrap surface whose behavior depends on middlebox port-53 handling and active L7 fingerprinting. No mature Android-targeting fork exists yet. The spike validates whether the bootstrap shim is buildable on Android and whether controlled external clients can complete the roughly 80-query type-A handshake on representative resolver paths

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917575-001 — ripdpi-dns-morph crate compiles for all 4 Android ABIs

The RIPDPI implementation MUST satisfy this portfolio criterion: ripdpi-dns-morph crate compiles for all 4 Android ABIs.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that ripdpi-dns-morph crate compiles for all 4 Android ABIs

### Requirement: REQ-TRN-1786264762917575-002 — Bootstrap completes against a synthetic DNS-Morph bridge in test-lab/dns/ scena…

The RIPDPI implementation MUST satisfy this portfolio criterion: Bootstrap completes against a synthetic DNS-Morph bridge in test-lab/dns/ scenario (~3–8 s end-to-end per paper).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Bootstrap completes against a synthetic DNS-Morph bridge in test-lab/dns/ scenario (~3–8 s end-to-end per paper)

### Requirement: REQ-TRN-1786264762917575-003 — Normal-query compatibility verified against the bridge

The RIPDPI implementation MUST satisfy this portfolio criterion: Protocol-behavior validation confirms that querying the bridge with dig @bridge www.example.com returns normal DNS responses.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate normal-query compatibility against the bridge

### Requirement: REQ-TRN-1786264762917575-004 — Integration test in core/diagnostics-data/ covers bootstrap → primary-transport…

The RIPDPI implementation MUST satisfy this portfolio criterion: Integration test in core/diagnostics-data/ covers bootstrap → primary-transport handoff.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Integration test in core/diagnostics-data/ covers bootstrap → primary-transport handoff

### Requirement: REQ-TRN-1786264762917575-005 — LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a dup…

The RIPDPI implementation MUST satisfy this portfolio criterion: LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a duplicate of ripdpi-dns-resolver or any current bootstrap transport code.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a duplicate of ripdpi-dns-resolver or any current bootstrap transport code
