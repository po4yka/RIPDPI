use ripdpi_config::DesyncGroup;

use crate::types::DesyncAction;

pub(super) fn append_ttl_wrapped_packets(
    actions: &mut Vec<DesyncAction>,
    group: &DesyncGroup,
    default_ttl: u8,
    prelude_packets: Vec<Vec<u8>>,
) {
    if prelude_packets.is_empty() {
        return;
    }

    actions.push(DesyncAction::SetTtl(group.actions.ttl.unwrap_or(8)));
    for packet in prelude_packets {
        actions.push(DesyncAction::Write(packet));
    }
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}
