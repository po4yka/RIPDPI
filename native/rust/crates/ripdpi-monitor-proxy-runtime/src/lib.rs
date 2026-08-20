#![forbid(unsafe_code)]

use std::io::Read;
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use ripdpi_diagnostics_transport::transport::wait_for_listener;
use ripdpi_diagnostics_transport::transport::{Socks5Credentials, TransportConfig};
use ripdpi_monitor_engine::{
    CandidateAttemptCorrelationId, CandidateCleanupReceipt, CandidateProbeRuntime, CandidateRuntimeError,
    CandidateRuntimeExecutionEvidence, CandidateRuntimeLauncher, CandidateRuntimeTerminalReceipt,
    PreparedCandidateRuntime,
};
use ripdpi_runtime_api::EmbeddedProxyControl;

pub struct ProductionCandidateRuntimeLauncher;

struct TemporaryProxyRuntime {
    addr: SocketAddr,
    control: Arc<EmbeddedProxyControl>,
    generation: u64,
    auth_secret: String,
    handle: Option<JoinHandle<Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String>>>,
}

impl CandidateRuntimeLauncher for ProductionCandidateRuntimeLauncher {
    fn start_candidate_runtime(
        &self,
        prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
        start_candidate_runtime_with_readiness(prepared, wait_for_listener)
    }
}

fn start_candidate_runtime_with_readiness(
    mut prepared: PreparedCandidateRuntime,
    readiness: impl FnOnce(SocketAddr) -> Result<(), String>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    let generation = prepared.generation;
    let auth_secret = generate_runtime_auth_secret()?;
    prepared.config.network.listen.auth_token = Some(auth_secret.clone());
    let listener = ripdpi_proxy_runtime::create_listener(&prepared.config)
        .map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
    let addr = listener.local_addr().map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
    let control =
        Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, prepared.runtime_context, generation));
    let worker_control = control.clone();
    let handle = thread::spawn(move || {
        ripdpi_proxy_runtime::run_proxy_with_embedded_control_receipt(prepared.config, listener, worker_control)
            .map_err(|err| err.to_string())
    });
    finish_runtime_readiness(
        TemporaryProxyRuntime { addr, control, generation, auth_secret, handle: Some(handle) },
        readiness,
    )
}

fn generate_runtime_auth_secret() -> Result<String, CandidateRuntimeError> {
    let mut bytes = [0u8; 24];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut source| source.read_exact(&mut bytes))
        .map_err(|error| CandidateRuntimeError::Launch(format!("candidate auth entropy unavailable: {error}")))?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

fn finish_runtime_readiness(
    mut runtime: TemporaryProxyRuntime,
    readiness: impl FnOnce(SocketAddr) -> Result<(), String>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    if let Err(error) = readiness(runtime.addr) {
        let _ = runtime.force_abort_and_join();
        return Err(CandidateRuntimeError::Launch(error));
    }
    Ok(Box::new(runtime))
}

impl CandidateProbeRuntime for TemporaryProxyRuntime {
    fn transport(&self) -> TransportConfig {
        let token = CandidateAttemptCorrelationId::warmup(self.generation);
        // Infallible: candidate preparation allocates a nonzero generation before launcher construction.
        let token = token.expect("candidate generation is nonzero");
        self.transport_for_attempt(&token)
    }

    fn generation(&self) -> u64 {
        self.generation
    }

    fn transport_for_attempt(&self, attempt_token: &CandidateAttemptCorrelationId) -> TransportConfig {
        let credentials = Socks5Credentials::new(attempt_token.as_opaque_str(), self.auth_secret.clone());
        // Infallible: generated IDs and the 48-byte runtime secret satisfy RFC 1929's 255-byte bounds.
        let credentials = credentials.expect("generated candidate SOCKS credentials are bounded");
        TransportConfig::Socks5 {
            host: "127.0.0.1".to_string(),
            port: self.addr.port(),
            credentials: Some(credentials),
        }
    }

    fn request_shutdown(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
    }

    fn force_abort_and_join(&mut self) -> CandidateRuntimeTerminalReceipt {
        self.request_shutdown();
        join_runtime_terminal(&mut self.handle, true, &self.control)
    }

    fn shutdown(mut self: Box<Self>) -> CandidateRuntimeTerminalReceipt {
        self.request_shutdown();
        join_runtime_terminal(&mut self.handle, false, &self.control)
    }
}

