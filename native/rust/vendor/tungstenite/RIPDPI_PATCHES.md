# Tungstenite source patch

Upstream package: tungstenite 0.30.0, MIT OR Apache-2.0.
Upstream commit: 7f4aeaf0944992c5664c8fb8e0d54577c7e18020.
Published crate SHA-256: `e48ac77174b19c110a50ab2128b24215ac9cb40e0e12e093fb602d175c569d22`.
Source: https://github.com/snapview/tungstenite-rs/tree/7f4aeaf0944992c5664c8fb8e0d54577c7e18020

The package and both licenses are preserved. Trailing whitespace in the upstream changelog is removed. The authentication builder documentation uses an explicit synthetic fixture token. The client verifier rejects negotiated extensions because this implementation has no extension codec. This closes the RFC 6455 section 4.1 acceptance gap. TLS, Accept, subprotocol, and framing checks stay upstream-owned. No version change.

Regression coverage: ripdpi-ws-tunnel/tests/handshake_contract.rs exercises synchronous and asynchronous clients with independent response bytes.

The client checks for an exact Upgrade token, ignoring ASCII case, across comma-separated Connection options and repeated Connection fields. Accept and subprotocol checks are unchanged. The same regression file covers valid lists and invalid token, Accept, and subprotocol controls.

Request, endpoint, message, frame, and Close diagnostics record metadata in place of header values, URI paths, payload, or Close reasons. The wire data is unchanged. Regression coverage in ripdpi-ws-tunnel/tests/handshake_logging.rs captures the real log bridge and checks synthetic credentials, frames, and local redirects.

The response verifier rejects repeated Sec-WebSocket-Accept and Sec-WebSocket-Protocol fields, including identical values and names with different case. RFC 6455 sections 11.3.3 and 11.3.4 require these fields to occur at most once. Connection fields remain repeatable. The handshake contract tests cover both sync and async clients.
