use super::*;

impl AnyTlsUdpOverTcp {
    /// # Cancel safety
    /// Not cancel-safe: earlier PSH chunks may already be queued. After a
    /// cancelled send the caller must discard this UDP-over-TCP association.
    pub async fn send_datagram(&mut self, target: TargetAddr, port: u16, payload: &[u8]) -> Result<(), AnyTlsError> {
        let mut packet = encode_target(&target, port)?;
        // UoT's datagram serializer has a different address-family registry
        // from the SOCKS serializer used by the initial request.
        packet[0] = match target {
            TargetAddr::Ipv4(_) => 0,
            TargetAddr::Ipv6(_) => 1,
            TargetAddr::Domain(_) => 2,
        };
        let len = u16::try_from(payload.len()).map_err(|_| AnyTlsError::DatagramTooLong(payload.len()))?;
        packet.extend_from_slice(&len.to_be_bytes());
        packet.extend_from_slice(payload);
        self.stream.write_all(&packet).await
    }

    /// # Cancel safety
    /// Header and payload bytes remain in `pending` until a complete packet is
    /// returned. The inner stream also retains partial exact reads on cancel.
    pub async fn recv_datagram(&mut self) -> Result<AnyTlsDatagram, AnyTlsError> {
        loop {
            match parse_datagram(&self.pending)? {
                Parsed::Complete(packet) => {
                    self.pending.clear();
                    return Ok(packet);
                }
                Parsed::Need(required) => {
                    debug_assert!(required <= MAX_DATAGRAM_BYTES);
                    let bytes = self.stream.read_exact_len(required - self.pending.len()).await?;
                    self.pending.extend_from_slice(&bytes);
                }
            }
        }
    }
}

// domain: ATYP + length + 255 bytes + port + payload length + u16 payload.
const MAX_DATAGRAM_BYTES: usize = 261 + u16::MAX as usize;
enum Parsed {
    Need(usize),
    Complete(AnyTlsDatagram),
}

fn parse_datagram(bytes: &[u8]) -> Result<Parsed, AnyTlsError> {
    let Some(&family) = bytes.first() else {
        return Ok(Parsed::Need(1));
    };
    let address_end = match family {
        0 => 5,
        1 => 17,
        2 => {
            let Some(&len) = bytes.get(1) else {
                return Ok(Parsed::Need(2));
            };
            2 + usize::from(len)
        }
        _ => return Err(AnyTlsError::InvalidDatagram),
    };
    let header_end = address_end + 4;
    if bytes.len() < header_end {
        return Ok(Parsed::Need(header_end));
    }
    let target = match family {
        0 => TargetAddr::Ipv4(Ipv4Addr::new(bytes[1], bytes[2], bytes[3], bytes[4])),
        1 => {
            let mut octets = [0; 16];
            octets.copy_from_slice(&bytes[1..17]);
            TargetAddr::Ipv6(Ipv6Addr::from(octets))
        }
        2 => TargetAddr::Domain(
            std::str::from_utf8(&bytes[2..address_end]).map_err(|_| AnyTlsError::InvalidDatagram)?.to_owned(),
        ),
        _ => return Err(AnyTlsError::InvalidDatagram),
    };
    let port = u16::from_be_bytes([bytes[address_end], bytes[address_end + 1]]);
    let payload_len = usize::from(u16::from_be_bytes([bytes[address_end + 2], bytes[address_end + 3]]));
    let end = header_end + payload_len;
    if bytes.len() < end {
        return Ok(Parsed::Need(end));
    }
    if bytes.len() != end {
        return Err(AnyTlsError::InvalidDatagram);
    }
    Ok(Parsed::Complete(AnyTlsDatagram { target, port, payload: bytes[header_end..end].to_vec() }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn largest_domain_packet_is_bounded_and_invalid_headers_fail() {
        let mut packet = vec![2, 255];
        packet.extend_from_slice(&[b'a'; 255]);
        packet.extend_from_slice(&[1, 187, 255, 255]);
        assert!(matches!(parse_datagram(&packet), Ok(Parsed::Need(MAX_DATAGRAM_BYTES))));
        packet.resize(MAX_DATAGRAM_BYTES, 7);
        let Ok(Parsed::Complete(parsed)) = parse_datagram(&packet) else {
            panic!("complete bounded packet");
        };
        assert_eq!(parsed.payload.len(), usize::from(u16::MAX));
        assert!(matches!(parse_datagram(&[3]), Err(AnyTlsError::InvalidDatagram)));
        assert!(matches!(parse_datagram(&[2, 1, 255, 0, 1, 0, 0]), Err(AnyTlsError::InvalidDatagram)));
    }
}
