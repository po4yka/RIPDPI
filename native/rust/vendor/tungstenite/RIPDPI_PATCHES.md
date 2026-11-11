# Tungstenite source patch

Upstream package: tungstenite 0.30.0, MIT OR Apache-2.0.
Upstream commit: 7f4aeaf0944992c5664c8fb8e0d54577c7e18020.
Published crate SHA-256: `e48ac77174b19c110a50ab2128b24215ac9cb40e0e12e093fb602d175c569d22`.
Source: https://github.com/snapview/tungstenite-rs/tree/7f4aeaf0944992c5664c8fb8e0d54577c7e18020

The package and both licenses are preserved. Trailing whitespace in the upstream changelog is removed. The authentication builder documentation uses an explicit synthetic fixture token. The client verifier rejects negotiated extensions because this implementation has no extension codec. This closes the RFC 6455 section 4.1 acceptance gap. TLS, Accept, subprotocol, and framing checks stay upstream-owned. No version change.

Regression coverage: ripdpi-ws-tunnel/tests/handshake_contract.rs exercises synchronous and asynchronous clients with independent response bytes.
