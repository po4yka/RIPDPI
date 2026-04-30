use std::collections::HashMap;
use std::io;
use std::sync::Arc;

use bytes::{Bytes, BytesMut};
use h3_datagram::datagram_handler::DatagramSender;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::client::MasqueClientInner;
use crate::h3::attempt_h3_connect_udp;
use crate::response::{classify_attempt_failure, AttemptError};

pub(crate) const UDP_CONTEXT_ID: u8 = 0;

type H3DatagramSender = DatagramSender<h3_quinn::datagram::SendDatagramHandler, Bytes>;

pub struct MasqueUdpRelay {
    pub(crate) client: Arc<MasqueClientInner>,
    pub(crate) flows: HashMap<String, MasqueUdpFlow>,
    pub(crate) incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
    pub(crate) incoming_rx: mpsc::Receiver<(String, Vec<u8>)>,
}

pub(crate) struct MasqueUdpFlow {
    pub(crate) sender: H3DatagramSender,
    pub(crate) driver_task: JoinHandle<()>,
    pub(crate) reader_task: JoinHandle<()>,
}

impl MasqueUdpRelay {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        if !self.flows.contains_key(target) {
            let flow = self.open_flow(target).await?;
            self.flows.insert(target.to_string(), flow);
        }

        let flow = self.flows.get_mut(target).expect("flow inserted above");
        flow.send(payload)
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "MASQUE UDP relay closed"))
    }

    async fn open_flow(&self, target: &str) -> io::Result<MasqueUdpFlow> {
        let auth_header = self.client.request_auth_header(target).await?;
        match attempt_h3_connect_udp(&self.client.config, target, auth_header.as_ref(), self.incoming_tx.clone()).await
        {
            Ok(flow) => {
                let reason = if self.client.config.quic_migrate_after_handshake {
                    Some("path_validated_after_http3_udp_connect")
                } else {
                    Some("http3_transport_without_rebind")
                };
                let status =
                    if self.client.config.quic_migrate_after_handshake { "validated" } else { "not_attempted" };
                self.client.record_quic_migration_status(status, reason).await;
                Ok(flow)
            }
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.client.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h3_connect_udp(&self.client.config, target, Some(&retry_header), self.incoming_tx.clone())
                    .await
                {
                    Ok(flow) => {
                        let reason = if self.client.config.quic_migrate_after_handshake {
                            Some("path_validated_after_http3_udp_connect_retry")
                        } else {
                            Some("http3_transport_without_rebind")
                        };
                        let status =
                            if self.client.config.quic_migrate_after_handshake { "validated" } else { "not_attempted" };
                        self.client.record_quic_migration_status(status, reason).await;
                        Ok(flow)
                    }
                    Err(AttemptError::Io(error)) => {
                        let reason = format!("http3_udp_connect_failed_{}", classify_attempt_failure(&error));
                        self.client.record_quic_migration_status("failed", Some(&reason)).await;
                        Err(error)
                    }
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => {
                let reason = format!("http3_udp_connect_failed_{}", classify_attempt_failure(&error));
                self.client.record_quic_migration_status("failed", Some(&reason)).await;
                Err(error)
            }
        }
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }
}

impl MasqueUdpFlow {
    fn send(&mut self, payload: &[u8]) -> io::Result<()> {
        let mut datagram = BytesMut::with_capacity(1 + payload.len());
        datagram.extend_from_slice(&[UDP_CONTEXT_ID]);
        datagram.extend_from_slice(payload);
        self.sender
            .send_datagram(datagram.freeze())
            .map_err(|error| io::Error::other(format!("failed to send MASQUE UDP datagram: {error}")))
    }
}

impl Drop for MasqueUdpFlow {
    fn drop(&mut self) {
        self.driver_task.abort();
        self.reader_task.abort();
    }
}
