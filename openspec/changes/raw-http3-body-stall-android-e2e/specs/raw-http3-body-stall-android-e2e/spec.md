## ADDED Requirements

### Requirement: REQ-DGN-1795367419931764-001 — Preserve the HTTP/3 body divergence in Android history

The Android E2E test SHALL use the manual RAW profile and one pinned HTTPS origin over TCP and HTTP/3. It SHALL assert the saved result for each protocol and the first differing stage.

#### Scenario: HTTP/3 stops after one body byte

- **WHEN** TCP completes the verified two-byte object and HTTP/3 stops after one body byte
- **THEN** history records two bytes with a verified TCP outcome and one byte with an HTTP/3 body timeout, with the difference at the body stage
