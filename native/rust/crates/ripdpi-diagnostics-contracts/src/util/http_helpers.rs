use super::constants::FAT_HEADER_THRESHOLD_BYTES;

pub fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
}

pub fn parse_content_length(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.split("\r\n") {
        let (name, value) = line.split_once(':')?;
        if name.trim().eq_ignore_ascii_case("content-length") {
            return value.trim().parse::<usize>().ok();
        }
    }
    None
}

pub fn fat_threshold_reached(bytes_sent: usize) -> bool {
    bytes_sent >= FAT_HEADER_THRESHOLD_BYTES.saturating_sub(2 * 1024)
}

pub fn late_stage_cutoff(bytes_sent: usize, responses_seen: usize) -> bool {
    fat_threshold_reached(bytes_sent) || (responses_seen >= 1 && bytes_sent >= 8 * 1024)
}
