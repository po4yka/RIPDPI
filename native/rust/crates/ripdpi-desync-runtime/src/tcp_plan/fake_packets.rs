use std::io;

use ripdpi_config::DesyncGroup;
use ripdpi_desync::{build_fake_packet, build_secondary_fake_packet};

#[derive(Debug)]
pub(crate) struct BuiltFakePackets {
    pub(crate) primary: ripdpi_desync::FakePacketPlan,
    pub(crate) secondary: Option<ripdpi_desync::FakePacketPlan>,
}

pub(crate) fn build_tcp_fake_packets(
    group: &DesyncGroup,
    tampered: &[u8],
    seed: u32,
) -> io::Result<Option<BuiltFakePackets>> {
    let primary = build_fake_packet(group, tampered, seed)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync"))?;
    let secondary = build_secondary_fake_packet(group, tampered, seed.wrapping_add(1)).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidData, "failed to build secondary fake packet for tcp desync")
    })?;
    Ok(Some(BuiltFakePackets { primary, secondary }))
}
