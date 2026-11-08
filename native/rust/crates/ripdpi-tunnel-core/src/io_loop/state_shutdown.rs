use super::state::LoopState;
use super::udp_assoc::{drain_udp_association_tasks, take_udp_association_tasks};

impl LoopState {
    /// # Cancel safety:
    /// cancel-safe: packet pins are released synchronously and both task drains abort unfinished tasks on drop.
    pub(in crate::io_loop) async fn shutdown(&mut self) {
        self.pending_uid_packets.clear(&mut self.dns_cache);
        let udp_tasks = take_udp_association_tasks(&mut self.udp_associations, &mut self.dns_cache);
        tokio::join!(
            super::bridge::shutdown_active_sessions(&mut self.sessions, &mut self.socket_set, &mut self.dns_cache),
            drain_udp_association_tasks(udp_tasks),
        );
    }
}
