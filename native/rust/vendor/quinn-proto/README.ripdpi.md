# RIPDPI Quinn TLS error patch

Published quinn-proto 0.11.19 archive, SHA-256
`0e750cca55fe4f0439a15d0bb529da9651e79993e8e72c61a899a36d462befbe`.
Upstream revision: `8192ed399a26a6e8f0aba43d10ec8badaa5b905e`.
The upstream Apache-2.0 and MIT license files are preserved.

The Rustls client config can install a per-session typed TLS error callback.
The callback runs before Quinn converts the error to a QUIC transport error.
MASQUE uses authenticated ECH retry configs from this error to reconnect once.
No wire, trust, package-version or default behavior changes.

Keep this adapter local until the pinned upstream exposes typed rejection data.
The standalone workspace isolates upstream test dependencies.
