use std::io;
use std::net::IpAddr;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

#[derive(Clone)]
pub(crate) struct VlessRealitySessionFactory {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) udp_enabled: bool,
}

pub(crate) struct VlessRealitySession {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) mux: Option<ripdpi_vless::VlessYamuxSession>,
    pub(crate) udp_enabled: bool,
}

impl RelaySession for VlessRealitySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ripdpi_vless::VlessXudpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        if let Some(mux) = &self.mux {
            let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(mux.open_stream(target).await?);
            return Ok(stream);
        }
        let stream = match self.outbound_bind_ip {
            Some(bind_ip) => ripdpi_vless::VlessRealityClient::connect_with_bind(&self.config, bind_ip, target).await?,
            None => ripdpi_vless::VlessRealityClient::connect(&self.config, target).await?,
        };
        let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        if !self.udp_enabled || self.config.flow == ripdpi_vless::addons::VlessFlow::None {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS Reality XUDP is disabled for this profile"));
        }
        ripdpi_vless::VlessRealityClient::connect_xudp(&self.config, self.outbound_bind_ip).await
    }
}

impl RelaySessionFactory for VlessRealitySessionFactory {
    type Session = VlessRealitySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities {
            tcp: true,
            udp: self.udp_enabled && self.config.flow != ripdpi_vless::addons::VlessFlow::None,
            reusable: self.config.mux.is_some(),
        }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        let mux = if config.mux.is_some() {
            Some(ripdpi_vless::VlessRealityClient::connect_mux(&config, outbound_bind_ip).await?)
        } else {
            None
        };
        Ok(Arc::new(VlessRealitySession { config, outbound_bind_ip, mux, udp_enabled: self.udp_enabled }))
    }
}
