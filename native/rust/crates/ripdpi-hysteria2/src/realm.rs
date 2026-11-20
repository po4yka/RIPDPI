//! Hysteria Realm client rendezvous.
//!
//! Wire format of sing-box `v1.14.0-alpha.22` (`sing-quic` `hysteria2/realm`),
//! the release the deploy role `hysteria-realm` pins: STUN discovery on the
//! QUIC socket, `POST /v1/{realm}/connect` to the rendezvous service, a UDP
//! hole punch, then the normal Hysteria2 handshake on the same socket.

use std::collections::HashSet;
use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use rand::RngExt;
use ripdpi_native_protect::SocketProtectionPolicy;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, UdpSocket};
use tokio::time::{Instant, timeout, timeout_at};

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::quic_transport::{QuicTransportConfig, build_client_udp_socket_with_policy};

const STUN_TIMEOUT: Duration = Duration::from_secs(4);
const STUN_DEFAULT_PORT: u16 = 3478;
const STUN_MAGIC_COOKIE: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];
const STUN_HEADER_LEN: usize = 20;
const STUN_XOR_MAPPED_ADDRESS: u16 = 0x0020;
/// The service holds a `connect` call for up to 10 s while the peer answers.
const CONTROL_TIMEOUT: Duration = Duration::from_secs(15);
const CONTROL_MAX_RESPONSE: usize = 64 * 1024;
const PUNCH_TIMEOUT: Duration = Duration::from_secs(10);
/// Bounds the whole rendezvous, name lookups included.
const RENDEZVOUS_TIMEOUT: Duration = Duration::from_secs(40);
const PUNCH_INTERVAL: Duration = Duration::from_millis(100);
const PUNCH_HELLO: u8 = 0x01;
const PUNCH_ACK: u8 = 0x02;
const PUNCH_MAGIC: [u8; 8] = *b"HYRLMv1\0";
const PUNCH_SALT_LEN: usize = 8;
const PUNCH_NONCE_LEN: usize = 16;
const PUNCH_MIN_BODY: usize = PUNCH_MAGIC.len() + 1 + PUNCH_NONCE_LEN;
const PUNCH_MAX_PADDING: usize = 1024;
/// Bounds the datagrams one answer of the rendezvous service can make this
/// client send. Upstream has no bound.
const PUNCH_MAX_PEERS: usize = 16;
const SYMMETRIC_NAT_PORT_GAP: u16 = 4;
const SYMMETRIC_NAT_EXTRA_PORTS: u32 = 4;
const SYMMETRIC_NAT_MAX_PORTS_PER_HOST: usize = 32;

/// Rendezvous settings of a Hysteria2 profile (`realm` block of the sing-box
/// hysteria2 outbound).
#[derive(Clone)]
pub struct RealmConfig {
    /// `https://` URL of the rendezvous service. `http://` is accepted for a
    /// loopback host only, because the request carries the bearer token.
    pub server_url: String,
    pub token: String,
    pub realm_id: String,
    /// `host` or `host:port`; the port defaults to 3478.
    pub stun_servers: Vec<String>,
}

impl fmt::Debug for RealmConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RealmConfig")
            .field("server_url", &self.server_url)
            .field("token", &"<redacted>")
            .field("realm_id", &self.realm_id)
            .field("stun_servers", &self.stun_servers)
            .finish()
    }
}

#[derive(Clone, Copy)]
struct PunchMetadata {
    nonce: [u8; PUNCH_NONCE_LEN],
    obfs: [u8; 32],
}

/// Runs the rendezvous and returns the punched socket with the peer address
/// that answered on it.
///
/// # Cancel safety:
/// cancel-safe: the socket and every partial exchange are owned by this
/// future and close when it is dropped. A dropped name lookup leaves at most
/// one detached resolver task. A peer that was already told to punch gives up
/// at its own timeout.
pub(crate) async fn rendezvous(config: &Config, realm: &RealmConfig) -> Result<(std::net::UdpSocket, SocketAddr)> {
    timeout(RENDEZVOUS_TIMEOUT, rendezvous_unbounded(config, realm))
        .await
        .map_err(|_| HysteriaError::RealmControl("realm rendezvous timed out".into()))?
}

