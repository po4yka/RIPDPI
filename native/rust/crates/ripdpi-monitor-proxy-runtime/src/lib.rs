#![forbid(unsafe_code)]

use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use ripdpi_diagnostics_transport::transport::TransportConfig;
use ripdpi_diagnostics_transport::transport::wait_for_listener;
use ripdpi_monitor_engine::{
    CandidateCleanupReceipt, CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher,
    PreparedCandidateRuntime,
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
        let listener = ripdpi_proxy_runtime::create_listener(&prepared.config)
            .map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
        let addr = listener.local_addr().map_err(|err| CandidateRuntimeError::Launch(err.to_string()))?;
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, prepared.runtime_context));
        let worker_control = control.clone();
        let handle = thread::spawn(move || {
            ripdpi_proxy_runtime::run_proxy_with_embedded_control_receipt(prepared.config, listener, worker_control)
                .map_err(|err| err.to_string())
        });
        wait_for_listener(addr).map_err(CandidateRuntimeError::Launch)?;
        Ok(Box::new(TemporaryProxyRuntime { addr, control, handle: Some(handle) }))
    }
}

impl CandidateProbeRuntime for TemporaryProxyRuntime {
    fn transport(&self) -> TransportConfig {
        TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: self.addr.port() }
    }

    fn request_shutdown(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
    }

    fn force_abort_and_join(&mut self, _grace: std::time::Duration) -> CandidateCleanupReceipt {
        self.request_shutdown();
        let joined = self.handle.take().and_then(|handle| handle.join().ok()).and_then(Result::ok);
        CandidateCleanupReceipt { started: 1, stopped: 1, joined: usize::from(joined.is_some()), forced_abort: 1 }
    }

    fn shutdown(mut self: Box<Self>) -> CandidateCleanupReceipt {
        self.request_shutdown();
        let joined = self.handle.take().and_then(|handle| handle.join().ok()).and_then(Result::ok);
        CandidateCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: usize::from(joined.is_some()),
            forced_abort: usize::from(joined.is_some_and(|receipt| receipt.forced_abort)),
        }
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
