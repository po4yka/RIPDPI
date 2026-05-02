use ripdpi_config::TcpChainStep;
use ripdpi_desync::build_hostfake_bytes;

pub(super) struct HostFakePayload {
    fake_host: Vec<u8>,
}

impl HostFakePayload {
    pub(super) fn new(configured_step: &TcpChainStep, real_host: &[u8], seed: u32) -> Self {
        let fake_host = build_hostfake_bytes(
            real_host,
            configured_step.fake_host_template.as_deref(),
            seed,
            configured_step.random_fake_host,
        );
        Self { fake_host }
    }

    pub(super) fn fake_host(&self) -> &[u8] {
        &self.fake_host
    }
}
