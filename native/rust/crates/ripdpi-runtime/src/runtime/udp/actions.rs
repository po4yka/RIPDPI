use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

use crate::platform;
use ripdpi_desync::DesyncAction;

#[derive(Clone, Copy)]
pub(super) struct UdpActionExecContext<'a> {
    pub(super) upstream: &'a UdpSocket,
    pub(super) target: SocketAddr,
    pub(super) default_ttl: u8,
    pub(super) protect_path: Option<&'a str>,
    pub(super) ip_id_mode: Option<ripdpi_config::IpIdMode>,
}

pub(super) fn execute_udp_actions(ctx: UdpActionExecContext<'_>, actions: &[DesyncAction]) -> io::Result<()> {
    for action in actions {
        execute_udp_action(ctx, action)?;
    }
    Ok(())
}

fn execute_udp_action(ctx: UdpActionExecContext<'_>, action: &DesyncAction) -> io::Result<()> {
    match action {
        DesyncAction::Write(bytes) => execute_udp_write_action(ctx, bytes),
        DesyncAction::WriteIpFragmentedUdp { bytes, split_offset, disorder, ipv6_ext } => {
            execute_udp_fragmented_write_action(ctx, bytes, *split_offset, *disorder, *ipv6_ext)
        }
        DesyncAction::SetTtl(ttl) => execute_udp_ttl_action(ctx, *ttl),
        DesyncAction::Delay(ms) => execute_udp_delay_action(*ms),
        DesyncAction::RestoreDefaultTtl
        | DesyncAction::WriteIpFragmentedTcp { .. }
        | DesyncAction::WriteUrgent { .. }
        | DesyncAction::SetMd5Sig { .. }
        | DesyncAction::AttachDropSack
        | DesyncAction::DetachDropSack
        | DesyncAction::AwaitWritable
        | DesyncAction::SetWindowClamp(_)
        | DesyncAction::RestoreWindowClamp
        | DesyncAction::SetWsize { .. }
        | DesyncAction::RestoreWsize
        | DesyncAction::SendFakeRst
        | DesyncAction::WriteSeqOverlap { .. } => Ok(()),
    }
}

fn execute_udp_write_action(ctx: UdpActionExecContext<'_>, bytes: &[u8]) -> io::Result<()> {
    ctx.upstream.send(bytes)?;
    Ok(())
}

fn execute_udp_fragmented_write_action(
    ctx: UdpActionExecContext<'_>,
    bytes: &[u8],
    split_offset: usize,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
) -> io::Result<()> {
    match platform::send_ip_fragmented_udp(
        ctx.upstream,
        ctx.target,
        bytes,
        split_offset,
        ctx.default_ttl,
        ctx.protect_path,
        disorder,
        ipv6_ext,
        ctx.ip_id_mode,
    ) {
        Ok(()) => Ok(()),
        Err(err) if should_fallback_ipfrag_udp_error_kind(err.kind()) => {
            ctx.upstream.send(bytes)?;
            Ok(())
        }
        Err(err) => Err(err),
    }
}

fn should_fallback_ipfrag_udp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput)
}

fn execute_udp_ttl_action(ctx: UdpActionExecContext<'_>, ttl: u8) -> io::Result<()> {
    match ctx.target {
        SocketAddr::V4(_) => ctx.upstream.set_ttl(ttl as u32),
        SocketAddr::V6(_) => Ok(()),
    }
}

fn execute_udp_delay_action(ms: u16) -> io::Result<()> {
    thread::sleep(Duration::from_millis(u64::from(ms)));
    Ok(())
}
