#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fn split_http_response(data: &[u8]) -> (&[u8], &[u8]) {
    if let Some(header_end) = data.windows(4).position(|window| window == b"\r\n\r\n") {
        (&data[..header_end], &data[header_end + 4..])
    } else {
        (data, &[])
    }
}

fuzz_target!(|data: &[u8]| {
    let (headers, body) = split_http_response(data);
    let _ = ripdpi_monitor::fuzz_parse_http_response(headers, body);

    let structured = common::http_response_from_bytes(data);
    let (headers, body) = split_http_response(&structured);
    let _ = ripdpi_monitor::fuzz_parse_http_response(headers, body);
});