/// # Cancel safety:
/// cancel-safe: see [`rendezvous`], which owns the deadline.
async fn rendezvous_unbounded(config: &Config, realm: &RealmConfig) -> Result<(std::net::UdpSocket, SocketAddr)> {
    let protection = config.socket_protection;
    // shortcut: IPv4 only, upgrade when a realm peer is reachable over IPv6 alone.
    let socket = build_client_udp_socket_with_policy(false, config.quic_bind_low_port, protection)?;
    socket.set_nonblocking(true)?;
    let socket = UdpSocket::from_std(socket)?;

    let local = discover(&socket, &realm.stun_servers, protection).await?;
    let offered = PunchMetadata { nonce: rand::random(), obfs: rand::random() };
    let peers = timeout(CONTROL_TIMEOUT, control_connect(realm, protection, &local, offered))
        .await
        .map_err(|_| HysteriaError::RealmControl("no answer from the rendezvous service".into()))??;
    let peer = punch(&socket, &peers, offered).await?;
    Ok((socket.into_std()?, peer))
}

/// # Cancel safety:
/// cancel-safe: only local buffers span an `.await`; a dropped name lookup
/// leaves at most one detached resolver task.
async fn discover(
    socket: &UdpSocket,
    servers: &[String],
    protection: SocketProtectionPolicy,
) -> Result<Vec<SocketAddr>> {
    let mut pending = HashSet::new();
    for server in servers {
        let (host, port) = split_stun_server(server);
        let addresses =
            protection.resolve_host(host, port).await.map_err(|error| HysteriaError::RealmStun(error.to_string()))?;
        for address in addresses.into_iter().filter(SocketAddr::is_ipv4) {
            let transaction: [u8; 12] = rand::random();
            let mut request = [0u8; STUN_HEADER_LEN];
            request[1] = 0x01; // Binding request, no attributes.
            request[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);
            request[8..].copy_from_slice(&transaction);
            if socket.send_to(&request, address).await.is_ok() {
                pending.insert(transaction);
            }
        }
    }
    if pending.is_empty() {
        return Err(HysteriaError::RealmStun("no STUN request could be sent".into()));
    }

    let deadline = Instant::now() + STUN_TIMEOUT;
    let mut found = Vec::new();
    let mut buffer = [0u8; 1500];
    while !pending.is_empty() {
        let Ok(received) = timeout_at(deadline, socket.recv_from(&mut buffer)).await else { break };
        let (len, _) = received?;
        if let Some((transaction, mapped)) = parse_stun_response(&buffer[..len])
            && pending.remove(&transaction)
            && let Some(mapped) = mapped
            && !found.contains(&mapped)
        {
            found.push(mapped);
        }
    }
    if found.is_empty() {
        return Err(HysteriaError::RealmStun("no STUN response received".into()));
    }
    Ok(found)
}

fn split_stun_server(server: &str) -> (&str, u16) {
    fn bare(host: &str) -> &str {
        host.trim_start_matches('[').trim_end_matches(']')
    }
    if server.parse::<IpAddr>().is_ok() {
        return (server, STUN_DEFAULT_PORT);
    }
    match server.rsplit_once(':') {
        Some((host, port)) if !bare(host).is_empty() => match port.parse() {
            Ok(port) => (bare(host), port),
            Err(_) => (server, STUN_DEFAULT_PORT),
        },
        _ => (bare(server), STUN_DEFAULT_PORT),
    }
}

