use std::io;
use std::net::IpAddr;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

#[derive(Clone)]
pub(crate) struct VlessRealitySessionFactory {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
}

pub(crate) struct VlessRealitySession {
    pub(crate) config: ripdpi_vless::config::VlessRealityConfig,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
}

impl RelaySession for VlessRealitySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = match self.outbound_bind_ip {
            Some(bind_ip) => ripdpi_vless::VlessRealityClient::connect_with_bind(&self.config, bind_ip, target).await?,
            None => ripdpi_vless::VlessRealityClient::connect(&self.config, target).await?,
        };
        let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS Reality relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for VlessRealitySessionFactory {
    type Session = VlessRealitySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        Ok(Arc::new(VlessRealitySession { config, outbound_bind_ip }))
    }
}
