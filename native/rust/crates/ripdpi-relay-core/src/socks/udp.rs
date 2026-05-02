use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::AsyncReadExt;
use tokio::net::{TcpStream, UdpSocket};

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::{decode_udp_frame, encode_udp_frame};

const UDP_BUFFER_SIZE: usize = 65_536;

pub(crate) async fn handle_udp_associate<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    if !backend.udp_capable() {
        write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.backend_kind),
        ));
    }

    let mut udp_session = match backend.open_udp_session().await {
        Ok(session) => session,
        Err(error) => {
            telemetry.record_handshake_error(error.to_string());
            write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(error);
        }
    };

    let udp_socket = UdpSocket::bind(format!("{}:0", config.local_socks_host)).await?;
    let bound = udp_socket.local_addr()?;
    write_reply(&mut client, 0x00, bound).await?;

    let control_ip = client.peer_addr()?.ip();
    let mut associated_client = None;
    let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
    let mut control_probe = [0u8; 1];
    let control_closed = async {
        let _ = client.read(&mut control_probe).await;
    };
    tokio::pin!(control_closed);

    loop {
        tokio::select! {
            _ = &mut control_closed => break,
            recv = udp_socket.recv_from(&mut udp_buffer) => {
                let (received, source) = recv?;
                if source.ip() != control_ip {
                    continue;
                }
                associated_client = Some(source);
                let (target, payload) = decode_udp_frame(&udp_buffer[..received])?;
                telemetry.record_target(target.to_string());
                if let Err(error) = udp_session.send_to(&target, payload).await {
                    telemetry.record_handshake_error(error.to_string());
                    return Err(error);
                }
            }
            result = udp_session.recv_from() => {
                let (target, payload) = result?;
                let Some(destination) = associated_client else {
                    continue;
                };
                let frame = encode_udp_frame(&target, &payload)?;
                udp_socket.send_to(&frame, destination).await?;
            }
        }
    }

    Ok(())
}
