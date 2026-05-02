use std::io;

use bytes::Bytes;

use crate::udp::UDP_CONTEXT_ID;

pub(crate) fn decode_udp_payload(payload: Bytes) -> io::Result<Vec<u8>> {
    let Some((&context_id, payload)) = payload.split_first() else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "MASQUE UDP datagram is missing the required context identifier",
        ));
    };
    if context_id != UDP_CONTEXT_ID {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported MASQUE UDP context identifier {context_id}"),
        ));
    }
    Ok(payload.to_vec())
}
