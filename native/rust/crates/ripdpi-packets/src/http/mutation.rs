use super::parse::{HttpRequestLayout, parse_http_parts, parse_http_request_layout};
use crate::types::{
    MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTPAD, MH_HOSTTAB, MH_METHODEOL, MH_METHODSPACE, MH_SPACE, MH_UNIXEOL,
    PacketMutation,
};

/// Apply HTTP header mutations in place on `buf`. Returns 0 if modified, -1 otherwise.
pub fn mod_http_inplace(buf: &mut Vec<u8>, flags: u32) -> isize {
    let mutation = mod_http_like_c(buf, flags);
    if mutation.rc == 0 {
        *buf = mutation.bytes;
    }
    mutation.rc
}

pub fn mod_http_like_c(input: &[u8], flags: u32) -> PacketMutation {
    let mut output = input.to_vec();
    let mut modified = false;

    if flags & MH_HMIX != 0
        && let Some(next) = apply_host_mixed_case(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_DMIX != 0
        && let Some(next) = apply_domain_mixed_case(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_SPACE != 0
        && let Some(next) = apply_host_remove_spaces(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_UNIXEOL != 0
        && let Some(next) = apply_http_unix_eol(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_METHODEOL != 0
        && let Some(next) = apply_http_method_eol(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_METHODSPACE != 0
        && let Some(next) = apply_http_method_space(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_HOSTPAD != 0
        && let Some(next) = apply_http_host_pad(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_HOSTEXTRASPACE != 0
        && let Some(next) = apply_host_extra_space(&output)
    {
        modified |= next != output;
        output = next;
    }
    if flags & MH_HOSTTAB != 0
        && let Some(next) = apply_host_tab(&output)
    {
        modified |= next != output;
        output = next;
    }

    PacketMutation { rc: if modified { 0 } else { -1 }, bytes: if modified { output } else { input.to_vec() } }
}

fn apply_host_mixed_case(input: &[u8]) -> Option<Vec<u8>> {
    let parts = parse_http_parts(input)?;
    if parts.header_name_start + 3 >= input.len() {
        return None;
    }
    let mut output = input.to_vec();
    output[parts.header_name_start] = output[parts.header_name_start].to_ascii_lowercase();
    output[parts.header_name_start + 1] = output[parts.header_name_start + 1].to_ascii_uppercase();
    output[parts.header_name_start + 3] = output[parts.header_name_start + 3].to_ascii_uppercase();
    Some(output)
}

fn apply_domain_mixed_case(input: &[u8]) -> Option<Vec<u8>> {
    let parts = parse_http_parts(input)?;
    let mut output = input.to_vec();
    for idx in (parts.host_start..parts.host_end).step_by(2) {
        output[idx] = output[idx].to_ascii_uppercase();
    }
    Some(output)
}

fn apply_host_remove_spaces(input: &[u8]) -> Option<Vec<u8>> {
    let parts = parse_http_parts(input)?;
    let mut output = input.to_vec();
    let mut hlen = parts.host_end - parts.host_start;
    while parts.host_start + hlen < output.len() && !output[parts.host_start + hlen].is_ascii_whitespace() {
        hlen += 1;
    }
    if parts.host_start + hlen >= output.len() {
        return None;
    }
    let header_value_start = parts.header_name_start + 5;
    let space_count = parts.host_start.saturating_sub(header_value_start);
    output.copy_within(parts.host_start..parts.host_start + hlen, header_value_start);
    for byte in &mut output[header_value_start + hlen..header_value_start + hlen + space_count] {
        *byte = b'\t';
    }
    Some(output)
}

fn reconstruct_http_request(
    input: &[u8],
    layout: &HttpRequestLayout,
    line_ending: &[u8],
    user_agent_padding: usize,
) -> Vec<u8> {
    let mut output = Vec::with_capacity(input.len() + user_agent_padding);
    output.extend_from_slice(&input[layout.method_start..layout.request_line_end]);
    output.extend_from_slice(line_ending);
    for (index, line) in layout.header_lines.iter().enumerate() {
        output.extend_from_slice(&input[line.start..line.end]);
        if layout.user_agent_index == Some(index) && user_agent_padding > 0 {
            output.extend(std::iter::repeat_n(b' ', user_agent_padding));
        }
        output.extend_from_slice(line_ending);
    }
    output.extend_from_slice(line_ending);
    output.extend_from_slice(&input[layout.body_start..]);
    output
}

fn apply_http_unix_eol(input: &[u8]) -> Option<Vec<u8>> {
    let layout = parse_http_request_layout(input)?;
    let candidate = reconstruct_http_request(input, &layout, b"\n", 0);
    if candidate.len() > input.len() {
        return None;
    }
    let padding = input.len().saturating_sub(candidate.len());
    let output = if padding == 0 {
        candidate
    } else if layout.user_agent_index.is_some() {
        reconstruct_http_request(input, &layout, b"\n", padding)
    } else {
        return None;
    };
    (output.len() == input.len() && output != input).then_some(output)
}

fn apply_http_method_eol(input: &[u8]) -> Option<Vec<u8>> {
    let layout = parse_http_request_layout(input)?;
    let user_agent = layout.user_agent_index.and_then(|index| layout.header_lines.get(index)).copied()?;
    if user_agent.end < user_agent.value_start + 2 {
        return None;
    }

    let mut output = Vec::with_capacity(input.len() + 2);
    output.extend_from_slice(b"\r\n");
    output.extend_from_slice(input);
    output.drain(user_agent.end..user_agent.end + 2);
    Some(output)
}

fn apply_http_method_space(input: &[u8]) -> Option<Vec<u8>> {
    let layout = parse_http_request_layout(input)?;
    let request_line = &input[layout.method_start..layout.request_line_end];
    let space = request_line.iter().position(|&byte| byte == b' ')?;
    let insert_at = layout.method_start + space;
    let mut output = Vec::with_capacity(input.len() + 1);
    output.extend_from_slice(&input[..insert_at]);
    output.extend_from_slice(b"  ");
    output.extend_from_slice(&input[insert_at + 1..]);
    Some(output)
}

fn apply_http_host_pad(input: &[u8]) -> Option<Vec<u8>> {
    let layout = parse_http_request_layout(input)?;
    let header_insertion = layout.body_start.checked_sub(2)?;
    let mut output = Vec::with_capacity(input.len() + 41);
    output.extend_from_slice(&input[..header_insertion]);
    output.extend_from_slice(b"X-Pad: 01234567890123456789012345678901\r\n");
    output.extend_from_slice(&input[header_insertion..]);
    Some(output)
}

/// Insert a space before the colon in the Host header: `Host : value`.
/// Many DPI parsers fail on the extra space but most HTTP servers accept it.
fn apply_host_extra_space(input: &[u8]) -> Option<Vec<u8>> {
    let parts = parse_http_parts(input)?;
    // header_name_start points at 'H' of "Host:", colon is at +4
    let colon_pos = parts.header_name_start + 4;
    if colon_pos >= input.len() || input[colon_pos] != b':' {
        return None;
    }
    let mut output = Vec::with_capacity(input.len() + 1);
    output.extend_from_slice(&input[..colon_pos]);
    output.push(b' ');
    output.extend_from_slice(&input[colon_pos..]);
    Some(output)
}

/// Replace the space after `Host:` with a tab character: `Host:\tvalue`.
/// Valid HTTP per RFC 7230 but trips up simple DPI parsers that match on
/// `Host: ` with a literal space.
fn apply_host_tab(input: &[u8]) -> Option<Vec<u8>> {
    let parts = parse_http_parts(input)?;
    // header_name_start points at 'H' of "Host:", colon is at +4, space at +5
    let space_pos = parts.header_name_start + 5;
    if space_pos >= input.len() || input[space_pos] != b' ' {
        return None;
    }
    let mut output = input.to_vec();
    output[space_pos] = b'\t';
    Some(output)
}
