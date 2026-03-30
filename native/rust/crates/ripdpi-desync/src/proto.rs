use crate::types::ProtoInfo;
use ripdpi_config::OffsetProto;
use ripdpi_packets::{http_marker_info, tls_marker_info, IS_HTTP, IS_HTTPS};

pub(crate) fn init_proto_info(buffer: &[u8], info: &mut ProtoInfo) {
    if info.http.is_some() || info.tls.is_some() {
        return;
    }
    if let Some(tls) = tls_marker_info(buffer) {
        info.kind = IS_HTTPS;
        info.tls = Some(tls);
    } else if let Some(http) = http_marker_info(buffer) {
        if info.kind == 0 {
            info.kind = IS_HTTP;
        }
        info.http = Some(http);
    }
}

pub(crate) fn resolve_host_range<'a>(
    buffer: &'a [u8],
    info: &mut ProtoInfo,
    proto: OffsetProto,
) -> Option<(usize, usize, &'a [u8])> {
    init_proto_info(buffer, info);
    match proto {
        OffsetProto::TlsOnly => {
            let tls = info.tls?;
            Some((tls.host_start, tls.host_end, &buffer[tls.host_start..tls.host_end]))
        }
        OffsetProto::Any => {
            if let Some(tls) = info.tls {
                Some((tls.host_start, tls.host_end, &buffer[tls.host_start..tls.host_end]))
            } else {
                let http = info.http?;
                Some((http.host_start, http.host_end, &buffer[http.host_start..http.host_end]))
            }
        }
    }
}
