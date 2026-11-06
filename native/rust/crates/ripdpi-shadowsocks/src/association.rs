use std::{
    io,
    time::{Duration, Instant},
};

use shadowsocks::{packet_window::PacketWindowFilter, relay::udprelay::options::UdpSocketControlData};

/// Bounded client association policy around authenticated upstream UDP decoding.
pub struct UdpAssociation {
    client_id: u64,
    next_packet_id: u64,
    current: Option<Peer>,
    previous: Option<Peer>,
}

struct Peer {
    id: u64,
    window: PacketWindowFilter,
    last_seen: Instant,
}

impl Default for UdpAssociation {
    fn default() -> Self {
        Self { client_id: rand::random(), next_packet_id: 0, current: None, previous: None }
    }
}

impl UdpAssociation {
    /// Reserves an ID before send so cancellation cannot reuse it.
    ///
    /// # Errors
    /// Returns an error when the session has exhausted its packet IDs.
    pub fn next_control(&mut self) -> io::Result<UdpSocketControlData> {
        let packet_id = self.next_packet_id;
        self.next_packet_id =
            packet_id.checked_add(1).ok_or_else(|| io::Error::other("Shadowsocks UDP packet ID exhausted"))?;
        let mut control = UdpSocketControlData::default();
        control.client_session_id = self.client_id;
        control.packet_id = packet_id;
        Ok(control)
    }

    /// Returns the timestamp only for a fresh packet bound to this client.
    /// Call only with authenticated metadata from upstream decoding.
    pub fn accept(&mut self, control: &UdpSocketControlData, now: Instant) -> Option<u64> {
        let timestamp = control.authenticated_timestamp?;
        if control.client_session_id != self.client_id {
            return None;
        }
        for peer in [&mut self.current, &mut self.previous].into_iter().flatten() {
            if peer.id == control.server_session_id {
                if !peer.window.validate_packet_id(control.packet_id, u64::MAX) {
                    return None;
                }
                peer.last_seen = now;
                return Some(timestamp);
            }
        }
        // Whole-second timestamps extend the inclusive +/-30s horizon by up to one second.
        if self.previous.as_ref().is_some_and(|peer| now.duration_since(peer.last_seen) <= Duration::from_secs(61)) {
            return None;
        }
        let mut window = PacketWindowFilter::new();
        if !window.validate_packet_id(control.packet_id, u64::MAX) {
            return None;
        }
        self.previous = self.current.take();
        self.current = Some(Peer { id: control.server_session_id, window, last_seen: now });
        Some(timestamp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replay_identity_and_retirement_are_checked_before_state_changes() {
        let mut association = UdpAssociation::default();
        let mut control = association.next_control().unwrap();
        control.authenticated_timestamp = Some(1_700_000_000);
        control.server_session_id = 1;
        let start = Instant::now();
        assert!(association.accept(&control, start).is_some());
        assert!(association.accept(&control, start).is_none());
        control.server_session_id = 2;
        assert!(association.accept(&control, start).is_some());
        control.packet_id = 1;
        assert!(association.accept(&control, start + Duration::from_secs(50)).is_some());
        control.packet_id = 0;
        control.server_session_id = 3;
        assert!(association.accept(&control, start + Duration::from_secs(61)).is_none());
        let expired = start + Duration::from_secs(61) + Duration::from_nanos(1);
        control.client_session_id ^= 1;
        assert!(association.accept(&control, expired).is_none());
        control.client_session_id ^= 1;
        control.server_session_id = 1;
        assert!(association.accept(&control, expired).is_none());
        control.server_session_id = 3;
        assert!(association.accept(&control, expired).is_some());
        control.server_session_id = 2;
        assert!(association.accept(&control, expired).is_none());
        control.server_session_id = 4;
        assert!(association.accept(&control, expired).is_none());
    }

    #[test]
    fn send_counter_never_wraps_and_missing_timestamp_is_rejected() {
        let mut association = UdpAssociation::default();
        let first = association.next_control().unwrap();
        assert_eq!(first.packet_id, 0);
        assert_eq!(association.next_control().unwrap().packet_id, 1);
        assert!(association.accept(&first, Instant::now()).is_none());
        association.next_packet_id = u64::MAX;
        assert!(association.next_control().is_err());
        assert!(association.next_control().is_err());
    }
}
