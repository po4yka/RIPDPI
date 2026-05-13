# Mock Relay

This directory is reserved for malformed-handshake, auth-failure, timeout, and
reset scenarios. The MVP lab starts without a relay container because the client
protocol contract should be tested against the repository-owned reference relay
once that surface is stable.

Expected mock modes:

- valid handshake
- invalid credentials
- malformed handshake
- target unavailable
- connection reset
