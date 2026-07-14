use super::state::LoopState;
use super::udp_assoc::{drain_udp_association_tasks, take_udp_association_tasks};

impl LoopState {
    pub(in crate::io_loop) async fn shutdown(&mut self) {
        let udp_tasks = take_udp_association_tasks(&mut self.udp_associations, &mut self.dns_cache);
        tokio::join!(
            super::bridge::shutdown_active_sessions(&mut self.sessions, &mut self.socket_set, &mut self.dns_cache),
            drain_udp_association_tasks(udp_tasks),
        );
    }
}
