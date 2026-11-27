pub(crate) mod protect;
pub mod socks5;
pub mod tcp;
#[cfg(test)]
pub(crate) mod test_socket;
pub mod udp;

pub use socks5::{Auth, TargetAddr};
use std::net::SocketAddr;
use std::path::PathBuf;
pub use tcp::TcpSession;
pub use udp::UdpSession;

#[derive(Clone, PartialEq, Eq)]
pub enum ProxyUpstream {
    Tcp(SocketAddr),
    Unix(PathBuf),
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