fn join_runtime_terminal(
    handle: &mut Option<JoinHandle<Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String>>>,
    forced: bool,
    control: &EmbeddedProxyControl,
) -> CandidateRuntimeTerminalReceipt {
    let Some(handle) = handle.take() else {
        return CandidateRuntimeTerminalReceipt::already_joined();
    };
    match handle.join() {
        Ok(Ok(receipt)) => {
            let cleanup = CandidateCleanupReceipt {
                started: 1,
                stopped: 1,
                joined: 1,
                forced_abort: usize::from(receipt.forced_abort()),
            };
            if receipt.worker_panicked() {
                let terminal = CandidateRuntimeTerminalReceipt::runtime_panicked(
                    control.desync_execution_generation(),
                    cleanup,
                    terminal_shutdown_mode(forced),
                    candidate_execution_evidence(&receipt),
                );
                // Infallible: a joined panicking worker has one started/joined runtime and coherent abort counts.
                let terminal = terminal.expect("worker panic has coherent cleanup");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed())
            } else if receipt.forced_abort() {
                let terminal = CandidateRuntimeTerminalReceipt::forced_abort(
                    control.desync_execution_generation(),
                    cleanup,
                    candidate_execution_evidence(&receipt),
                );
                // Infallible: the proxy receipt reports one started/stopped/joined forced-abort runtime.
                let terminal = terminal.expect("proxy cleanup receipt is a completed forced abort");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed())
            } else {
                let terminal = CandidateRuntimeTerminalReceipt::clean_shutdown(
                    control.desync_execution_generation(),
                    cleanup,
                    candidate_execution_evidence(&receipt),
                );
                // Infallible: the proxy receipt reports one started/stopped/joined graceful runtime.
                let terminal = terminal.expect("proxy cleanup receipt is a completed clean shutdown");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed())
            }
        }
        Ok(Err(_)) => {
            let terminal = CandidateRuntimeTerminalReceipt::runtime_failed(
                control.desync_execution_generation(),
                CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: usize::from(forced) },
                terminal_shutdown_mode(forced),
                candidate_execution_evidence_from_runtime(control.desync_execution_evidence()),
            );
            // Infallible: the joined error path creates one started/joined runtime with coherent abort counts.
            let terminal = terminal.expect("joined runtime failure has coherent cleanup");
            terminal.with_execution_evidence_overflowed(control.desync_execution_evidence_overflowed())
        }
        Err(_) => {
            let terminal = CandidateRuntimeTerminalReceipt::runtime_panicked(
                control.desync_execution_generation(),
                CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: usize::from(forced) },
                terminal_shutdown_mode(forced),
                candidate_execution_evidence_from_runtime(control.desync_execution_evidence()),
            );
            // Infallible: the joined panic path creates one started/joined runtime with coherent abort counts.
            let terminal = terminal.expect("joined runtime panic has coherent cleanup");
            terminal.with_execution_evidence_overflowed(control.desync_execution_evidence_overflowed())
        }
    }
}

fn terminal_shutdown_mode(forced: bool) -> ripdpi_monitor_engine::CandidateRuntimeShutdownMode {
    if forced {
        ripdpi_monitor_engine::CandidateRuntimeShutdownMode::ForcedAbort
    } else {
        ripdpi_monitor_engine::CandidateRuntimeShutdownMode::CleanShutdown
    }
}

fn candidate_execution_evidence(
    receipt: &ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt,
) -> Vec<CandidateRuntimeExecutionEvidence> {
    candidate_execution_evidence_from_runtime(receipt.desync_execution_evidence().to_vec())
}

fn candidate_execution_evidence_from_runtime(
    evidence: Vec<ripdpi_runtime_api::DesyncExecutionEvidence>,
) -> Vec<CandidateRuntimeExecutionEvidence> {
    evidence.iter().filter_map(CandidateRuntimeExecutionEvidence::from_runtime_desync).collect()
}

impl Drop for TemporaryProxyRuntime {
    fn drop(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_monitor_engine::CandidateRuntimeTerminalStatus;

    #[test]
    fn production_candidate_runtime_uses_generation_bound_redacted_socks_auth() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 42));
        let handle = thread::spawn(move || {
            let _ = listener.accept();
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, false, Vec::new(), false))
        });
        let runtime = TemporaryProxyRuntime {
            addr,
            control,
            generation: 42,
            auth_secret: generate_runtime_auth_secret().expect("runtime secret"),
            handle: Some(handle),
        };
        let attempt = CandidateAttemptCorrelationId::evaluated(42, 7).expect("attempt correlation id");
        let transport = runtime.transport_for_attempt(&attempt);

        assert_eq!(runtime.generation(), 42);
        let TransportConfig::Socks5 { credentials: Some(credentials), .. } = &transport else {
            panic!("expected authenticated SOCKS transport");
        };
        assert_eq!(credentials.username(), attempt.as_opaque_str());
        let debug = format!("{transport:?}");
        assert!(!debug.contains(attempt.as_opaque_str()));
        assert!(!debug.contains(credentials.password()));
        assert!(debug.contains("<redacted>"));

        let receipt = Box::new(runtime).shutdown();
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::CleanShutdown);
    }

    #[test]
    fn joined_runtime_error_is_preserved_as_runtime_failed_terminal_receipt() {
        let mut handle = Some(thread::spawn(|| Err("runtime failed".to_string())));

        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);
        let receipt = join_runtime_terminal(&mut handle, false, &control);

        assert_eq!(receipt.cleanup(), CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 });
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimeFailed);
        assert!(receipt.execution_evidence().is_empty());
    }

    #[test]
    fn joined_runtime_panic_still_counts_as_joined() {
        let mut handle = Some(thread::spawn(|| -> Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String> {
            panic!("synthetic runtime panic")
        }));

        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);
        let receipt = join_runtime_terminal(&mut handle, true, &control);

        assert_eq!(receipt.cleanup(), CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimePanicked);
    }

    #[test]
    fn client_worker_panic_is_preserved_as_runtime_panicked_terminal_receipt() {
        let mut handle = Some(thread::spawn(|| {
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, true, Vec::new(), false))
        }));

        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);
        let receipt = join_runtime_terminal(&mut handle, false, &control);

        assert_eq!(receipt.cleanup(), CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 });
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimePanicked);
    }

    #[test]
    fn readiness_failure_stops_spawned_runtime_before_returning() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1));
        let handle = thread::spawn(move || {
            let _ = listener.accept();
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, false, Vec::new(), false))
        });
        let runtime = TemporaryProxyRuntime {
            addr,
            control,
            generation: 1,
            auth_secret: "test-runtime-secret".to_string(),
            handle: Some(handle),
        };

        let result = finish_runtime_readiness(runtime, |_| Err("synthetic readiness failure".to_string()));

        assert!(matches!(result, Err(CandidateRuntimeError::Launch(_))));
        assert!(TcpStream::connect_timeout(&addr, std::time::Duration::from_millis(100)).is_err());
    }
}
