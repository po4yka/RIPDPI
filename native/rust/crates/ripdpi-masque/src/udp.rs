use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::{Buf, Bytes};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::capsule::encode_connect_udp_payload;
use crate::client::MasqueClientInner;
use crate::h2::attempt_h2_connect_udp;
use crate::h3::attempt_h3_connect_udp;
use crate::response::{AttemptError, classify_attempt_failure};

type H3RequestSender = h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>;

const MAX_UDP_FLOWS: usize = 64;
const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(120);
const H3_DATAGRAM_TOO_LARGE: &str = "masque_h3_datagram_too_large: payload exceeds the current QUIC path limit";

fn h3_datagram_send_error(error: quinn::SendDatagramError) -> io::Error {
    match error {
        quinn::SendDatagramError::TooLarge => io::Error::new(io::ErrorKind::InvalidInput, H3_DATAGRAM_TOO_LARGE),
        other => io::Error::other(format!("failed to send MASQUE UDP datagram: {other}")),
    }
}

pub(crate) enum MasqueUdpSender {
    H3 { connection: quinn::Connection, stream_id: h3::quic::StreamId },
    H2(mpsc::Sender<Vec<u8>>),
}

pub struct MasqueUdpRelay {
    pub(crate) client: Arc<MasqueClientInner>,
    pub(crate) flows: HashMap<String, MasqueUdpFlow>,
    pub(crate) incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
    pub(crate) incoming_rx: mpsc::Receiver<(String, Vec<u8>)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MasqueQuicPathSnapshot {
    pub current_mtu: u16,
    pub max_datagram_size: Option<usize>,
    pub sent_datagram_frames: u64,
    pub received_datagram_frames: u64,
    pub lost_packets: u64,
    pub sent_plpmtud_probes: u64,
    pub lost_plpmtud_probes: u64,
    pub black_holes_detected: u64,
}

/// Drop order: the `Drop::drop` body aborts both `driver_task` and
/// `reader_task` (tokio `.abort()` is non-blocking and merely flips
/// the task's cancellation flag) before any field drops. After the
/// body returns, the fields drop in declaration order: `sender`, the retained
/// H3 request sender that keeps the connection open, then the two `JoinHandle`s. Dropping
/// a `JoinHandle` without `await` is a detach -- the tokio runtime
/// reclaims the task on its next scheduler tick. Because both
/// aborts fire BEFORE `sender` drops, the tasks observe
/// cancellation rather than a `BrokenPipe` on their next `.await`.
pub(crate) struct MasqueUdpFlow {
    pub(crate) sender: MasqueUdpSender,
    _request_sender: Option<H3RequestSender>,
    pub(crate) driver_task: JoinHandle<()>,
    pub(crate) reader_task: JoinHandle<()>,
    path_connection: Option<quinn::Connection>,
    datagram_stream_id_len: usize,
    last_used: Instant,
}

impl MasqueUdpRelay {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        if !self.flows.contains_key(target) {
            make_room_for_flow(&mut self.flows, Instant::now(), MAX_UDP_FLOWS);
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
            Err(AttemptError::Io(error)) if self.client.config.use_http2_fallback => {
                let fallback_reason = format!("http3_udp_connect_failed_{}", classify_attempt_failure(&error));
                tracing::info!(target, error = %error, "MASQUE H3 UDP connect failed, falling back to HTTP/2");
                match attempt_h2_connect_udp(
                    &self.client.config,
                    target,
                    auth_header.as_ref(),
                    self.incoming_tx.clone(),
                )
                .await
                {
                    Ok(flow) => {
                        self.client.record_quic_migration_status("http2_fallback", Some(&fallback_reason)).await;
                        Ok(flow)
                    }
                    Err(AttemptError::Io(fallback_error)) => {
                        self.client
                            .record_quic_migration_status(
                                "failed",
                                Some("http3_udp_connect_failed_and_http2_fallback_failed"),
                            )
                            .await;
                        Err(fallback_error)
                    }
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials during HTTP/2 UDP fallback",
                    )),
                }
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

