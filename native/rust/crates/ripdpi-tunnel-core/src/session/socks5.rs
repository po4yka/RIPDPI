mod address;
mod auth;
mod connect;
mod udp_associate;
mod udp_frame;

pub use address::TargetAddr;
pub use auth::{Auth, handshake};
pub use connect::connect;
pub use udp_associate::associate;
pub(crate) use udp_frame::decode_udp_target_frame;
pub use udp_frame::{decode_udp_frame, encode_udp_frame, encode_udp_target_frame};

#[cfg(test)]
mod tests;
