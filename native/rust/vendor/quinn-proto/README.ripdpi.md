# RIPDPI Quinn TLS error patch

Published quinn-proto 0.11.18 archive, SHA-256
`a9746dbde176634f4f2f1faf2404e30a31b2bc1e9cafb5329c95d8177a18c9fc`.
Upstream revision: `eaec0db4bcb698f76df736d89938743c88a2ad5f`.
The upstream Apache-2.0 and MIT license files are preserved.

The Rustls client config can install a per-session typed TLS error callback.
The callback runs before Quinn converts the error to a QUIC transport error.
MASQUE uses authenticated ECH retry configs from this error to reconnect once.
No wire, trust, package-version or default behavior changes.

Keep this adapter local until the pinned upstream exposes typed rejection data.
The standalone workspace isolates upstream test dependencies.
