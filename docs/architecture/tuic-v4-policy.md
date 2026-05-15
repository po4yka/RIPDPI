# TUIC v4 Policy — ADR

> Status: **draft; awaiting code implementation**.
> Authored: 2026-05-15.
> Tracking task: `docs/tasks/issues/add-tuic-v4-fallback-or-version-detection.md`.

## Question

`ripdpi-tuic` ships TUIC v5 only (`TUIC_VERSION = 0x05`). Should it
also support v4, negotiate, or hard-require v5?

## Decision

**v5 only**, with explicit failure classification.

- The wire constant remains `TUIC_VERSION = 0x05`.
- A v4-server response is classified as
  `FailureClass::TuicVersionUnsupported` in the failure classifier,
  not as a generic protocol error.
- User-facing remediation text: "Server speaks TUIC v4; upgrade the
  server or remove this profile."

## Rationale

- **Population.** v5 has been the recommended deployment since 2023.
  Active v4 servers are rare; the maintenance cost of dual-version
  wire code is high.
- **Wire divergence.** v4 and v5 differ on auth, packet framing, and
  command bytes. "Fallback" effectively means shipping two clients.
- **Diagnostic clarity.** A targeted `TuicVersionUnsupported` class
  is enough to direct the operator without carrying the v4 wire.

## Trade-offs accepted

- **No v4 connectivity.** Users with v4-only servers cannot connect.
  This is documented in the editor UI and surfaced in the failure
  classifier.

## Implementation outline

1. In `ripdpi-failure-classifier`, add a `TuicVersionUnsupported`
   variant.
2. In `ripdpi-tuic::client`, identify the v4-server signature on
   handshake failure (the v4 server's reply differs from v5's
   `AUTHENTICATE` response shape) and surface
   `TuicVersionUnsupported`.
3. Add a unit test that feeds a v4-style response into the v5
   handshake path and asserts the classifier output.

## Owner

Native-transport owner picks up the implementation work.
