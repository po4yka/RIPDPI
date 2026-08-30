use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

#[derive(Clone)]
pub(crate) struct VlessRealitySessionFactory {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) session_limiter: ripdpi_vless::VlessRealityCarrierLimiter,
    pub(crate) udp_enabled: bool,
}

pub(crate) struct VlessRealitySession {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) mux: Option<ripdpi_vless::VlessYamuxSession>,
    pub(crate) mux_carrier_used: AtomicBool,
    pub(crate) session_limiter: ripdpi_vless::VlessRealityCarrierLimiter,
    pub(crate) udp_enabled: bool,
}

impl RelaySession for VlessRealitySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ripdpi_vless::VlessXudpSession;
    type Error = io::Error;

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: the direct branch drops its exclusively
    /// owned carrier. The mux branch depends on `VlessYamuxSession::open_stream`
    /// dropping an incomplete logical stream by resetting only that substream
    /// while leaving the shared carrier codec synchronized;
    /// `CarrierReuseGuard` guarantees one terminal telemetry event.
    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        if let Some(mux) = &self.mux {
            // Mark the carrier as used only AFTER a stream opens. Flagging
            // before the await made a failed or cancelled first open look
            // like a completed reuse to every later attempt, emitting
            // terminal reuse telemetry for a carrier that never carried
            // anything.
            let reused = self.mux_carrier_used.load(Ordering::Acquire);
            let mut guard = reused.then(CarrierReuseGuard::new);
            return match mux.open_stream(target).await {
                Ok(stream) => {
                    self.mux_carrier_used.store(true, Ordering::Release);
                    if let Some(guard) = &mut guard {
                        guard.finish("succeeded", None);
                    }
                    Ok(Box::new(stream))
                }
                Err(error) => {
                    if let Some(guard) = &mut guard {
                        guard.finish("failed", Some(&error));
                    }
                    Err(error)
                }
            };
        }
        let endpoint =
            ripdpi_vless::VlessRealityClient::resolve_server_endpoint(&self.config, self.outbound_bind_ip).await?;
        let stream =
            self.session_limiter.connect_to_addr(&self.config, self.outbound_bind_ip, target, endpoint).await?;
        Ok(Box::new(stream))
    }

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: cancellation before establishment drops the
    /// incomplete carrier and its admission guard. Established XUDP tasks own
    /// the guarded stream until physical carrier teardown.
    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        if !self.udp_enabled || self.config.flow == ripdpi_vless::addons::VlessFlow::None {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS Reality XUDP is disabled for this profile"));
        }
        let endpoint =
            ripdpi_vless::VlessRealityClient::resolve_server_endpoint(&self.config, self.outbound_bind_ip).await?;
        self.session_limiter.connect_xudp_to_addr(&self.config, self.outbound_bind_ip, endpoint).await
    }
}

struct CarrierReuseGuard {
    started: Instant,
    finished: bool,
}

impl CarrierReuseGuard {
    fn new() -> Self {
        Self { started: Instant::now(), finished: false }
    }

    fn finish(&mut self, outcome: &'static str, error: Option<&io::Error>) {
        emit_carrier_reuse(outcome, self.started, error);
        self.finished = true;
    }
}

impl Drop for CarrierReuseGuard {
    fn drop(&mut self) {
        if !self.finished {
            emit_carrier_reuse("cancelled", self.started, None);
        }
    }
}

fn emit_carrier_reuse(outcome: &'static str, started: Instant, error: Option<&io::Error>) {
    let duration_ms = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
    let io_error_kind = error.map(|value| format!("{:?}", value.kind()).to_ascii_lowercase());
    let os_error_code = error.and_then(io::Error::raw_os_error);
    tracing::info!(
        kind = "relay_attempt_stage",
        stage = "vless_request",
        outcome,
        duration_ms,
        failure_stage = error.map(|_| "vless_request"),
        failure_class = error.map(|_| "io_error"),
        io_error_kind,
        os_error_code,
        carrier_disposition = "carrier_reused",
        "relay attempt reused an existing VLESS carrier"
    );
}

impl RelaySessionFactory for VlessRealitySessionFactory {
    /// # Cancel safety
    ///
    /// cancel-safe: completes without an await or observable state mutation.
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = VlessRealitySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities {
            tcp: true,
            udp: self.udp_enabled && self.config.flow != ripdpi_vless::addons::VlessFlow::None,
            reusable: self.config.mux.is_some(),
        }
    }

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: cancellation before establishment drops the
    /// incomplete carrier and guard. On success, the yamux driver owns the
    /// guarded physical stream until its task actually destroys the carrier.
    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        let mux = if config.mux.is_some() {
            let endpoint = ripdpi_vless::VlessRealityClient::resolve_server_endpoint(&config, outbound_bind_ip).await?;
            Some(self.session_limiter.connect_mux_to(&config, outbound_bind_ip, endpoint).await?)
        } else {
            None
        };
        Ok(Arc::new(VlessRealitySession {
            config,
            outbound_bind_ip,
            mux,
            mux_carrier_used: AtomicBool::new(false),
            session_limiter: self.session_limiter.clone(),
            udp_enabled: self.udp_enabled,
        }))
    }
}
