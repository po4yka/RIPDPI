use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Instant;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::telemetry::{ChainHopRole, ChainHopTelemetryState};

#[derive(Clone)]
pub(crate) struct ChainRelaySessionFactory {
    pub(crate) entry: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) exit: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) telemetry: ChainHopTelemetryState,
}

pub(crate) struct ChainRelaySession {
    pub(crate) entry: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) exit: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) telemetry: ChainHopTelemetryState,
}

impl RelaySession for ChainRelaySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let exit_target = format!("{}:{}", self.exit.server, self.exit.port);
            self.telemetry.record(ChainHopRole::Entry, "connecting", None);
            let entry_started = Instant::now();
            let first_hop_result = match self.outbound_bind_ip {
                Some(bind_ip) => {
                    ripdpi_vless::VlessRealityClient::connect_with_bind(&self.entry, bind_ip, &exit_target).await
                }
                None => ripdpi_vless::VlessRealityClient::connect(&self.entry, &exit_target).await,
            };
            let first_hop = match first_hop_result {
                Ok(stream) => {
                    self.telemetry.record(
                        ChainHopRole::Entry,
                        "connected",
                        Some(entry_started.elapsed().as_millis() as u64),
                    );
                    stream
                }
                Err(error) => {
                    self.telemetry.record(
                        ChainHopRole::Entry,
                        "failed",
                        Some(entry_started.elapsed().as_millis() as u64),
                    );
                    return Err(error);
                }
            };
            self.telemetry.record(ChainHopRole::Exit, "connecting", None);
            let exit_started = Instant::now();
            let second_hop_result = ripdpi_vless::VlessRealityClient::connect_over(&self.exit, first_hop, target).await;
            let second_hop = match second_hop_result {
                Ok(stream) => {
                    self.telemetry.record(
                        ChainHopRole::Exit,
                        "connected",
                        Some(exit_started.elapsed().as_millis() as u64),
                    );
                    stream
                }
                Err(error) => {
                    self.telemetry.record(
                        ChainHopRole::Exit,
                        "failed",
                        Some(exit_started.elapsed().as_millis() as u64),
                    );
                    return Err(error);
                }
            };
            let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(second_hop);
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "chain relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for ChainRelaySessionFactory {
    type Session = ChainRelaySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let entry = self.entry.clone();
        let exit = self.exit.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        let telemetry = self.telemetry.clone();
        Box::pin(async move { Ok(Arc::new(ChainRelaySession { entry, exit, outbound_bind_ip, telemetry })) })
    }
}
