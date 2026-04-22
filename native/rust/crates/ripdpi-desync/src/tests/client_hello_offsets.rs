use crate::{parse_client_hello_offsets, ClientHelloOffsetsError};
use ripdpi_packets::{parse_tls_client_hello_layout, tls_fake_profile_bytes, TlsFakeProfile};

#[test]
fn client_hello_offsets_parse_google_chrome_and_firefox_profiles() {
    for profile in [TlsFakeProfile::GoogleChrome, TlsFakeProfile::IanaFirefox] {
        let packet = tls_fake_profile_bytes(profile);
        let offsets = parse_client_hello_offsets(packet).expect("parse semantic offsets");
        let layout = parse_tls_client_hello_layout(packet).expect("parse tls layout");
        let alpn = layout.extensions.iter().find(|extension| extension.ext_type == 0x0010).expect("alpn extension");

        assert_eq!(offsets.handshake_start, 5, "{profile:?}: handshake should start after record header");
        assert_eq!(offsets.extensions_start, layout.markers.ext_len_start + 2, "{profile:?}: extension list start");
        assert_eq!(
            offsets.extensions_end,
            layout.markers.ext_len_start + 2 + read_u16(packet, layout.markers.ext_len_start),
            "{profile:?}: extension list end",
        );
        assert_eq!(
            offsets.server_name_extension_start,
            Some(layout.markers.sni_ext_start - 4),
            "{profile:?}: SNI extension header start",
        );
        assert_eq!(
            offsets.sni_hostname,
            Some(layout.markers.host_start..layout.markers.host_end),
            "{profile:?}: SNI hostname bytes",
        );
        assert_eq!(offsets.alpn_extension_start, Some(alpn.type_offset), "{profile:?}: ALPN extension header start");
    }
}

#[test]
fn client_hello_offsets_handle_client_hello_without_sni() {
    let packet = remove_extension(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome), 0x0000);

    let offsets = parse_client_hello_offsets(&packet).expect("parse semantic offsets without sni");

    assert_eq!(offsets.handshake_start, 5);
    assert!(offsets.extensions_start < offsets.extensions_end);
    assert_eq!(offsets.server_name_extension_start, None);
    assert_eq!(offsets.sni_hostname, None);
    assert!(offsets.alpn_extension_start.is_some());
}

#[test]
fn client_hello_offsets_follow_reordered_extensions() {
    let packet = reorder_extensions(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome), &[0x0010, 0x0000, 0x002b]);

    let offsets = parse_client_hello_offsets(&packet).expect("parse semantic offsets after reorder");
    let layout = parse_tls_client_hello_layout(&packet).expect("parse reordered tls layout");
    let server_name = layout.extensions.iter().find(|extension| extension.ext_type == 0x0000).expect("sni extension");
    let alpn = layout.extensions.iter().find(|extension| extension.ext_type == 0x0010).expect("alpn extension");

    assert_eq!(offsets.server_name_extension_start, Some(server_name.type_offset));
    assert_eq!(offsets.alpn_extension_start, Some(alpn.type_offset));
    assert_eq!(
        offsets.sni_hostname,
        Some(layout.markers.host_start..layout.markers.host_end),
        "reordered packet should preserve hostname offsets",
    );
}

#[test]
fn client_hello_offsets_map_multi_record_client_hello_back_to_original_bytes() {
    let packet = split_client_hello_into_two_records(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome), 112);

    let offsets = parse_client_hello_offsets(&packet).expect("parse semantic offsets across records");

    let sni_range = offsets.sni_hostname.expect("sni hostname");
    assert_eq!(offsets.handshake_start, 5);
    assert!(offsets.server_name_extension_start.expect("sni extension") < sni_range.start);
    assert!(sni_range.end <= packet.len());
    assert!(!packet[sni_range].is_empty());
}

#[test]
fn client_hello_offsets_return_typed_error_for_malformed_sni_extension() {
    let packet = corrupt_server_name_extension(tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome));

    let error = parse_client_hello_offsets(&packet).expect_err("malformed SNI should fail");

    assert_eq!(error, ClientHelloOffsetsError::InvalidServerNameExtension);
}