    pub fn quic_path_snapshot(&self, target: &str) -> Option<MasqueQuicPathSnapshot> {
        let connection = self.flows.get(target)?.path_connection.as_ref()?;
        let stats = connection.stats();
        let path = stats.path;
        Some(MasqueQuicPathSnapshot {
            current_mtu: path.current_mtu,
            max_datagram_size: connection.max_datagram_size(),
            sent_datagram_frames: stats.frame_tx.datagram,
            received_datagram_frames: stats.frame_rx.datagram,
            lost_packets: path.lost_packets,
            sent_plpmtud_probes: path.sent_plpmtud_probes,
            lost_plpmtud_probes: path.lost_plpmtud_probes,
            black_holes_detected: path.black_holes_detected,
        })
    }
}

fn make_room_for_flow(flows: &mut HashMap<String, MasqueUdpFlow>, now: Instant, max_flows: usize) {
    flows.retain(|_, flow| now.saturating_duration_since(flow.last_used) < UDP_FLOW_IDLE_TIMEOUT);
    while flows.len() >= max_flows {
        let Some(oldest) = flows.iter().min_by_key(|(_, flow)| flow.last_used).map(|(target, _)| target.clone()) else {
            break;
        };
        flows.remove(&oldest);
    }
}

impl MasqueUdpFlow {
    pub(crate) fn new(
        sender: MasqueUdpSender,
        request_sender: Option<H3RequestSender>,
        driver_task: JoinHandle<()>,
        reader_task: JoinHandle<()>,
        path_connection: Option<quinn::Connection>,
        datagram_stream_id_len: usize,
    ) -> Self {
        Self {
            sender,
            _request_sender: request_sender,
            driver_task,
            reader_task,
            path_connection,
            datagram_stream_id_len,
            last_used: Instant::now(),
        }
    }

    fn send(&mut self, payload: &[u8]) -> io::Result<()> {
        let result =
            match &mut self.sender {
                MasqueUdpSender::H3 { connection, stream_id } => {
                    let datagram = encode_connect_udp_payload(0, payload)
                        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
                    if self.path_connection.as_ref().and_then(quinn::Connection::max_datagram_size).is_some_and(
                        |max_datagram_size| datagram.len() + self.datagram_stream_id_len > max_datagram_size,
                    ) {
                        return Err(io::Error::new(io::ErrorKind::InvalidInput, H3_DATAGRAM_TOO_LARGE));
                    }
                    let mut encoded = h3_datagram::datagram::Datagram::new(*stream_id, Bytes::from(datagram)).encode();
                    let encoded_len = encoded.remaining();
                    connection.send_datagram(encoded.copy_to_bytes(encoded_len)).map_err(h3_datagram_send_error)
                }
                MasqueUdpSender::H2(sender) => sender.try_send(payload.to_vec()).map_err(|error| {
                    io::Error::new(io::ErrorKind::BrokenPipe, format!("failed to queue MASQUE H2 UDP payload: {error}"))
                }),
            };
        if result.is_ok() {
            self.last_used = Instant::now();
        }
        result
    }
}

impl Drop for MasqueUdpFlow {
    fn drop(&mut self) {
        self.driver_task.abort();
        self.reader_task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_too_large_race_maps_to_stable_invalid_input_contract() {
        let error = h3_datagram_send_error(quinn::SendDatagramError::TooLarge);

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("masque_h3_datagram_too_large"));
    }

    fn test_flow(last_used: Instant) -> MasqueUdpFlow {
        let (sender, _receiver) = mpsc::channel(1);
        let driver_task = tokio::spawn(std::future::pending());
        let reader_task = tokio::spawn(std::future::pending());
        let mut flow = MasqueUdpFlow::new(MasqueUdpSender::H2(sender), None, driver_task, reader_task, None, 0);
        flow.last_used = last_used;
        flow
    }

    #[tokio::test]
    async fn flow_limit_evicts_idle_then_least_recently_used_targets() {
        let mut flows = HashMap::new();
        let now = Instant::now();
        flows.insert("idle".to_string(), test_flow(now - UDP_FLOW_IDLE_TIMEOUT));
        flows.insert("old".to_string(), test_flow(now - Duration::from_secs(2)));
        flows.insert("recent".to_string(), test_flow(now - Duration::from_secs(1)));

        make_room_for_flow(&mut flows, now, 2);

        assert_eq!(flows.len(), 1);
        assert!(flows.contains_key("recent"));
    }

    #[tokio::test]
    async fn flow_limit_never_retains_more_than_the_configured_capacity() {
        let mut flows = HashMap::new();
        let now = Instant::now();
        for index in 0..=MAX_UDP_FLOWS {
            flows.insert(index.to_string(), test_flow(now));
        }

        make_room_for_flow(&mut flows, now, MAX_UDP_FLOWS);

        assert!(flows.len() < MAX_UDP_FLOWS);
    }
}
