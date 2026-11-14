use super::{long_header, validate_retry_tag, validate_server_initial_with_header};
use crate::quic::datagram::decode_quic_initial_datagram;
use crate::quic::frames::read_quic_varint;
use crate::quic::parse::{
    decrypt_quic_initial_payload_with_offset, parse_quic_initial_header, parse_quic_initial_response_header_for_runtime,
};
use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicInitialResponseDisposition {
    Accepted,
    Rejected,
    /// CID and packet integrity match, but the complete client offer is unavailable.
    UnverifiableClientOffer,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ClientOffers {
    Unavailable,
    Known { compatible: bool, grease: bool },
}

/// Bounded response evidence for one client Initial, without retaining its packet bytes.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QuicInitialResponseContext {
    version: u32,
    dcid: [u8; 20],
    scid: [u8; 20],
    dcid_len: u8,
    scid_len: u8,
    offers: ClientOffers,
}

impl QuicInitialResponseContext {
    /// Whether retransmissions or compatible-version Initials use the same CID pair.
    pub fn matches_connection(&self, other: &Self) -> bool {
        self.dcid() == other.dcid() && self.scid() == other.scid()
    }

    fn dcid(&self) -> &[u8] {
        &self.dcid[..usize::from(self.dcid_len)]
    }
    fn scid(&self) -> &[u8] {
        &self.scid[..usize::from(self.scid_len)]
    }
}

/// Parse integrity-valid client evidence; incomplete ClientHello and absent SNI are allowed.
pub fn parse_quic_initial_response_context(request: &[u8]) -> Option<QuicInitialResponseContext> {
    if request.len() > usize::from(u16::MAX) {
        return None;
    }
    let sent = parse_quic_initial_header(request)?;
    decrypt_quic_initial_payload_with_offset(request, sent)?;
    let mut dcid = [0; 20];
    dcid[..sent.dcid.len()].copy_from_slice(sent.dcid);
    let mut scid = [0; 20];
    scid[..sent.scid.len()].copy_from_slice(sent.scid);
    Some(QuicInitialResponseContext {
        version: sent.version,
        dcid,
        scid,
        dcid_len: sent.dcid.len() as u8,
        scid_len: sent.scid.len() as u8,
        offers: client_offers(request, sent.version),
    })
}

/// Check first-response accounting without changing strict single-version probes.
/// This checks packet integrity and client version/Fixed Bit offers, not server authentication.
#[cfg(test)]
fn validate_quic_initial_response_for_runtime(request: &[u8], response: &[u8]) -> QuicInitialResponseDisposition {
    let Some(context) = parse_quic_initial_response_context(request) else {
        return QuicInitialResponseDisposition::Rejected;
    };
    classify_quic_initial_response_context(&context, response)
}

/// Check a reply against compact client evidence; Initial and Retry keys are public.
pub fn classify_quic_initial_response_context(
    context: &QuicInitialResponseContext,
    response: &[u8],
) -> QuicInitialResponseDisposition {
    use QuicInitialResponseDisposition::{Accepted, Rejected, UnverifiableClientOffer};
    let Some((version, dcid, scid, offset)) = long_header(response) else {
        return Rejected;
    };
    if dcid != context.scid() || !matches!(version, QUIC_V1_VERSION | QUIC_V2_VERSION) {
        return Rejected;
    }
    let clear_fixed_bit = response[0] & 0x40 == 0;
    let retry_type = if version == QUIC_V1_VERSION { 0x30 } else { 0x00 };
    if response[0] & 0x30 == retry_type {
        if version != context.version
            || scid == context.dcid()
            || response.len().saturating_sub(offset) <= 16
            || validate_retry_tag(version, context.dcid(), response).is_none()
        {
            return Rejected;
        }
    } else {
        let Some(received) = parse_quic_initial_response_header_for_runtime(response) else {
            return Rejected;
        };
        if validate_server_initial_with_header(context.dcid(), response, received).is_none() {
            return Rejected;
        }
    }
    if version == context.version && !clear_fixed_bit {
        return Accepted;
    }
    // RFC 9368/9369 permit compatible Initial versions; RFC 9287 permits a cleared Fixed Bit.
    // Retry retains the original version. Integrity uses the raw header, including its Fixed Bit.
    match context.offers {
        ClientOffers::Unavailable => UnverifiableClientOffer,
        ClientOffers::Known { compatible, grease } => {
            if (version == context.version || compatible) && (!clear_fixed_bit || grease) { Accepted } else { Rejected }
        }
    }
}

fn client_offers(request: &[u8], original: u32) -> ClientOffers {
    let Some(decoded) = decode_quic_initial_datagram(request) else {
        return ClientOffers::Unavailable;
    };
    let denied = ClientOffers::Known { compatible: false, grease: false };
    let Some(parsed) = crate::tls_nom::parse_client_hello_handshake(&decoded.client_hello) else {
        return denied;
    };
    let mut parameters = parsed.extensions.iter().filter(|extension| extension.ext_type == 0x0039);
    let Some(parameters_value) = parameters.next() else {
        return denied;
    };
    if parameters.next().is_some() {
        return denied;
    }
    let other = if original == QUIC_V1_VERSION { QUIC_V2_VERSION } else { QUIC_V1_VERSION };
    ClientOffers::Known {
        compatible: version_information_offers(parameters_value.data, original, other).unwrap_or(false),
        grease: matches!(transport_parameter(parameters_value.data, 0x2ab2), Some(Some(value)) if value.is_empty()),
    }
}

fn version_information_offers(parameters: &[u8], original: u32, negotiated: u32) -> Option<bool> {
    let Some(value) = transport_parameter(parameters, 0x11)? else {
        return Some(false);
    };
    if value.len() < 4 || !value.len().is_multiple_of(4) {
        return None;
    }
    let (chosen, available) = value.split_at(4);
    if chosen != original.to_be_bytes()
        || available.as_chunks::<4>().0.contains(&[0; 4])
        || !available.as_chunks::<4>().0.contains(&original.to_be_bytes())
    {
        return Some(false);
    }
    Some(available.as_chunks::<4>().0.contains(&negotiated.to_be_bytes()))
}

fn transport_parameter(parameters: &[u8], wanted: u64) -> Option<Option<&[u8]>> {
    let mut cursor = 0usize;
    let mut found = None;
    while cursor < parameters.len() {
        let (kind, width) = read_quic_varint(parameters, cursor)?;
        cursor = cursor.checked_add(width)?;
        let (length, width) = read_quic_varint(parameters, cursor)?;
        cursor = cursor.checked_add(width)?;
        let end = cursor.checked_add(usize::try_from(length).ok()?)?;
        let value = parameters.get(cursor..end)?;
        cursor = end;
        if kind == wanted {
            if found.is_some() {
                return None;
            }
            found = Some(value);
        }
    }
    Some(found)
}

#[cfg(test)]
#[path = "compatible_tests.rs"]
mod tests;
