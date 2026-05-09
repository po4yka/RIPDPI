use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn network_reprobe_enabled(&self) -> bool {
        self.network_reprobe_settings.enabled
    }
    pub(in crate::runtime) fn network_reprobe_protect_path(&self) -> Option<String> {
        self.network_reprobe_settings.protect_path.clone()
    }
    pub(in crate::runtime) fn encrypted_dns_ip_answers_for_host(
        &self,
        host: &str,
    ) -> io::Result<RuntimeEncryptedDnsIpAnswers> {
        runtime_encrypted_dns_ip_answers_for_host(
            host,
            self.runtime_context.as_ref(),
            self.response_failure_evidence_settings.protect_path.as_deref(),
        )
    }
    pub(in crate::runtime) fn shutdown_requested(&self) -> bool {
        self.control.as_ref().map_or_else(crate::process::shutdown_requested, |control| control.shutdown_requested())
    }
    pub(in crate::runtime) fn has_embedded_control(&self) -> bool {
        self.control.is_some()
    }
    pub(in crate::runtime) fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        self.control.as_ref().and_then(|control| control.current_network_snapshot())
    }
    pub(in crate::runtime) fn should_reprobe_network(&self, snapshot: &NetworkSnapshot) -> bool {
        self.reprobe_tracker.check_snapshot(snapshot)
    }
    pub(in crate::runtime) fn block_signal_confirmation_allowed(&self) -> bool {
        self.current_network_snapshot().is_none_or(|snapshot| snapshot.validated && !snapshot.captive_portal)
    }
    pub(in crate::runtime) fn resolve_encrypted_dns_host(
        &self,
        host: &str,
        protect_path: Option<&str>,
        ipv6_enabled: bool,
    ) -> io::Result<SocketAddr> {
        runtime_resolve_host_via_encrypted_dns(host, self.runtime_context.as_ref(), protect_path, ipv6_enabled)
    }
    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(in crate::runtime) fn io_uring_driver(&self) -> Option<&std::sync::Arc<ripdpi_io_uring::IoUringDriver>> {
        self.io_uring.as_ref()
    }
}