/// Returns the transaction id and, when present and well formed, the
/// XOR-MAPPED-ADDRESS of a STUN message.
fn parse_stun_response(data: &[u8]) -> Option<([u8; 12], Option<SocketAddr>)> {
    if data.len() < STUN_HEADER_LEN || data[4..8] != STUN_MAGIC_COOKIE {
        return None;
    }
    let attributes_len = usize::from(u16::from_be_bytes([data[2], data[3]]));
    let mut attributes = data.get(STUN_HEADER_LEN..STUN_HEADER_LEN + attributes_len)?;
    let transaction: [u8; 12] = data[8..STUN_HEADER_LEN].try_into().ok()?;
    let mut mapped = None;
    while attributes.len() >= 4 {
        let kind = u16::from_be_bytes([attributes[0], attributes[1]]);
        let len = usize::from(u16::from_be_bytes([attributes[2], attributes[3]]));
        let padded = (len + 3) & !3;
        let Some(value) = attributes.get(4..4 + len) else { break };
        if kind == STUN_XOR_MAPPED_ADDRESS {
            mapped = xor_mapped_address(value, &data[4..STUN_HEADER_LEN]);
            break;
        }
        let Some(rest) = attributes.get(4 + padded..) else { break };
        attributes = rest;
    }
    Some((transaction, mapped))
}

/// `key` is the magic cookie followed by the transaction id.
fn xor_mapped_address(value: &[u8], key: &[u8]) -> Option<SocketAddr> {
    let port = u16::from_be_bytes([*value.get(2)? ^ key[0], *value.get(3)? ^ key[1]]);
    let xored: Vec<u8> = value[4..].iter().zip(key).map(|(byte, key)| byte ^ key).collect();
    let ip: IpAddr = match (value[1], xored.len()) {
        (0x01, 4) => Ipv4Addr::from(<[u8; 4]>::try_from(xored).ok()?).into(),
        (0x02, 16) => Ipv6Addr::from(<[u8; 16]>::try_from(xored).ok()?).into(),
        _ => return None,
    };
    Some(SocketAddr::new(ip, port))
}

/// # Cancel safety:
/// cancel-safe: the TCP and TLS state is owned by this future and closes when
/// it is dropped; a dropped name lookup leaves at most one detached resolver
/// task. A request that was already sent may still make the peer punch.
async fn control_connect(
    realm: &RealmConfig,
    protection: SocketProtectionPolicy,
    local: &[SocketAddr],
    offered: PunchMetadata,
) -> Result<Vec<SocketAddr>> {
    let control = |message: &str| HysteriaError::RealmControl(message.to_owned());
    let mut url = url::Url::parse(&realm.server_url).map_err(|_| control("invalid rendezvous URL"))?;
    let authority_host = url.host_str().ok_or_else(|| control("rendezvous URL has no host"))?.to_owned();
    let host = authority_host.trim_start_matches('[').trim_end_matches(']').to_owned();
    let loopback = host == "localhost" || host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback());
    let tls = match url.scheme() {
        "https" => true,
        "http" if loopback => false,
        _ => return Err(control("rendezvous URL must use https")),
    };
    if realm.token.contains(['\r', '\n']) {
        return Err(control("rendezvous token contains a line break"));
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(control("rendezvous URL must not carry credentials"));
    }
    // The url crate would fold these into the path instead of encoding them.
    if matches!(realm.realm_id.as_str(), "" | "." | "..") {
        return Err(control("invalid realm id"));
    }
    let port = url.port_or_known_default().ok_or_else(|| control("rendezvous URL has no port"))?;
    let authority = url.port().map_or(authority_host.clone(), |port| format!("{authority_host}:{port}"));
    url.path_segments_mut().map_err(|()| control("invalid rendezvous URL"))?.pop_if_empty().extend([
        "v1",
        realm.realm_id.as_str(),
        "connect",
    ]);

    let body = serde_json::json!({
        "addresses": local.iter().map(ToString::to_string).collect::<Vec<_>>(),
        "nonce": hex_encode(&offered.nonce),
        "obfs": hex_encode(&offered.obfs),
    })
    .to_string();
    let authorization =
        if realm.token.is_empty() { String::new() } else { format!("Authorization: Bearer {}\r\n", realm.token) };
    let request = format!(
        "POST {} HTTP/1.1\r\nHost: {authority}\r\n{authorization}Content-Type: application/json\r\n\
         Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
        url.path(),
        body.len(),
    );

    let mut stream = Err(control("rendezvous host resolved to no address"));
    for address in protection.resolve_host(&host, port).await? {
        // The token must not leave in cleartext, whatever `localhost` resolves to.
        if !tls && !address.ip().is_loopback() {
            continue;
        }
        let socket = if address.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
        protection.protect_non_loopback(socket.as_raw_fd(), address)?;
        stream = socket.connect(address).await.map_err(HysteriaError::from);
        if stream.is_ok() {
            break;
        }
    }
    let stream = stream?;
    let (status, response) = if tls {
        let tls_config = QuicTransportConfig::new(host.clone())
            .with_alpn(vec![b"http/1.1".to_vec()])
            .build_rustls_client_config()?;
        let name =
            rustls::pki_types::ServerName::try_from(host).map_err(|_| control("invalid rendezvous host name"))?;
        let stream = tokio_rustls::TlsConnector::from(Arc::new(tls_config)).connect(name, stream).await?;
        exchange(stream, request.as_bytes()).await?
    } else {
        exchange(stream, request.as_bytes()).await?
    };

    let json: serde_json::Value = serde_json::from_slice(&response).unwrap_or_default();
    if !(200..300).contains(&status) {
        // The error code is server text: keep it only when it is a plain identifier.
        let code = json["error"]
            .as_str()
            .filter(|code| code.len() <= 32 && code.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'_'));
        return Err(HysteriaError::RealmControl(format!("status {status} {}", code.unwrap_or("unknown"))));
    }
    connect_answer_peers(&json, offered, loopback)
}

