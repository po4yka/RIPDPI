use std::io;

use ripdpi_config::TcpChainStep;
use ripdpi_desync::{HostFakeSpan, build_hostfake_region_bytes};

use crate::types::OutboundSendError;

pub(super) struct HostFakePayload {
    fake_host: Vec<u8>,
}

impl HostFakePayload {
    pub(super) fn new(
        configured_step: &TcpChainStep,
        buffer: &[u8],
        span: HostFakeSpan,
        seed: u32,
    ) -> Result<Self, OutboundSendError> {
        let fake_host = build_hostfake_region_bytes(
            buffer,
            span,
            configured_step.fake_host_template(),
            seed,
            configured_step.random_fake_host(),
        )
        .ok_or_else(|| {
            OutboundSendError::transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "hostfake hostname span is unavailable",
            ))
        })?;
        Ok(Self { fake_host })
    }

    pub(super) fn fake_host(&self) -> &[u8] {
        &self.fake_host
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{OffsetBase, OffsetExpr, TcpChainStepKind};
    use ripdpi_desync::resolve_hostfake_span;

    mod oracle {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-desync/tests/support/hostfake_tls.rs"));
    }

    #[test]
    fn hostfake_payload_preserves_record_headers_inside_fragmented_sni() {
        let step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0))
            .with_fake_host_template(Some("cover.example.net".to_string()));
        for tls13 in [false, true] {
            for cuts in [&[][..], &[59][..], &[59, 65][..]] {
                let input = oracle::client_hello(tls13, cuts);
                let span = resolve_hostfake_span(&step, &input, 0, input.len(), 23).expect("SNI span");
                let payload = HostFakePayload::new(&step, &input, span, 23).expect("hostfake payload");
                let mut fake_view = input.clone();
                fake_view[span.host_start..span.host_end].copy_from_slice(payload.fake_host());
                oracle::assert_fake_tls(&input, &fake_view, b"cover.example.net");
            }
        }
    }
}
