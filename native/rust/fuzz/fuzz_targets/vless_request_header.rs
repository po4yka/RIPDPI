#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::vless_smoke();

    let _ = ripdpi_vless::wire::parse_request_header(data);

    if !data.is_empty() {
        let host = common::ascii_label(data, "vless-", 24);
        let port = 1 + u16::from(data[0]);
        let target = format!("{host}.example:{port}");
        let uuid = [data[0]; 16];
        let encoded = ripdpi_vless::wire::encode_request(&uuid, &data[1..data.len().min(8)], &target);
        let _ = ripdpi_vless::wire::parse_request_header(&encoded);

        let mut invalid_command = encoded.clone();
        if invalid_command.len() > 18 {
            invalid_command[18] = 0x02;
            let _ = ripdpi_vless::wire::parse_request_header(&invalid_command);
        }

        let truncated_len = encoded.len().saturating_sub(1 + usize::from(data[0] % 4));
        let _ = ripdpi_vless::wire::parse_request_header(&encoded[..truncated_len]);
    }
});