/// Peers of a `connect` answer. The service echoes the caller's nonce and obfs
/// key, so an answer with other values did not come from this attempt. A peer
/// address is STUN-mapped and therefore public; a local one is accepted only
/// from a loopback service (a test fixture).
fn connect_answer_peers(
    json: &serde_json::Value,
    offered: PunchMetadata,
    local_service: bool,
) -> Result<Vec<SocketAddr>> {
    let control = |message: &str| HysteriaError::RealmControl(message.to_owned());
    let nonce: Option<[u8; PUNCH_NONCE_LEN]> = json["nonce"].as_str().and_then(hex_decode);
    let obfs: Option<[u8; 32]> = json["obfs"].as_str().and_then(hex_decode);
    if nonce != Some(offered.nonce) || obfs != Some(offered.obfs) {
        return Err(control("rendezvous answer does not match this attempt"));
    }
    let peers: Vec<SocketAddr> = json["addresses"]
        .as_array()
        .and_then(|addresses| {
            addresses.iter().take(PUNCH_MAX_PEERS).map(|address| address.as_str()?.parse().ok()).collect()
        })
        .ok_or_else(|| control("rendezvous answer has no valid addresses"))?;
    Ok(peers.into_iter().filter(|peer| local_service || !is_local(peer.ip())).collect())
}

fn is_local(ip: IpAddr) -> bool {
    match ip {
        // 100.64.0.0/10 is carrier-grade NAT space.
        IpAddr::V4(ip) => {
            ip.is_loopback()
                || ip.is_private()
                || ip.is_link_local()
                || (ip.octets()[0] == 100 && ip.octets()[1] & 0xc0 == 64)
        }
        IpAddr::V6(ip) => ip.is_loopback() || ip.is_unique_local() || ip.is_unicast_link_local(),
    }
}

