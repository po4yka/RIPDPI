pub(crate) mod protect;
pub mod socks5;
pub mod tcp;
#[cfg(test)]
pub(crate) mod test_socket;
pub mod udp;

pub use socks5::{Auth, TargetAddr};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;

use ripdpi_tunnel_config::Config;
pub use tcp::TcpSession;
pub use udp::UdpSession;

#[derive(Clone, PartialEq, Eq)]
pub enum ProxyUpstream {
    Tcp(SocketAddr),
    Unix(PathBuf),
}

impl ProxyUpstream {
    pub(crate) fn from_config(config: &Config) -> io::Result<Self> {
        if let Some(path) = &config.socks5.unix_path {
            return Ok(Self::Unix(path.into()));
        }
        let ip: IpAddr = config
            .socks5
            .address
            .parse()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid socks5.address"))?;
        Ok(Self::Tcp(SocketAddr::new(ip, config.socks5.port)))
    }
}

impl std::fmt::Debug for ProxyUpstream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Tcp(addr) => write!(f, "Tcp({addr})"),
            Self::Unix(_) => f.write_str("Unix(..)"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::ProxyUpstream;

    #[test]
    fn debug_output_never_contains_the_unix_path() {
        let unix = ProxyUpstream::Unix("/data/user/0/app/files/px-secret".into());
        let tcp = ProxyUpstream::Tcp("127.0.0.1:1080".parse().unwrap());

        assert_eq!(format!("{unix:?}"), "Unix(..)");
        assert_eq!(format!("{tcp:?}"), "Tcp(127.0.0.1:1080)");
    }
}
