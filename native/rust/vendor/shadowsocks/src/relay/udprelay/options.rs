//! UDP Socket options and extra data

use std::sync::Arc;

use crate::config::ServerUser;

#[derive(Debug, Clone, Default)]
#[non_exhaustive]
pub struct UdpSocketControlData {
    /// Authenticated peer timestamp from a successfully decoded AEAD-2022 packet.
    pub authenticated_timestamp: Option<u64>,
    /// Session ID in client.
    ///
    /// For identifying an unique association in client
    pub client_session_id: u64,
    /// Session ID in server.
    ///
    /// For identifying an unique association in server
    pub server_session_id: u64,
    /// Packet counter
    pub packet_id: u64,
    /// Server user instance
    pub user: Option<Arc<ServerUser>>,
}
