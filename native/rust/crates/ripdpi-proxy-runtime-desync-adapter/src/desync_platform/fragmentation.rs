use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{
    TcpFlagOverrides as DesyncTcpFlagOverrides, TcpFragmentSender, TcpPayloadSegment as DesyncTcpPayloadSegment,
};

use crate::platform as runtime_platform;

use super::RuntimeTcpDesyncPlatform;
use super::conversion::to_runtime_flags;

impl TcpFragmentSender for RuntimeTcpDesyncPlatform {
    #[allow(clippy::too_many_arguments)]
    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: crate::ip_fragmentation::Ipv6ExtHeaders,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            flags,
            ip_id_mode,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[DesyncTcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        super::multi_disorder::send_multi_disorder_tcp(
            stream,
            payload,
            segments,
            default_ttl,
            protect_path,
            inter_segment_delay_ms,
            md5sig,
            original_flags,
            ip_id_mode,
        )
    }
}

#[allow(clippy::too_many_arguments)]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: crate::ip_fragmentation::Ipv6ExtHeaders,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        to_runtime_flags(flags),
        ip_id_mode,
    )
    .map_err(project_fragment_error)
}

pub(super) fn project_fragment_error(error: io::Error) -> io::Error {
    let kind = error.kind();
    let Some(phase) =
        error.get_ref().and_then(|source| source.downcast_ref::<runtime_platform::raw_packet::IpFragmentSendError>())
    else {
        return io::Error::new(
            kind,
            ripdpi_desync_runtime::platform::TcpFragmentSendError { source: error, payload_commit_uncertain: true },
        );
    };
    let source = phase
        .raw_os_error()
        .map_or_else(|| io::Error::new(phase.source.kind(), phase.source.to_string()), io::Error::from_raw_os_error);
    io::Error::new(
        kind,
        ripdpi_desync_runtime::platform::TcpFragmentSendError {
            source,
            payload_commit_uncertain: phase.payload_commit_uncertain,
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{Shutdown, TcpListener};

    #[test]
    fn fragment_error_projection_preserves_normalized_kind_errno_and_phase() {
        let source = io::Error::new(io::ErrorKind::Unsupported, io::Error::from_raw_os_error(libc::EPERM));
        let error = io::Error::new(
            io::ErrorKind::Unsupported,
            runtime_platform::raw_packet::IpFragmentSendError { source, payload_commit_uncertain: false },
        );
        let projected = project_fragment_error(error);
        assert_eq!(projected.kind(), io::ErrorKind::Unsupported);
        let phase = projected
            .get_ref()
            .and_then(|source| source.downcast_ref::<ripdpi_desync_runtime::platform::TcpFragmentSendError>())
            .expect("projected phase");
        assert!(!phase.payload_commit_uncertain);
        assert_eq!(phase.source.raw_os_error(), Some(libc::EPERM));
    }

    #[test]
    fn opaque_fragment_failure_after_bytes_remains_uncertain() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
        let mut writer = TcpStream::connect(listener.local_addr().expect("address")).expect("writer");
        let (mut reader, _) = listener.accept().expect("reader");
        writer.write_all(b"sent").expect("payload accepted");
        writer.shutdown(Shutdown::Write).expect("finish write");
        let error = project_fragment_error(io::Error::new(io::ErrorKind::WouldBlock, "opaque failure"));
        let mut received = Vec::new();
        reader.read_to_end(&mut received).expect("received bytes");
        assert_eq!(received, b"sent");
        let phase = error
            .get_ref()
            .and_then(|source| source.downcast_ref::<ripdpi_desync_runtime::platform::TcpFragmentSendError>())
            .expect("conservative phase");
        assert!(phase.payload_commit_uncertain);
    }
    #[test]
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn real_non_linux_fragment_adapter_preserves_plain_fallback() {
        let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
        let mut writer = TcpStream::connect(listener.local_addr().expect("address")).expect("writer");
        let (mut reader, _) = listener.accept().expect("reader");
        reader.set_read_timeout(Some(std::time::Duration::from_secs(2))).expect("read deadline");
        let mut group = ripdpi_config::DesyncGroup::new(0);
        group.actions.tcp_chain = vec![ripdpi_config::TcpChainStep::new(
            ripdpi_config::TcpChainStepKind::IpFrag2,
            ripdpi_config::OffsetExpr::absolute(12),
        )];
        let progress = ripdpi_session::OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len() - 1,
        };
        let context = ripdpi_desync_runtime::activation_context_from_progress(
            progress,
            ripdpi_desync::ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            None,
            ripdpi_desync::AdaptivePlannerHints::default(),
        );
        let outcome = super::super::send_prepared_with_runtime_platform(
            &mut writer,
            &ripdpi_config::RuntimeConfig::default(),
            &group,
            payload,
            progress,
            context,
            None,
            None,
            &crate::sync::AtomicBool::new(false),
            None,
        )
        .expect("non-Linux plain fallback");
        writer.shutdown(Shutdown::Write).expect("finish write");
        let mut received = Vec::new();
        reader.read_to_end(&mut received).expect("full original payload");
        assert_eq!(received, payload);
        assert_eq!(outcome.execution_receipt.effective_family, Some(ripdpi_desync_runtime::TcpStrategyFamily::Split));
        assert_eq!(outcome.bytes_committed, payload.len());
    }
}
