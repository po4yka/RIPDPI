//! Position helpers for the bundled zapret2 ABI 5 payload functions.

use std::collections::HashMap;
use std::sync::Arc;

use mlua::{Lua, LuaString, Table};
use ripdpi_strategy_trait::MarkerName;

#[derive(Clone, Copy)]
struct Position {
    marker: MarkerName,
    offset: i16,
}

fn parse(token: &str) -> mlua::Result<Position> {
    if let Ok(offset) = token.parse::<i16>() {
        return Ok(Position { marker: MarkerName::Absolute, offset });
    }
    let suffix = token.find(['+', '-']);
    let name = suffix.map_or(token, |index| &token[..index]);
    let marker =
        super::enabled::marker_by_name(name).ok_or_else(|| mlua::Error::external("invalid position marker"))?;
    let offset = suffix
        .map_or(Ok(0), |index| token[index..].parse::<i16>())
        .map_err(|_| mlua::Error::external("invalid position offset"))?;
    Ok(Position { marker, offset })
}

fn parse_list(spec: &str, limit: usize) -> mlua::Result<Vec<Position>> {
    if spec.is_empty() {
        return Ok(Vec::new());
    }
    let mut positions = Vec::new();
    for token in spec.split(',') {
        if positions.len() == limit {
            return Err(mlua::Error::external("too many position markers"));
        }
        positions.push(parse(token)?);
    }
    Ok(positions)
}

fn resolve(
    position: Position,
    len: usize,
    payload_type: &str,
    original_type: &str,
    markers: &HashMap<MarkerName, usize>,
) -> Option<usize> {
    let base = match position.marker {
        MarkerName::Absolute => {
            if position.offset < 0 {
                len
            } else {
                0
            }
        }
        MarkerName::Data => 0,
        MarkerName::End => len,
        marker if payload_type == original_type && matches!(payload_type, "http_req" | "tls_client_hello") => {
            *markers.get(&marker)?
        }
        _ => return None,
    };
    let offset = base.checked_add_signed(isize::from(position.offset))?;
    (offset < len).then_some(offset)
}

fn check_marker_blob(positions: &[Position], data: &[u8], original: &[u8], payload_type: &str) -> mlua::Result<()> {
    // ponytail: these markers describe the original payload; reject alternate
    // host-relative blobs until this planner shares the upstream packet parser.
    if matches!(payload_type, "http_req" | "tls_client_hello")
        && data != original
        && positions
            .iter()
            .any(|position| !matches!(position.marker, MarkerName::Absolute | MarkerName::Data | MarkerName::End))
    {
        return Err(mlua::Error::external("host-relative positions require the original payload"));
    }
    Ok(())
}

fn check_payload_type(payload_type: &str) -> mlua::Result<()> {
    // Names from the pinned nfq2/protocol.c l7payload_name registry.
    if matches!(
        payload_type,
        "all"
            | "unknown"
            | "empty"
            | "known"
            | "ipv4"
            | "ipv6"
            | "icmp"
            | "http_req"
            | "http_reply"
            | "tls_client_hello"
            | "tls_server_hello"
            | "dtls_client_hello"
            | "dtls_server_hello"
            | "quic_initial"
            | "wireguard_initiation"
            | "wireguard_response"
            | "wireguard_cookie"
            | "wireguard_keepalive"
            | "wireguard_data"
            | "dht"
            | "discord_ip_discovery"
            | "stun"
            | "xmpp_stream"
            | "xmpp_starttls"
            | "xmpp_proceed"
            | "xmpp_features"
            | "dns_query"
            | "dns_response"
            | "mtproto_initial"
            | "bt_handshake"
            | "utp_bt_handshake"
    ) {
        Ok(())
    } else {
        Err(mlua::Error::external("invalid payload type"))
    }
}

fn table(lua: &Lua, positions: impl IntoIterator<Item = usize>, zero_based: bool) -> mlua::Result<Table> {
    lua.create_sequence_from(positions.into_iter().map(|offset| offset + usize::from(!zero_based)))
}

pub(super) fn install(
    lua: &Lua,
    markers: &HashMap<MarkerName, usize>,
    payload: &[u8],
    original_type: &'static str,
) -> mlua::Result<()> {
    let original: Arc<[u8]> = payload.into();
    let single_original = Arc::clone(&original);
    let single_markers = markers.clone();
    lua.globals().set(
        "resolve_pos",
        lua.create_function(
            move |_, (data, payload_type, spec, zero_based): (LuaString, String, String, Option<bool>)| {
                check_payload_type(&payload_type)?;
                let position = parse(&spec)?;
                check_marker_blob(&[position], &data.as_bytes(), &single_original, &payload_type)?;
                Ok(resolve(position, data.as_bytes().len(), &payload_type, original_type, &single_markers)
                    .map(|offset| offset + usize::from(!zero_based.unwrap_or(false))))
            },
        )?,
    )?;
    let multi_markers = markers.clone();
    let multi_original = Arc::clone(&original);
    lua.globals().set(
        "resolve_multi_pos",
        lua.create_function(
            move |lua, (data, payload_type, spec, zero_based): (LuaString, String, String, Option<bool>)| {
                check_payload_type(&payload_type)?;
                let parsed = parse_list(&spec, 128)?;
                check_marker_blob(&parsed, &data.as_bytes(), &multi_original, &payload_type)?;
                let mut positions: Vec<_> = parsed
                    .into_iter()
                    .filter_map(|position| {
                        resolve(position, data.as_bytes().len(), &payload_type, original_type, &multi_markers)
                    })
                    .collect();
                positions.sort_unstable();
                positions.dedup();
                table(lua, positions, zero_based.unwrap_or(false))
            },
        )?,
    )?;
    let range_markers = markers.clone();
    lua.globals().set(
        "resolve_range",
        lua.create_function(
            move |lua,
                  (data, payload_type, spec, strict, zero_based): (
                LuaString,
                String,
                String,
                Option<bool>,
                Option<bool>,
            )| {
                check_payload_type(&payload_type)?;
                let positions = parse_list(&spec, 2)?;
                if positions.len() != 2 {
                    return Err(mlua::Error::external("resolve_range requires two markers"));
                }
                check_marker_blob(&positions, &data.as_bytes(), &original, &payload_type)?;
                let len = data.as_bytes().len();
                let start = resolve(positions[0], len, &payload_type, original_type, &range_markers);
                let end = resolve(positions[1], len, &payload_type, original_type, &range_markers);
                if (start.is_none() && end.is_none()) || (strict.unwrap_or(false) && (start.is_none() || end.is_none()))
                {
                    return Ok(None);
                }
                let start = start.unwrap_or(0);
                let end = end.unwrap_or(len.saturating_sub(1));
                if start > end {
                    return Ok(None);
                }
                table(lua, [start, end], zero_based.unwrap_or(false)).map(Some)
            },
        )?,
    )?;
    Ok(())
}
