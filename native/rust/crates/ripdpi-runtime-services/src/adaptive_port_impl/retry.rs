use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::thread;
use std::time::Duration;

use ripdpi_runtime_adaptive::adaptive_port::RetryPacingPort;
use ripdpi_runtime_policy::runtime_policy::{RetrySelectionPenalty, TransportProtocol};

use crate::ServicesStateHandle;

impl RetryPacingPort for ServicesStateHandle {
    fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        let Some(signature) =
            self.build_retry_signature(&self.0.config, target, group_index, host, payload, transport)?
        else {
            return Ok(());
        };
        let mut pacer = self.0.retry_pacer.write().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        pacer.clear_success(&signature);
        Ok(())
    }

    fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()> {
        let Some(signature) =
            self.build_retry_signature(&self.0.config, target, group_index, host, payload, transport)?
        else {
            return Ok(());
        };
        let mut pacer = self.0.retry_pacer.write().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        pacer.record_failure(&signature, now_ms);
        Ok(())
    }

    fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
        let config = &self.0.config;
        let mut signatures = Vec::with_capacity(config.groups.len());
        for group_index in 0..config.groups.len() {
            if let Some(sig) = self.build_retry_signature(config, target, group_index, host, payload, transport)? {
                signatures.push((group_index, sig));
            }
        }
        let pacer = self.0.retry_pacer.read().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        let mut penalties = BTreeMap::new();
        for (group_index, signature) in signatures {
            penalties.insert(group_index, pacer.penalty_for(&signature, now_ms));
        }
        Ok(penalties)
    }

    fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
        on_paced: &dyn Fn(SocketAddr, usize, &'static str, u64),
    ) -> io::Result<()> {
        let Some(signature) =
            self.build_retry_signature(&self.0.config, target, group_index, host, payload, TransportProtocol::Tcp)?
        else {
            return Ok(());
        };
        let decision = {
            let pacer = self.0.retry_pacer.read().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
            pacer.retry_delay_for(&signature, now_ms)
        };
        let Some(decision) = decision.filter(|d| d.backoff_ms > 0) else {
            return Ok(());
        };
        on_paced(target, group_index, decision.reason, decision.backoff_ms);
        thread::sleep(Duration::from_millis(decision.backoff_ms));
        Ok(())
    }
}
