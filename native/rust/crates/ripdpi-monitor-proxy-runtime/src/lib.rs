#![forbid(unsafe_code)]

use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use ripdpi_diagnostics_transport::transport::TransportConfig;
use ripdpi_diagnostics_transport::transport::wait_for_listener;
use ripdpi_monitor_engine::{
    CandidateCleanupReceipt, CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher,
    CandidateRuntimeTerminalReceipt, PreparedCandidateRuntime,
};
use ripdpi_runtime_api::EmbeddedProxyControl;

pub struct ProductionCandidateRuntimeLauncher;

struct TemporaryProxyRuntime {
    addr: SocketAddr,
    control: Arc<EmbeddedProxyControl>,
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
    prepared: PreparedCandidateRuntime,
    readiness: impl FnOnce(SocketAddr) -> Result<(), String>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    let listener = ripdpi_proxy_runtime::create_listener(&prepared.config)
        .map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
    let addr = listener.local_addr().map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
    let control = Arc::new(EmbeddedProxyControl::new_with_context(None, prepared.runtime_context));
    let worker_control = control.clone();
    let handle = thread::spawn(move || {
        ripdpi_proxy_runtime::run_proxy_with_embedded_control_receipt(prepared.config, listener, worker_control)
            .map_err(|err| err.to_string())
    });
    finish_runtime_readiness(TemporaryProxyRuntime { addr, control, handle: Some(handle) }, readiness)
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
        TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: self.addr.port() }
    }

    fn request_shutdown(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
    }

    fn force_abort_and_join(&mut self) -> CandidateRuntimeTerminalReceipt {
        self.request_shutdown();
        join_runtime_terminal(&mut self.handle, true)
    }

    fn shutdown(mut self: Box<Self>) -> CandidateRuntimeTerminalReceipt {
        self.request_shutdown();
        join_runtime_terminal(&mut self.handle, false)
    }
}

fn join_runtime_terminal(
    handle: &mut Option<JoinHandle<Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String>>>,
    forced: bool,
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
                forced_abort: usize::from(receipt.forced_abort),
            };
            if receipt.forced_abort {
                CandidateRuntimeTerminalReceipt::forced_abort(cleanup)
            } else {
                CandidateRuntimeTerminalReceipt::clean_shutdown(cleanup)
            }
        }
        Ok(Err(_)) => CandidateRuntimeTerminalReceipt::runtime_failed(CandidateCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: 1,
            forced_abort: usize::from(forced),
        }),
        Err(_) => CandidateRuntimeTerminalReceipt::runtime_panicked(CandidateCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: 1,
            forced_abort: usize::from(forced),
        }),
    }
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
    fn joined_runtime_error_is_preserved_as_runtime_failed_terminal_receipt() {
        let mut handle = Some(thread::spawn(|| Err("runtime failed".to_string())));

        let receipt = join_runtime_terminal(&mut handle, false);

        assert_eq!(receipt.cleanup, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 });
        assert_eq!(receipt.terminal_status, CandidateRuntimeTerminalStatus::RuntimeFailed);
        assert!(receipt.execution_evidence.is_empty());
    }

    #[test]
    fn joined_runtime_panic_still_counts_as_joined() {
        let mut handle = Some(thread::spawn(|| -> Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt, String> {
            panic!("synthetic runtime panic")
        }));

        let receipt = join_runtime_terminal(&mut handle, true);

        assert_eq!(receipt.cleanup, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(receipt.terminal_status, CandidateRuntimeTerminalStatus::RuntimePanicked);
    }

    #[test]
    fn readiness_failure_stops_spawned_runtime_before_returning() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, None));
        let handle = thread::spawn(move || {
            let _ = listener.accept();
            Ok(ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt::default())
        });
        let runtime = TemporaryProxyRuntime { addr, control, handle: Some(handle) };

        let result = finish_runtime_readiness(runtime, |_| Err("synthetic readiness failure".to_string()));

        assert!(matches!(result, Err(CandidateRuntimeError::Launch(_))));
        assert!(TcpStream::connect_timeout(&addr, std::time::Duration::from_millis(100)).is_err());
    }
}
