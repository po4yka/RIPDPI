//! Relay session multiplexer for TCP streams and UDP datagrams.
//!
//! [`RelaySession`] is a unified abstraction covering both connection types:
//!
//! - [`RelaySession::open_stream`] -- opens a proxied TCP stream to a target address.
//! - [`RelaySession::open_datagram`] -- opens a UDP datagram session through the relay.
//!   Backends that are TCP-only (VLESS Reality, xHTTP, ChainRelay, ShadowTLS) return
//!   `io::ErrorKind::Unsupported` and advertise `RelayCapabilities { udp: false }`.
//!   Callers must check [`RelayCapabilities::udp`] before invoking this method.
//!
//! The `UdpSocket` used in `ripdpi-relay-core` for SOCKS5 UDP ASSOCIATE is the
//! **local inbound half** of that flow (binding on the device's loopback/LAN
//! interface to receive datagrams from the Android client). The outbound half
//! -- towards the relay server -- goes through `RelayMux::open_datagram` ->
//! `RelaySession::open_datagram`. The two sockets are complementary, not
//! redundant. See architecture notes for the full rationale.

#![forbid(unsafe_code)]

mod contracts;
mod health;
mod lease;
mod pool;
mod state;
mod stream;
#[cfg(test)]
mod tests;
mod types;
pub mod wire_mux;

pub use contracts::{RelayCapabilities, RelaySession, RelaySessionFactory};
pub use pool::RelayMux;
pub use stream::{MuxLease, MuxStream};
pub use types::{RelayPoolConfig, RelayPoolHealth};
pub use wire_mux::{
    DeliverOutcome, GoAwayReason, MuxProtocol, PaddingMode, SingMuxCodecError, SingMuxCommand, SingMuxDecoder,
    SingMuxFrame, StreamIdAllocator, StreamMailbox, YamuxCodecError, YamuxDecoder, YamuxFrame, YamuxFrameType,
    YamuxHeader,
};
