# Design

Build the two requests from one HTTPS URL and one numeric unicast address. Use the URL authority for certificate verification and the pinned address for both connections. Use the URL port for both requests, with 443 as the default. Request an identity representation.

Allow one attempt per protocol. Cap the pair at 15 seconds and respect an earlier scan deadline. Reserve half the initial time for HTTP/3. Verify the length and SHA-256 while streaming at most the expected byte count plus one. Save the stage, terminal result, and bounded byte count per leg. Show the earliest differing stage when both attempts produced evidence. Keep partial byte counts if the network scope is lost, and mark the pair inconclusive.

The comparison uses no background probes, extra endpoints, or in-path fallback. The result contains no body bytes, digest, or endpoint string.
