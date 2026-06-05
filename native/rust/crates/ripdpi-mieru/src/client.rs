//! Mieru outbound client (TCP carrier).
//!
//! The client runs the Mieru session over a caller-supplied, **already
//! protected** transport (the relay layer owns the `VpnService.protect()` dial,
//! per `PROTOCOL.md` §6) and exposes a tunnelled target as a plain
//! [`DuplexStream`]. One stream per session (multiplexing-`off` semantics); the
//! `multiplexing` knob is accepted but session reuse is not yet implemented.

use ring::rand::{SecureRandom, SystemRandom};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream};

use crate::config::{MieruConfig, MieruProtocol};
use crate::error::{MieruError, Result};
use crate::session::{DataReader, DataWriter, open_session, socks5_connect};

/// Size of the in-process duplex bridging the caller to the tunnel pumps.
const BRIDGE_BUF: usize = 64 * 1024;
/// Pump copy buffer (one application chunk; `<= MAX_PDU`).
const PUMP_BUF: usize = 32 * 1024;

/// A Mieru outbound client holding one established session.
pub struct MieruClient {
    writer: DataWriter,
    reader: DataReader,
}

impl MieruClient {
    /// Establish a Mieru session over an already-connected, protected transport.
    ///
    /// `now_unix` is the caller's **network-time** source in Unix seconds (the
    /// replay clock must not depend on the device wall clock). Only the TCP
    /// carrier is supported; a `udp` config returns [`MieruError::UdpUnsupported`].
    pub async fn connect_over<T>(transport: T, config: &MieruConfig, now_unix: i64) -> Result<Self>
    where
        T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        config.validate()?;
        if config.protocol == MieruProtocol::Udp {
            return Err(MieruError::UdpUnsupported);
        }
        let session_id = random_session_id()?;
        let (writer, reader) =
            open_session(transport, config.password.as_bytes(), config.username.as_bytes(), session_id, now_unix)
                .await?;
        Ok(Self { writer, reader })
    }

    /// Open a tunnelled byte stream to `target` (`host:port`) via the in-tunnel
    /// SOCKS5 server, consuming this session. Returns the caller-facing end of a
    /// duplex; two background pumps move bytes to/from the tunnel.
    pub async fn tcp_connect(self, target: &str) -> Result<DuplexStream> {
        let MieruClient { mut writer, mut reader } = self;
        socks5_connect(&mut writer, &mut reader, target).await?;

        let (caller, engine) = tokio::io::duplex(BRIDGE_BUF);
        let (mut engine_read, mut engine_write) = tokio::io::split(engine);

        // Outbound: caller writes -> data segments.
        tokio::spawn(async move {
            let mut buf = vec![0u8; PUMP_BUF];
            loop {
                match engine_read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if writer.send_data(&buf[..n]).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });

        // Inbound: data segments -> caller reads.
        tokio::spawn(async move {
            let mut buf = vec![0u8; PUMP_BUF];
            loop {
                match reader.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if engine_write.write_all(&buf[..n]).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });

        Ok(caller)
    }
}

fn random_session_id() -> Result<u32> {
    let rng = SystemRandom::new();
    let mut bytes = [0u8; 4];
    rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("session id rng"))?;
    let id = u32::from_be_bytes(bytes);
    Ok(if id == 0 { 1 } else { id })
}