/// Sends one HTTP/1.1 request and reads the status and body of the answer.
///
/// # Cancel safety:
/// cancel-safe: the stream is owned by this future and closes when it is dropped.
async fn exchange<S: AsyncRead + AsyncWrite + Unpin>(mut stream: S, request: &[u8]) -> Result<(u16, Vec<u8>)> {
    stream.write_all(request).await?;
    stream.flush().await?;
    let mut raw = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        // A peer that closes without a TLS close_notify, or resets, ended the stream.
        let read = match stream.read(&mut buffer).await {
            Ok(read) => read,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionReset | io::ErrorKind::BrokenPipe
                ) =>
            {
                0
            }
            Err(error) => return Err(error.into()),
        };
        raw.extend_from_slice(&buffer[..read]);
        if raw.len() > CONTROL_MAX_RESPONSE {
            return Err(HysteriaError::RealmControl("rendezvous answer is too large".into()));
        }
        if let Some(answer) = parse_http_response(&raw, read == 0)? {
            return Ok(answer);
        }
        if read == 0 {
            return Err(HysteriaError::RealmControl("rendezvous answer is truncated".into()));
        }
    }
}

/// Returns `None` until the answer is complete.
fn parse_http_response(raw: &[u8], end_of_stream: bool) -> Result<Option<(u16, Vec<u8>)>> {
    let mut headers = [httparse::EMPTY_HEADER; 32];
    let mut response = httparse::Response::new(&mut headers);
    let head = match response.parse(raw) {
        Ok(httparse::Status::Complete(head)) => head,
        Ok(httparse::Status::Partial) => return Ok(None),
        Err(_) => return Err(HysteriaError::RealmControl("rendezvous answer is not HTTP".into())),
    };
    let status = response.code.unwrap_or(0);
    let header = |name: &str| {
        response
            .headers
            .iter()
            .find(|header| header.name.eq_ignore_ascii_case(name))
            .and_then(|header| std::str::from_utf8(header.value).ok())
    };
    let body = &raw[head..];
    if header("transfer-encoding").is_some_and(|value| value.to_ascii_lowercase().contains("chunked")) {
        return Ok(decode_chunked(body).map(|body| (status, body)));
    }
    if let Some(length) = header("content-length").and_then(|value| value.trim().parse::<usize>().ok()) {
        return Ok(body.get(..length).map(|body| (status, body.to_vec())));
    }
    Ok(end_of_stream.then(|| (status, body.to_vec())))
}

/// Returns `None` until the terminating zero-size chunk has arrived.
fn decode_chunked(mut body: &[u8]) -> Option<Vec<u8>> {
    let mut decoded = Vec::new();
    loop {
        let line_end = body.windows(2).position(|window| window == b"\r\n")?;
        let size_text = std::str::from_utf8(&body[..line_end]).ok()?;
        let size = usize::from_str_radix(size_text.split(';').next()?.trim(), 16).ok()?;
        if size == 0 {
            return Some(decoded);
        }
        // The size is server text: a huge value must not overflow the offsets.
        let start = line_end + 2;
        let end = start.checked_add(size)?;
        decoded.extend_from_slice(body.get(start..end)?);
        body = body.get(end.checked_add(2)?..)?;
    }
}

/// # Cancel safety:
/// cancel-safe: only local buffers span an `.await`.
async fn punch(socket: &UdpSocket, peers: &[SocketAddr], metadata: PunchMetadata) -> Result<SocketAddr> {
    let candidates = punch_candidates(peers);
    if candidates.is_empty() {
        return Err(HysteriaError::RealmPunch("no compatible peer address".into()));
    }
    let deadline = Instant::now() + PUNCH_TIMEOUT;
    let mut next_send = Instant::now();
    let mut buffer = [0u8; 1500];
    loop {
        let now = Instant::now();
        if now >= deadline {
            return Err(HysteriaError::RealmPunch("no answer from the peer".into()));
        }
        if now >= next_send {
            for candidate in &candidates {
                let _ = socket.send_to(&encode_punch_packet(PUNCH_HELLO, metadata), candidate).await;
            }
            next_send = now + PUNCH_INTERVAL;
        }
        let Ok(received) = timeout_at(next_send.min(deadline), socket.recv_from(&mut buffer)).await else { continue };
        let (len, from) = received?;
        // The port may differ from every candidate behind a symmetric NAT; the host may not.
        if !candidates.iter().any(|candidate| candidate.ip() == from.ip()) {
            continue;
        }
        let Some(kind) = decode_punch_packet(&buffer[..len], metadata) else { continue };
        if kind == PUNCH_HELLO {
            let _ = socket.send_to(&encode_punch_packet(PUNCH_ACK, metadata), from).await;
        }
        return Ok(from);
    }
}

