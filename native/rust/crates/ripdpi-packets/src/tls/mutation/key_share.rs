use crate::types::PacketMutation;
use crate::util::read_u16;

use super::super::detect::is_tls_client_hello;
use super::super::edit::{adjust_tls_lengths, remove_ks_group};

pub fn remove_tls_key_share_group_like_c(input: &[u8], group: u16) -> PacketMutation {
    if !is_tls_client_hello(input) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let Some(parsed) = crate::tls_nom::parse_client_hello_record(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };

    let mut output = input.to_vec();
    let removed = remove_ks_group(&mut output, input.len(), parsed.ext_len_offset, group);
    if removed == 0 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    let Some(key_share_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0033) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let Some(remaining_key_share_len) = read_u16(&output, key_share_offs + 2) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if remaining_key_share_len <= 2 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    if !adjust_tls_lengths(&mut output, parsed.ext_len_offset, -(removed as isize)) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    output.truncate(input.len() - removed);
    PacketMutation { rc: 0, bytes: output }
}
