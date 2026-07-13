use super::state::LoopState;
use super::udp_assoc::shutdown_udp_associations;

impl LoopState {
    pub(in crate::io_loop) async fn shutdown(&mut self) {
        tokio::join!(
            super::bridge::shutdown_active_sessions(&mut self.sessions, &mut self.socket_set, &mut self.dns_cache),
            shutdown_udp_associations(&mut self.udp_associations),
        );
    }
}
