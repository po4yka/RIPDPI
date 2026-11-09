use crate::types::TlsMarkerInfo;

/// SNI and byte positions in the original stream for a fragmented ClientHello.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReassembledTlsSni {
    pub host: Vec<u8>,
    /// Original stream offset for each byte of `host`.
    pub host_offsets: Vec<usize>,
    pub ext_len_start: usize,
    pub sni_ext_start: usize,
    /// Present only when the host is contiguous in the original stream.
    pub contiguous_markers: Option<TlsMarkerInfo>,
}

/// Extract an SNI spanning complete handshake records without changing the stream.
pub fn reassembled_tls_sni(buffer: &[u8]) -> Option<ReassembledTlsSni> {
    let first_len = crate::util::read_u16(buffer, 3)?;
    if first_len >= 4 && crate::util::read_u24(buffer, 6).is_some_and(|size| size + 4 <= first_len) {
        return None;
    }
    let mut flat = Vec::new();
    let mut spans = Vec::new();
    let mut cursor = 0usize;
    let mut total = None;
    while cursor < buffer.len() {
        let header = buffer.get(cursor..cursor + 5)?;
        if header[0] != 0x16 || header[1] != 0x03 || header[2] > 4 {
            return None;
        }
        let size = usize::from(u16::from_be_bytes([header[3], header[4]]));
        if size == 0 {
            return None;
        }
        let start = cursor + 5;
        let end = start.checked_add(size)?;
        let bytes = buffer.get(start..end)?;
        let flat_start = flat.len();
        flat.extend_from_slice(bytes);
        spans.push((flat_start, start, end));
        cursor = end;
        if total.is_none() && flat.len() >= 4 {
            if flat[0] != 1 {
                return None;
            }
            total = Some(4 + ((flat[1] as usize) << 16) + ((flat[2] as usize) << 8) + flat[3] as usize);
        }
        if total.is_some_and(|len| flat.len() >= len) {
            break;
        }
    }
    if spans.len() < 2 || flat.len() < total? {
        return None;
    }
    let markers = super::detect::tls_client_hello_marker_info_in_handshake(&flat)?;
    let map = |offset: usize| {
        let index = spans.partition_point(|&(flat_start, _, _)| flat_start <= offset).checked_sub(1)?;
        let (flat_start, start, end) = spans[index];
        (offset < flat_start + end - start).then(|| start + offset - flat_start)
    };
    let host = flat.get(markers.host_start..markers.host_end)?.to_vec();
    let host_offsets: Vec<_> = (markers.host_start..markers.host_end).map(map).collect::<Option<_>>()?;
    let first = *host_offsets.first()?;
    let last = *host_offsets.last()?;
    let ext_len_start = map(markers.ext_len_start)?;
    let sni_ext_start = map(markers.sni_ext_start)?;
    let contiguous_markers = host_offsets.windows(2).all(|pair| pair[1] == pair[0] + 1).then(|| TlsMarkerInfo {
        ext_len_start,
        ech_ext_start: markers.ech_ext_start.and_then(map),
        sni_ext_start,
        host_start: first,
        host_end: last + 1,
    });
    Some(ReassembledTlsSni { host, host_offsets, ext_len_start, sni_ext_start, contiguous_markers })
}