fn reorder_extensions(packet: &[u8], preferred_order: &[u16]) -> Vec<u8> {
    let layout = parse_tls_client_hello_layout(packet).expect("parse tls layout");
    let ext_len = read_u16(packet, layout.markers.ext_len_start);
    let ext_start = layout.markers.ext_len_start + 2;
    let ext_end = ext_start + ext_len;
    let mut reordered = Vec::with_capacity(ext_len);
    let mut used = vec![false; layout.extensions.len()];

    for ext_type in preferred_order {
        if let Some((index, extension)) = layout
            .extensions
            .iter()
            .enumerate()
            .find(|(index, extension)| !used[*index] && extension.ext_type == *ext_type)
        {
            reordered.extend_from_slice(&packet[extension.type_offset..extension.data_offset + extension.data_len]);
            used[index] = true;
        }
    }

    for (index, extension) in layout.extensions.iter().enumerate() {
        if used[index] {
            continue;
        }
        reordered.extend_from_slice(&packet[extension.type_offset..extension.data_offset + extension.data_len]);
    }

    let mut output = packet.to_vec();
    output[ext_start..ext_end].copy_from_slice(&reordered);
    output
}

fn remove_extension(packet: &[u8], ext_type: u16) -> Vec<u8> {
    let layout = parse_tls_client_hello_layout(packet).expect("parse tls layout");
    let extension = layout.extensions.iter().find(|extension| extension.ext_type == ext_type).expect("extension");
    let remove_start = extension.type_offset;
    let remove_end = extension.data_offset + extension.data_len;
    let removed_len = remove_end - remove_start;

    let mut output = Vec::with_capacity(packet.len() - removed_len);
    output.extend_from_slice(&packet[..remove_start]);
    output.extend_from_slice(&packet[remove_end..]);

    let new_ext_len = read_u16(packet, layout.markers.ext_len_start) - removed_len;
    write_u16(&mut output, layout.markers.ext_len_start, new_ext_len);
    let new_record_len = read_u16(packet, 3) - removed_len;
    write_u16(&mut output, 3, new_record_len);
    let new_handshake_len = read_u24(packet, 6) - removed_len;
    write_u24(&mut output, 6, new_handshake_len);
    output
}

fn split_client_hello_into_two_records(packet: &[u8], split: usize) -> Vec<u8> {
    let handshake = &packet[5..];
    let split = split.min(handshake.len().saturating_sub(1)).max(1);
    let header = &packet[..3];
    let first = &handshake[..split];
    let second = &handshake[split..];
    let mut output = Vec::with_capacity(packet.len() + 5);
    output.extend_from_slice(header);
    output.extend_from_slice(&(first.len() as u16).to_be_bytes());
    output.extend_from_slice(first);
    output.extend_from_slice(header);
    output.extend_from_slice(&(second.len() as u16).to_be_bytes());
    output.extend_from_slice(second);
    output
}

fn corrupt_server_name_extension(packet: &[u8]) -> Vec<u8> {
    let layout = parse_tls_client_hello_layout(packet).expect("parse tls layout");
    let extension = layout.extensions.iter().find(|extension| extension.ext_type == 0x0000).expect("sni extension");
    let mut output = packet.to_vec();
    write_u16(&mut output, extension.data_offset + 2, 0x7fff);
    output
}

fn read_u16(buffer: &[u8], offset: usize) -> usize {
    let bytes = buffer.get(offset..offset + 2).expect("u16 bytes");
    u16::from_be_bytes([bytes[0], bytes[1]]) as usize
}

fn read_u24(buffer: &[u8], offset: usize) -> usize {
    let bytes = buffer.get(offset..offset + 3).expect("u24 bytes");
    ((bytes[0] as usize) << 16) | ((bytes[1] as usize) << 8) | bytes[2] as usize
}

fn write_u16(buffer: &mut [u8], offset: usize, value: usize) {
    buffer[offset..offset + 2].copy_from_slice(&(value as u16).to_be_bytes());
}

fn write_u24(buffer: &mut [u8], offset: usize, value: usize) {
    buffer[offset] = ((value >> 16) & 0xff) as u8;
    buffer[offset + 1] = ((value >> 8) & 0xff) as u8;
    buffer[offset + 2] = (value & 0xff) as u8;
}
