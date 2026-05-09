use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn note_adaptive_tcp_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_tcp_success(&self.services, group_index, target, host, payload)
    }
    pub(in crate::runtime) fn note_adaptive_tcp_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_tcp_failure(&self.services, group_index, target, host, payload)
    }
    pub(in crate::runtime) fn note_adaptive_udp_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_udp_success(&self.services, group_index, target, host, payload)
    }
    pub(in crate::runtime) fn note_adaptive_udp_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_udp_failure(&self.services, group_index, target, host, payload)
    }
    pub(in crate::runtime) fn note_adaptive_fake_ttl_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_success(&self.services, group_index, target, host)
    }
    pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_failure(&self.services, group_index, target, host)
    }
    pub(in crate::runtime) fn note_server_ttl(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_server_ttl(&self.services, group_index, target, host, observed_ttl)
    }
    pub(in crate::runtime) fn note_evolver_success(&self) {
        AdaptiveFeedbackPort::note_evolver_success(&self.services);
    }
    pub(in crate::runtime) fn note_evolver_failure(&self, class: RuntimeFailureClass) {
        AdaptiveFeedbackPort::note_evolver_failure(&self.services, class);
    }
}
