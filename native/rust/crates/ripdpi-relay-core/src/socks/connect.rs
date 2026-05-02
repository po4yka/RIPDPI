use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::copy_bidirectional;
use tokio::net::TcpStream;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::SocksTelemetry;

pub(crate) async fn handle_connect<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    target: RelayTargetAddr,
    telemetry: &T,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    let mut upstream = match backend.connect_tcp(&target).await {
        Ok(stream) => stream,
        Err(error) => {
            telemetry.record_handshake_error(error.to_string());
            write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(error);
        }
    };

    write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}
