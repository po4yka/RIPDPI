#![forbid(unsafe_code)]

use std::io::Read;
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use ripdpi_diagnostics_transport::transport::wait_for_listener;
use ripdpi_diagnostics_transport::transport::{Socks5Credentials, TransportConfig};
use ripdpi_monitor_engine::{
    CandidateAttemptCorrelationId, CandidateCleanupReceipt, CandidateProbeRuntime, CandidateRuntimeError,
    CandidateRuntimeLauncher, CandidateRuntimeTerminalReceipt, PreparedCandidateRuntime,
};
use ripdpi_runtime_api::EmbeddedProxyControl;

mod evidence_projection;

use evidence_projection::{CandidateExecutionEvidenceProjection, project_runtime_execution_evidence_batch};

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
    if forced && !handle.is_finished() {
        // A deadline/cancel path may only wait for the runtime's bounded proxy
        // drain. Dropping the unfinished handle detaches it; `joined=0`
        // records that fact instead of claiming a completed OS join.
        let CandidateExecutionEvidenceProjection { evidence, rejected } =
            candidate_execution_evidence_from_runtime(&control.desync_execution_evidence());
        let terminal = CandidateRuntimeTerminalReceipt::forced_abort(
            control.desync_execution_generation(),
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 0, forced_abort: 1, ..Default::default() },
            evidence,
        )
        .expect("bounded forced detach has coherent cleanup");
        return terminal.with_execution_evidence_overflowed(control.desync_execution_evidence_overflowed() || rejected);
    }
    match handle.join() {
        Ok(Ok(receipt)) => {
            let projection = candidate_execution_evidence(&receipt);
            let CandidateExecutionEvidenceProjection { evidence, rejected } = projection;
            let cleanup = CandidateCleanupReceipt {
                started: 1,
                stopped: 1,
                joined: 1,
                forced_abort: usize::from(receipt.forced_abort()),
                address_attempt_count: receipt.connection_refused_count(),
                connection_refused_count: receipt.connection_refused_count(),
                duplicate_refusal_count: receipt.duplicate_refusal_count(),
                ..Default::default()
            };
            if receipt.worker_panicked() {
                let terminal = CandidateRuntimeTerminalReceipt::runtime_panicked(
                    control.desync_execution_generation(),
                    cleanup,
                    terminal_shutdown_mode(forced),
                    evidence,
                );
                // Infallible: a joined panicking worker has one started/joined runtime and coherent abort counts.
                let terminal = terminal.expect("worker panic has coherent cleanup");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed() || rejected)
            } else if receipt.forced_abort() {
                let terminal = CandidateRuntimeTerminalReceipt::forced_abort(
                    control.desync_execution_generation(),
                    cleanup,
                    evidence,
                );
                // Infallible: the proxy receipt reports one started/stopped/joined forced-abort runtime.
                let terminal = terminal.expect("proxy cleanup receipt is a completed forced abort");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed() || rejected)
            } else {
                let terminal = CandidateRuntimeTerminalReceipt::clean_shutdown(
                    control.desync_execution_generation(),
                    cleanup,
                    evidence,
                );
                // Infallible: the proxy receipt reports one started/stopped/joined graceful runtime.
                let terminal = terminal.expect("proxy cleanup receipt is a completed clean shutdown");
                terminal.with_execution_evidence_overflowed(receipt.desync_execution_evidence_overflowed() || rejected)
            }
        }
        Ok(Err(_)) => {
            let CandidateExecutionEvidenceProjection { evidence, rejected } =
                candidate_execution_evidence_from_runtime(&control.desync_execution_evidence());
            let terminal = CandidateRuntimeTerminalReceipt::runtime_failed(
                control.desync_execution_generation(),
                CandidateCleanupReceipt {
                    started: 1,
                    stopped: 1,
                    joined: 1,
                    forced_abort: usize::from(forced),
                    ..Default::default()
                },
                terminal_shutdown_mode(forced),
                evidence,
            );
            // Infallible: the joined error path creates one started/joined runtime with coherent abort counts.
            let terminal = terminal.expect("joined runtime failure has coherent cleanup");
            terminal.with_execution_evidence_overflowed(control.desync_execution_evidence_overflowed() || rejected)
        }
        Err(_) => {
            let CandidateExecutionEvidenceProjection { evidence, rejected } =
                candidate_execution_evidence_from_runtime(&control.desync_execution_evidence());
            let terminal = CandidateRuntimeTerminalReceipt::runtime_panicked(
                control.desync_execution_generation(),
                CandidateCleanupReceipt {
                    started: 1,
                    stopped: 1,
                    joined: 1,
                    forced_abort: usize::from(forced),
                    ..Default::default()
                },
                terminal_shutdown_mode(forced),
                evidence,
            );
            // Infallible: the joined panic path creates one started/joined runtime with coherent abort counts.
            let terminal = terminal.expect("joined runtime panic has coherent cleanup");
            terminal.with_execution_evidence_overflowed(control.desync_execution_evidence_overflowed() || rejected)
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
) -> CandidateExecutionEvidenceProjection {
    candidate_execution_evidence_from_runtime(receipt.desync_execution_evidence())
}