/// Deduplicated IPv4 peers, plus the next ports of a host whose reported
/// ports are close together (a symmetric NAT with predictable allocation).
fn punch_candidates(peers: &[SocketAddr]) -> Vec<SocketAddr> {
    let mut candidates: Vec<SocketAddr> = Vec::new();
    for peer in peers {
        let targetable =
            matches!(peer.ip(), IpAddr::V4(ip) if !(ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast()));
        if targetable && peer.port() != 0 && !candidates.contains(peer) {
            candidates.push(*peer);
        }
    }
    let hosts: Vec<IpAddr> = candidates.iter().map(SocketAddr::ip).collect::<HashSet<_>>().into_iter().collect();
    for host in hosts {
        let mut ports: Vec<u16> = candidates.iter().filter(|peer| peer.ip() == host).map(SocketAddr::port).collect();
        ports.sort_unstable();
        if ports.len() < 2 || ports.windows(2).any(|pair| pair[1] - pair[0] > SYMMETRIC_NAT_PORT_GAP) {
            continue;
        }
        let end = (u32::from(ports[ports.len() - 1]) + SYMMETRIC_NAT_EXTRA_PORTS).min(u32::from(u16::MAX));
        let predicted: Vec<SocketAddr> = (u32::from(ports[0])..=end)
            .filter_map(|port| u16::try_from(port).ok())
            .map(|port| SocketAddr::new(host, port))
            .filter(|address| !candidates.contains(address))
            .take(SYMMETRIC_NAT_MAX_PORTS_PER_HOST)
            .collect();
        candidates.extend(predicted);
    }
    candidates
}

fn punch_key(metadata: PunchMetadata, salt: &[u8]) -> [u8; 32] {
    Sha256::new().chain_update(metadata.obfs).chain_update(salt).finalize().into()
}

fn encode_punch_packet(kind: u8, metadata: PunchMetadata) -> Vec<u8> {
    let mut rng = rand::rng();
    let padding = rng.random_range(0..=PUNCH_MAX_PADDING);
    let mut packet = vec![0u8; PUNCH_SALT_LEN + PUNCH_MIN_BODY + padding];
    rng.fill(&mut packet[..]);
    let (salt, body) = packet.split_at_mut(PUNCH_SALT_LEN);
    body[..PUNCH_MAGIC.len()].copy_from_slice(&PUNCH_MAGIC);
    body[PUNCH_MAGIC.len()] = kind;
    body[PUNCH_MAGIC.len() + 1..PUNCH_MIN_BODY].copy_from_slice(&metadata.nonce);
    let key = punch_key(metadata, salt);
    for (index, byte) in body.iter_mut().enumerate() {
        *byte ^= key[index % key.len()];
    }
    packet
}

