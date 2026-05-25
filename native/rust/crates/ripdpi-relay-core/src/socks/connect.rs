use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Instant;

use tokio::io::copy_bidirectional;
use tokio::net::TcpStream;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::SocksTelemetry;
use crate::telemetry::TcpConnectObservation;

/// cancel-safe: the function either completes or is cancelled at an `.await`
/// point. RTT measurement uses wall-clock `Instant`, so a mid-cancel drop
/// loses at most one in-flight sample. The quality observation is emitted
/// synchronously before the first `.await` after the connect result is known.
pub(crate) async fn handle_connect<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    target: RelayTargetAddr,
    telemetry: &T,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    let connect_start = Instant::now();
    let upstream_result = backend.connect_tcp(&target).await;
    let rtt_ms = connect_start.elapsed().as_millis() as u64;

    let mut upstream = match upstream_result {
        Ok(stream) => {
            telemetry.emit_connect_observation(TcpConnectObservation { rtt_ms, succeeded: true });
            stream
        }
        Err(error) => {
            telemetry.emit_connect_observation(TcpConnectObservation { rtt_ms, succeeded: false });
            telemetry.record_handshake_error(error.to_string());
            write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(error);
        }
    };

    write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}