fn candidate_execution_evidence_from_runtime(
    evidence: &[ripdpi_runtime_api::DesyncExecutionEvidence],
) -> CandidateExecutionEvidenceProjection {
    project_runtime_execution_evidence_batch(evidence)
}

impl Drop for TemporaryProxyRuntime {
    fn drop(&mut self) {
        let _ = self.force_abort_and_join();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_monitor_engine::CandidateRuntimeTerminalStatus;
    use ripdpi_runtime_api::{
        AttemptCorrelationId, DesyncExecutionDisposition, DesyncExecutionEvidence, DesyncExecutionReceipt,
        DesyncExecutionTransport, DesyncOffsetMarkerBase, DesyncStrategyFamily,
    };

    #[test]
    fn production_candidate_runtime_uses_generation_bound_redacted_socks_auth() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 42));
        let handle = thread::spawn(move || {
            let _ = listener.accept();
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, false, Vec::new(), false, 0, 0, None))
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

        assert_eq!(
            receipt.cleanup(),
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() }
        );
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimeFailed);
        assert!(receipt.execution_evidence().is_empty());
    }

    #[test]
    fn rejected_runtime_receipt_marks_the_clean_terminal_evidence_incomplete() {
        let token = AttemptCorrelationId::new("p-0000000000000001-0000000000000001").expect("attempt token");
        let valid = DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            Some(DesyncOffsetMarkerBase::Host),
            Some(1),
            Some(10),
            1,
            3,
            3,
            2,
            1,
            100,
            false,
            None,
            None,
            None,
            None,
        )
        .expect("valid split receipt");
        let unknown = DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Unknown),
            Some(DesyncStrategyFamily::Unknown),
            None,
            None,
            None,
            1,
            1,
            1,
            1,
            0,
            1,
            false,
            None,
            None,
            None,
            None,
        )
        .expect("forward-compatible runtime receipt");
        let evidence = vec![
            DesyncExecutionEvidence::new(1, token.clone(), 1, valid).expect("valid evidence"),
            DesyncExecutionEvidence::new(1, token, 2, unknown).expect("unknown evidence"),
        ];
        let mut handle = Some(thread::spawn(move || {
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, false, evidence, false, 0, 0, None))
        }));
        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);

        let terminal = join_runtime_terminal(&mut handle, false, &control);

        assert_eq!(terminal.terminal_status(), CandidateRuntimeTerminalStatus::CleanShutdown);
        assert_eq!(terminal.execution_evidence().len(), 1);
        assert!(terminal.execution_evidence_overflowed());
    }

    #[test]
    fn joined_runtime_panic_still_counts_as_joined() {
        let mut handle = Some(thread::spawn(|| -> Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String> {
            panic!("synthetic runtime panic")
        }));
        while !handle.as_ref().expect("runtime handle").is_finished() {
            thread::yield_now();
        }

        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);
        let receipt = join_runtime_terminal(&mut handle, true, &control);

        assert_eq!(
            receipt.cleanup(),
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1, ..Default::default() }
        );
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimePanicked);
    }

    #[test]
    fn client_worker_panic_is_preserved_as_runtime_panicked_terminal_receipt() {
        let mut handle = Some(thread::spawn(|| {
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, true, Vec::new(), false, 0, 0, None))
        }));

        let control = EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1);
        let receipt = join_runtime_terminal(&mut handle, false, &control);

        assert_eq!(
            receipt.cleanup(),
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() }
        );
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::RuntimePanicked);
    }

    #[test]
    fn readiness_failure_stops_spawned_runtime_before_returning() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 1));
        let handle = thread::spawn(move || {
            let _ = listener.accept();
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::clean(false, false, Vec::new(), false, 0, 0, None))
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
