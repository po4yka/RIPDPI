# Design

Capture the applied member and the authenticated in-path route lease with the selector's existing physical network token. The active member is the member in the applied VPN configuration. Treat a missing or revoked lease or a pending member change as an inconclusive pass: it records no observation and keeps the earlier failure streak, reserve progress and cooldown. Log the first inconclusive pass of a run. With noDomain on, the in-path listener cannot resolve control host names and local resolution would leave the tunnel, so the selector keeps the isolated candidate probe for the active member.

A change of the applied VPN path does not reset failover state. The selector's own switch reloads the tunnel, and a reset would drop the cooldown that prevents flapping.

Use the existing bounded HTTP body and digest verifier through the authenticated loopback route. Retain candidate relays for reserve probes. Check the lease and the physical network again at the automatic selection write. Existing failure thresholds, reserve eligibility and reload ownership apply.

The Android fixture stalls after sending headers and one byte on the leased VPN endpoint. An isolated probe of the same profile must complete. Verify that the distinct test UID receives the expected bytes and digest through the reserve after it is applied.
