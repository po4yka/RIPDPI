use crate::types::DesyncAction;
use ripdpi_config::DesyncGroup;

/// Effective TTL for disorder-family steps: use the configured fake_ttl,
/// falling back to 1 when fake_ttl is zero (unconfigured).
pub(super) fn disorder_ttl(fake_ttl: u8) -> u8 {
    if fake_ttl == 0 { 1 } else { fake_ttl }
}

pub(super) fn push_split_actions(actions: &mut Vec<DesyncAction>, bytes: Vec<u8>) {
    actions.push(DesyncAction::Write(bytes));
    actions.push(DesyncAction::AwaitWritable);
}

pub(super) fn push_fake_actions(
    actions: &mut Vec<DesyncAction>,
    original: &[u8],
    fake: Vec<u8>,
    group: &DesyncGroup,
    default_ttl: u8,
    fake_ttl: u8,
) {
    if original.is_empty() {
        return;
    }
    actions.push(DesyncAction::SetTtl(fake_ttl));
    if group.actions.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 5 });
    }
    actions.push(DesyncAction::Write(fake));
    actions.push(DesyncAction::AwaitWritable);
    if group.actions.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 0 });
    }
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}

pub(super) fn push_disorder_actions(actions: &mut Vec<DesyncAction>, chunk: Vec<u8>, default_ttl: u8, fake_ttl: u8) {
    actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
    actions.push(DesyncAction::Write(chunk));
    actions.push(DesyncAction::AwaitWritable);
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}

pub(super) fn push_disoob_actions(
    actions: &mut Vec<DesyncAction>,
    chunk: Vec<u8>,
    urgent_byte: u8,
    default_ttl: u8,
    fake_ttl: u8,
) {
    actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
    actions.push(DesyncAction::WriteUrgent { prefix: chunk, urgent_byte });
    actions.push(DesyncAction::AwaitWritable);
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}

pub(super) fn push_fake_rst_actions(actions: &mut Vec<DesyncAction>, chunk: Vec<u8>, fake_ttl: u8) {
    actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
    actions.push(DesyncAction::SendFakeRst);
    actions.push(DesyncAction::RestoreDefaultTtl);
    push_split_actions(actions, chunk);
}
