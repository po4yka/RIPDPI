use super::*;
use crate::runtime::desync::FakeTtlAttempt;

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
        attempt: FakeTtlAttempt,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_success(&self.services, group_index, target, host, attempt)
    }
    pub(in crate::runtime) fn note_adaptive_fake_ttl_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        attempt: FakeTtlAttempt,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_failure(&self.services, group_index, target, host, attempt)
    }
    pub(in crate::runtime) fn note_server_ttl(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        observed_ttl: u8,
        attempt: FakeTtlAttempt,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_server_ttl(&self.services, group_index, target, host, observed_ttl, attempt)
    }
    pub(in crate::runtime) fn discard_evolver_attempt(&self, attempt: Option<EvolutionAttempt>) {
        if let Some(attempt) = attempt {
            AdaptiveFeedbackPort::discard_evolver_attempt(&self.services, attempt);
        }
    }
    pub(in crate::runtime) fn note_evolver_answered(&self, attempt: Option<EvolutionAttempt>) {
        if let Some(attempt) = attempt {
            AdaptiveFeedbackPort::note_evolver_answered(&self.services, attempt);
        }
    }
    pub(in crate::runtime) fn note_evolver_success(&self, attempt: Option<EvolutionAttempt>) {
        if let Some(attempt) = attempt {
            AdaptiveFeedbackPort::note_evolver_success(&self.services, attempt);
        }
    }
    pub(in crate::runtime) fn note_evolver_failure(
        &self,
        attempt: Option<EvolutionAttempt>,
        class: RuntimeFailureClass,
    ) {
        if let Some(attempt) = attempt {
            AdaptiveFeedbackPort::note_evolver_failure(&self.services, attempt, class);
        }
    }
}

#[cfg(all(test, not(any(target_os = "linux", target_os = "android"))))]
mod tests;
