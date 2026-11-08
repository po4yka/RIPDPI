# RIPDPI smoltcp patch

This directory contains the published smoltcp 0.14.0 package.

- Archive: https://static.crates.io/crates/smoltcp/smoltcp-0.14.0.crate
- SHA-256: `b6f8b28ad56c6e35524a37dd492af5d1a47e31e1a4d175cd12f89c075f01980f`
- Upstream revision: `cbfe05f835cfc75bf781addc1c0eb2e1a0df1ea3`
- License: 0BSD. See [LICENSE-0BSD.txt](LICENSE-0BSD.txt).

The application uses this source through its root `[patch.crates-io]` entry.
The package version and production dependency requirements are unchanged.
The standalone workspace keeps upstream test dependencies outside the application
workspace. Its lockfile is copied from the published archive.

## Local changes

RFC 9293 section 3.7.1 requires a default send MSS of 536 for IPv4 and 1220 for
IPv6 when the peer does not supply an MSS option. The patch selects the default
from the received SYN address family in both passive and active handshakes.
MSS zero still acts as an absent option. An explicit nonzero MSS still uses the
upstream minimum clamp of 48 bytes. Device MTU limits remain in force.

The upstream TCP tests check emitted payload sizes for both handshakes, absent
and zero MSS, explicit MSS, and the minimum clamp. The application test
`crates/ripdpi-tunnel-core/tests/tcp_mss.rs` sends raw packets through the full
packet loop and checks exact bytes from a loopback SOCKS peer.

The TCP socket also exposes `is_closed_normally()`. A private outcome bit records
normal LAST-ACK completion and TIME-WAIT expiry. Reset, reuse, abort, accepted RST,
and timeout clear the outcome. The TUN bridge can therefore drain accepted bytes
after normal TCP closure without treating an application reset as a normal EOF.
The outcome does not mean that the application has consumed the receive buffer.

TIME-WAIT expiry retains unread accepted bytes and rearms its existing close timer.
This avoids both data loss under backpressure and an expired-timer busy loop. The
bridge retains the live relay in its existing session budget until the task ends;
normal forwarding does not use the shorter cancellation grace. Cancellation and
failure discard unread close state. No extra receive storage or wire rewrite is
introduced. Upstream tests cover the close outcome, reuse, reset, abort, and unread
data across expiry; application tests cover both half-close orders and a blocked
writer beyond the five-second stop grace and ten-second TCP close timer.

## Reproduce and verify

Download the archive above, verify its SHA-256, and extract it into an empty
directory. From the extracted package root, apply:

```sh
patch -p1 < /path/to/integration.patch
```

The result must match this directory, except for this integration record and
`integration.patch`. No Cargo cache files or input wire packets are modified.

From `native/rust`, run with the repository's pinned Rust toolchain:

```sh
cargo test --manifest-path vendor/smoltcp/Cargo.toml --locked --lib --no-default-features --features std,medium-ip,proto-ipv4,socket-tcp socket::tcp::test
cargo test --manifest-path vendor/smoltcp/Cargo.toml --locked --lib --no-default-features --features std,medium-ip,proto-ipv6,socket-tcp socket::tcp::test
cargo test -p ripdpi-tunnel-core --locked --test tcp_mss
```

On an upstream update, verify the new archive checksum and compare the source.
Reapply only the recorded local changes, preserve the license, and run both
address-family test sets and the packet-loop regression tests. Remove the local
patch when an upstream release provides the same behavior.
