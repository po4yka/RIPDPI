use std::collections::{HashMap, HashSet};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;

use bytes::{Bytes, BytesMut};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket, lookup_host};

use crate::ports::{PortForwardConfig, PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::support::{MAX_PACKET, to_io_error};
use crate::virtual_iface::{Bus, Event};

pub(crate) async fn handle_socks_client(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    udp_pool: Arc<UdpAssociationPool>,
) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }
    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    client.read_exact(&mut methods).await?;
    client.write_all(&[0x05, 0x00]).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS request"));
    }
    let target = read_target(&mut client, request_header[3]).await?;

    match request_header[1] {
        0x01 => handle_tcp_connect(client, bus, tcp_pool, target).await,
        0x03 => handle_udp_associate(client, bus, udp_pool).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS command unsupported"))
        }
    }
}

async fn handle_tcp_connect(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    target: SocketAddr,
) -> io::Result<()> {
    let virtual_port = tcp_pool.acquire().await?;
    let port_forward = PortForwardConfig { destination: target, protocol: PortProtocol::Tcp };
    let mut endpoint = bus.new_endpoint();
    endpoint.send(Event::ClientConnectionInitiated(port_forward, virtual_port));
    write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;

    let mut buffer = BytesMut::with_capacity(MAX_PACKET);
    // Pending remote->client write, held outside the select! so the write is not
    // entangled with the readable arm and cancel reasoning stays simple.
    let mut pending_write: Option<Bytes> = None;
    loop {
        if let Some(data) = pending_write.take() {
            client.write_all(&data).await?;
        }
        tokio::select! {
            readable_result = client.readable() => {
                match readable_result {
                    Ok(_) => match client.try_read_buf(&mut buffer) {
                        Ok(size) if size > 0 => {
                            endpoint.send(Event::LocalData(port_forward, virtual_port, Bytes::copy_from_slice(&buffer[..size])));
                            buffer.clear();
                        }
                        Ok(_) => break,
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                        Err(error) => return Err(error),
                    },
                    Err(error) => return Err(error),
                }
            }
            event = endpoint.recv() => match event {
                Event::Shutdown => break,
                Event::ClientConnectionDropped(event_port) if event_port == virtual_port => break,
                Event::RemoteData(event_port, data) if event_port == virtual_port => {
                    pending_write = Some(data);
                }
                _ => {}
            }
        }
    }

    endpoint.send(Event::ClientConnectionDropped(virtual_port));
    tcp_pool.release(virtual_port).await;
    Ok(())
}

async fn handle_udp_associate(mut control: TcpStream, bus: Bus, udp_pool: Arc<UdpAssociationPool>) -> io::Result<()> {
    let udp_socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await?;
    let bind_addr = udp_socket.local_addr()?;
    write_reply(&mut control, 0x00, bind_addr).await?;
    let association_bind_port = bind_addr.port();
    let mut endpoint = bus.new_endpoint();
    let mut buffer = [0u8; MAX_PACKET];
    let mut known_ports = HashSet::new();
    let mut targets = HashMap::new();

    loop {
        tokio::select! {
            readable_result = control.readable() => {
                match readable_result {
                    Ok(_) => {
                        let mut one = [0u8; 1];
                        match control.try_read(&mut one) {
                            Ok(0) => break,
                            Ok(_) => continue,
                            Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                            Err(error) => return Err(error),
                        }
                    }
                    Err(error) => return Err(error),
                }
            }
            recv_result = udp_socket.recv_from(&mut buffer) => {
                let (size, peer_addr) = recv_result?;
                let (target, payload) = parse_socks_udp_request(&buffer[..size]).map_err(to_io_error)?;
                let virtual_port = udp_pool.acquire(association_bind_port, peer_addr).await?;
                known_ports.insert(virtual_port);
                targets.insert(virtual_port, target);
                let port_forward = PortForwardConfig { destination: target, protocol: PortProtocol::Udp };
                endpoint.send(Event::LocalData(port_forward, virtual_port, payload));
            }
            event = endpoint.recv() => match event {
                Event::Shutdown => break,
                Event::RemoteData(virtual_port, data) if known_ports.contains(&virtual_port) => {
                    if let Some(peer_addr) = udp_pool.peer_addr(virtual_port).await {
                        let target = targets.get(&virtual_port).copied().unwrap_or_else(|| SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)));
                        let packet = encode_socks_udp_response(target, &data);
                        udp_socket.send_to(&packet, peer_addr).await?;
                    }
                }
                _ => {}
            }
        }
    }

    udp_pool.release_association(association_bind_port).await;
    Ok(())
}
async fn read_target(client: &mut TcpStream, address_type: u8) -> io::Result<SocketAddr> {
    let host = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets)).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            String::from_utf8(host).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid socks host"))?
        }
        0x04 => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "IPv6 SOCKS targets are not supported by the current WARP runtime",
            ));
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks address type"));
        }
    };
    let mut port = [0u8; 2];
    client.read_exact(&mut port).await?;
    let port = u16::from_be_bytes(port);
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        Ok(SocketAddr::V4(SocketAddrV4::new(ip, port)))
    } else {
        lookup_host((host.as_str(), port))
            .await?
            .find(SocketAddr::is_ipv4)
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve IPv4 target"))
    }
}

