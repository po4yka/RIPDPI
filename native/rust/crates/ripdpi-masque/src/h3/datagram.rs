use std::io;

use bytes::Bytes;

use crate::capsule::decode_connect_udp_payload;

pub(crate) fn decode_udp_payload(payload: Bytes) -> io::Result<Option<Vec<u8>>> {
    decode_connect_udp_payload(&payload)
        .map(|decoded| decoded.map(|(_, payload)| payload))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid MASQUE UDP datagram: {error}")))
}