/// Returns the packet type of a punch packet that carries this attempt's nonce.
fn decode_punch_packet(data: &[u8], metadata: PunchMetadata) -> Option<u8> {
    if !(PUNCH_SALT_LEN + PUNCH_MIN_BODY..=PUNCH_SALT_LEN + PUNCH_MIN_BODY + PUNCH_MAX_PADDING).contains(&data.len()) {
        return None;
    }
    let (salt, body) = data.split_at(PUNCH_SALT_LEN);
    let key = punch_key(metadata, salt);
    let body: Vec<u8> =
        body[..PUNCH_MIN_BODY].iter().enumerate().map(|(index, byte)| byte ^ key[index % key.len()]).collect();
    let kind = body[PUNCH_MAGIC.len()];
    (body[..PUNCH_MAGIC.len()] == PUNCH_MAGIC
        && matches!(kind, PUNCH_HELLO | PUNCH_ACK)
        && body[PUNCH_MAGIC.len() + 1..] == metadata.nonce)
        .then_some(kind)
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn hex_decode<const N: usize>(text: &str) -> Option<[u8; N]> {
    let mut bytes = [0u8; N];
    if text.len() != N * 2 || !text.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metadata() -> PunchMetadata {
        PunchMetadata { nonce: std::array::from_fn(|index| 0xa0 + index as u8), obfs: std::array::from_fn(|i| i as u8) }
    }

    /// Vector computed with Python `hashlib`: salt 01..08, a HELLO for the
    /// metadata above, two padding bytes.
    #[test]
    fn decodes_an_independently_computed_punch_packet() {
        let packet: Vec<u8> = (0.."0102030405060708d635bd515f2b38ba0d2ed5463b98f0c4482f6ae8611634765d00ba".len())
            .step_by(2)
            .map(|index| {
                u8::from_str_radix(
                    &"0102030405060708d635bd515f2b38ba0d2ed5463b98f0c4482f6ae8611634765d00ba"[index..index + 2],
                    16,
                )
                .unwrap()
            })
            .collect();
        assert_eq!(decode_punch_packet(&packet, metadata()), Some(PUNCH_HELLO));

        let mut other = metadata();
        other.nonce[0] ^= 1;
        assert_eq!(decode_punch_packet(&packet, other), None, "a packet of another attempt is ignored");
    }

    #[test]
    fn encoded_punch_packets_decode_and_stay_within_the_padding_limit() {
        for _ in 0..64 {
            let packet = encode_punch_packet(PUNCH_ACK, metadata());
            assert!(packet.len() <= PUNCH_SALT_LEN + PUNCH_MIN_BODY + PUNCH_MAX_PADDING);
            assert_eq!(decode_punch_packet(&packet, metadata()), Some(PUNCH_ACK));
        }
    }

    #[test]
    fn close_ports_of_one_host_add_the_predicted_ports() {
        let peers: Vec<SocketAddr> =
            ["198.51.100.7:4000", "198.51.100.7:4002", "203.0.113.9:5000", "[2001:db8::1]:6000", "198.51.100.7:4000"]
                .iter()
                .map(|peer| peer.parse().unwrap())
                .collect();
        let mut ports: Vec<u16> = punch_candidates(&peers)
            .into_iter()
            .filter(|peer| peer.ip().to_string() == "198.51.100.7")
            .map(|peer| peer.port())
            .collect();
        ports.sort_unstable();
        assert_eq!(ports, [4000, 4001, 4002, 4003, 4004, 4005, 4006]);
        assert_eq!(punch_candidates(&peers).iter().filter(|peer| peer.ip().to_string() == "203.0.113.9").count(), 1);
        assert!(punch_candidates(&peers).iter().all(SocketAddr::is_ipv4));
    }

    #[test]
    fn stun_response_yields_the_xor_mapped_address() {
        // RFC 5769 section 2.2 sample response, reduced to its XOR-MAPPED-ADDRESS (192.0.2.1:32853).
        let mut response = vec![0x01, 0x01, 0x00, 0x0c, 0x21, 0x12, 0xa4, 0x42];
        response.extend_from_slice(&[0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae]);
        response.extend_from_slice(&[0x00, 0x20, 0x00, 0x08, 0x00, 0x01, 0xa1, 0x47, 0xe1, 0x12, 0xa6, 0x43]);
        let (transaction, mapped) = parse_stun_response(&response).expect("STUN message");
        assert_eq!(transaction[..2], [0xb7, 0xe7]);
        assert_eq!(mapped, Some("192.0.2.1:32853".parse().unwrap()));
    }

    #[test]
    fn a_connect_answer_must_echo_this_attempt_and_name_public_peers() {
        let offered = metadata();
        let nonce = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf";
        let obfs = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        let answer = |nonce: &str, obfs: &str| {
            serde_json::json!({
                "addresses": ["198.51.100.7:4000", "192.168.1.1:53", "100.64.0.1:4000", "127.0.0.1:4000"],
                "nonce": nonce,
                "obfs": obfs,
            })
        };
        let peers = |local_service| connect_answer_peers(&answer(nonce, obfs), offered, local_service).unwrap();
        assert_eq!(peers(false), ["198.51.100.7:4000".parse::<SocketAddr>().unwrap()]);
        assert_eq!(peers(true).len(), 4, "a loopback test service may name local peers");

        let other_nonce = "b0a1a2a3a4a5a6a7a8a9aaabacadaeaf";
        assert!(connect_answer_peers(&answer(other_nonce, obfs), offered, false).is_err());
        let other_obfs = "ff0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        assert!(connect_answer_peers(&answer(nonce, other_obfs), offered, false).is_err());
    }

    #[test]
    fn peers_that_are_not_unicast_ipv4_are_never_punched() {
        let peers: Vec<SocketAddr> = ["0.0.0.0:4000", "224.0.0.1:4000", "255.255.255.255:4000", "198.51.100.7:0"]
            .iter()
            .map(|peer| peer.parse().unwrap())
            .collect();
        assert!(punch_candidates(&peers).is_empty());
        assert_eq!(hex_decode::<1>("+f"), None);
        assert_eq!(hex_decode::<1>("0f"), Some([0x0f]));
    }

    /// # Cancel safety:
    /// cancel-safe: test body; nothing outlives it.
    #[tokio::test]
    async fn the_token_is_refused_for_cleartext_or_credentialed_urls_before_any_connection() {
        let realm = |server_url: &str, realm_id: &str| RealmConfig {
            server_url: server_url.into(),
            token: "test-token".into(),
            realm_id: realm_id.into(),
            stun_servers: Vec::new(),
        };
        let offered = PunchMetadata { nonce: [0; 16], obfs: [0; 32] };
        for (url, id) in [
            ("http://realm.example.com", "realm-a"),
            ("https://user:secret@realm.example.com", "realm-a"),
            ("https://realm.example.com", ".."),
            ("ftp://realm.example.com", "realm-a"),
        ] {
            let result = control_connect(&realm(url, id), SocketProtectionPolicy::Inactive, &[], offered).await;
            assert!(matches!(result, Err(HysteriaError::RealmControl(_))), "{url} {id}");
        }
    }

    #[test]
    fn stun_server_port_defaults_to_3478() {
        assert_eq!(split_stun_server("stun.example.com"), ("stun.example.com", 3478));
        assert_eq!(split_stun_server("stun.example.com:19302"), ("stun.example.com", 19302));
        assert_eq!(split_stun_server("192.0.2.1"), ("192.0.2.1", 3478));
        assert_eq!(split_stun_server("[2001:db8::1]:3479"), ("2001:db8::1", 3479));
        assert_eq!(split_stun_server("2001:db8::1"), ("2001:db8::1", 3478));
    }

    #[test]
    fn http_answers_are_complete_by_length_chunks_or_end_of_stream() {
        let by_length = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}";
        assert_eq!(parse_http_response(by_length, false).unwrap(), Some((200, b"{}".to_vec())));
        assert_eq!(parse_http_response(&by_length[..by_length.len() - 1], false).unwrap(), None);
        let chunked = b"HTTP/1.1 401 Unauthorized\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1\r\n}\r\n0\r\n\r\n";
        assert_eq!(parse_http_response(chunked, false).unwrap(), Some((401, b"{}".to_vec())));
        let huge_chunk = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffffffffffffffff\r\n{}";
        assert_eq!(parse_http_response(huge_chunk, false).unwrap(), None, "an oversized chunk never completes");
        let until_close = b"HTTP/1.1 200 OK\r\n\r\n{}";
        assert_eq!(parse_http_response(until_close, false).unwrap(), None);
        assert_eq!(parse_http_response(until_close, true).unwrap(), Some((200, b"{}".to_vec())));
    }
}