fn parse_socks_udp_request(buffer: &[u8]) -> anyhow::Result<(SocketAddr, Bytes)> {
    if buffer.len() < 10 {
        anyhow::bail!("socks udp datagram too short");
    }
    if buffer[2] != 0 {
        anyhow::bail!("fragmented socks udp datagrams are unsupported");
    }
    let address_type = buffer[3];
    let (target, payload_offset) = match address_type {
        0x01 => {
            let ip = Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);
            let port = u16::from_be_bytes([buffer[8], buffer[9]]);
            (SocketAddr::V4(SocketAddrV4::new(ip, port)), 10)
        }
        _ => anyhow::bail!("only ipv4 socks udp targets are supported"),
    };
    Ok((target, Bytes::copy_from_slice(&buffer[payload_offset..])))
}

fn encode_socks_udp_response(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    match target {
        SocketAddr::V4(addr) => {
            let mut packet = Vec::with_capacity(10 + payload.len());
            packet.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
            packet.extend_from_slice(payload);
            packet
        }
        SocketAddr::V6(_) => payload.to_vec(),
    }
}
async fn write_reply(client: &mut TcpStream, code: u8, bind_addr: SocketAddr) -> io::Result<()> {
    match bind_addr {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, code, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            client.write_all(&reply).await
        }
        SocketAddr::V6(_) => Err(io::Error::new(io::ErrorKind::Unsupported, "ipv6 bind replies are unsupported")),
    }
}

/// Validate that the SOCKS5 listener host is a loopback address before binding.
///
/// The config resolver already pins `127.0.0.1`, but the runtime is the last
/// line of defence: a non-loopback bind would expose the in-tunnel SOCKS proxy
/// as a routable inbound surface, contrary to RIPDPI's no-inbound posture.
/// Fails closed -- any non-loopback or non-IP-literal host returns `Err`, so the
/// tunnel refuses to start rather than open a routable listener.
pub(crate) fn ensure_loopback_socks_host(host: &str) -> io::Result<()> {
    let ip: IpAddr = host.parse().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("SOCKS bind host {host:?} is not an IP literal"))
    })?;
    if ip.is_loopback() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::InvalidInput, format!("refusing non-loopback SOCKS bind host {host}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ensure_loopback_socks_host_accepts_loopback_and_rejects_routable() {
        assert!(ensure_loopback_socks_host("127.0.0.1").is_ok());
        assert!(ensure_loopback_socks_host("::1").is_ok());
        assert!(ensure_loopback_socks_host("0.0.0.0").is_err());
        assert!(ensure_loopback_socks_host("192.0.2.10").is_err());
        assert!(ensure_loopback_socks_host("not-an-ip").is_err());
    }

    #[test]
    fn parse_socks_udp_request_extracts_ipv4_target_and_payload() {
        let packet = [0x00, 0x00, 0x00, 0x01, 192, 0, 2, 10, 0x1F, 0x90, b'p', b'i', b'n', b'g'];

        let (target, payload) = parse_socks_udp_request(&packet).expect("socks udp request");

        assert_eq!(target, SocketAddr::from(([192, 0, 2, 10], 8080)));
        assert_eq!(&payload[..], b"ping");
    }

    #[test]
    fn parse_socks_udp_request_rejects_fragmented_datagrams() {
        let packet = [0x00, 0x00, 0x01, 0x01, 192, 0, 2, 10, 0x1F, 0x90];

        let error = parse_socks_udp_request(&packet).expect_err("fragmented datagram should fail");

        assert!(error.to_string().contains("fragmented"));
    }

    #[test]
    fn encode_socks_udp_response_wraps_ipv4_payload() {
        let target = SocketAddr::from(([203, 0, 113, 7], 5353));

        let packet = encode_socks_udp_response(target, b"dns");

        assert_eq!(&packet, &[0, 0, 0, 1, 203, 0, 113, 7, 0x14, 0xE9, b'd', b'n', b's']);
    }
}
