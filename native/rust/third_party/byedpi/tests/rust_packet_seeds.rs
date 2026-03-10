fn decode_hex(input: &str) -> Vec<u8> {
    let filtered: String = input
        .chars()
        .filter(|ch| !ch.is_ascii_whitespace())
        .collect();
    assert_eq!(filtered.len() % 2, 0, "hex payload must have even length");

    filtered
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let high = (pair[0] as char).to_digit(16).expect("hex digit") as u8;
            let low = (pair[1] as char).to_digit(16).expect("hex digit") as u8;
            (high << 4) | low
        })
        .collect()
}

fn read_u16(data: &[u8], offset: usize) -> u16 {
    ((data[offset] as u16) << 8) | data[offset + 1] as u16
}

fn write_u16(data: &mut [u8], offset: usize, value: u16) {
    data[offset] = ((value >> 8) & 0xff) as u8;
    data[offset + 1] = (value & 0xff) as u8;
}

fn write_u24(data: &mut [u8], offset: usize, value: u32) {
    data[offset] = ((value >> 16) & 0xff) as u8;
    data[offset + 1] = ((value >> 8) & 0xff) as u8;
    data[offset + 2] = (value & 0xff) as u8;
}

fn find_ext_block(data: &[u8]) -> usize {
    let sid_len = data[43] as usize;
    let cip_len = read_u16(data, 44 + sid_len) as usize;
    44 + sid_len + 2 + cip_len + 2
}

fn find_extension(data: &[u8], ext_type: u16) -> usize {
    let block = find_ext_block(data);
    let mut pos = block + 2;
    let end = pos + read_u16(data, block) as usize;
    while pos + 4 <= end {
        let curr_type = read_u16(data, pos);
        let curr_len = read_u16(data, pos + 2) as usize;
        if curr_type == ext_type {
            return pos;
        }
        pos += 4 + curr_len;
    }
    panic!("extension 0x{ext_type:04x} not found");
}

pub fn http_request() -> Vec<u8> {
    b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n".to_vec()
}

pub fn http_redirect_response() -> Vec<u8> {
    concat!(
        "HTTP/1.1 302 Found\r\n",
        "Location: https://example.net/wiki\r\n",
        "Content-Length: 0\r\n",
        "\r\n"
    )
    .as_bytes()
    .to_vec()
}

pub fn tls_client_hello() -> Vec<u8> {
    let mut data = decode_hex(
        "
1603010200010001fc0303035f6f2ced1322f8dcb2f260482d72666f57dd139d1b37dcfa362ebaf992993a20
f9df0c2e8a55898231631aefa8be0858a7a35a18d3965f045cb462af89d70f8b003e130213031301c02cc030
009fcca9cca8ccaac02bc02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d009c003d
003c0035002f00ff010001750000001600140000117777772e77696b6970656469612e6f7267000b00040300
0102000a00160014001d0017001e00190018010001010102010301040010000e000c02683208687474702f31
2e31001600000017000000310000000d002a0028040305030603080708080809080a080b0804080508060401
05010601030303010302040205020602002b0009080304030303020301002d00020101003300260024001d00
20118cb88ce88a08901eee19d9dde8d406b1d1e2abe01663d6dcda84a4b84bfb0e001500ac000000000000
",
    );
    data.resize(517, 0);
    data
}

pub fn tls_server_hello_like() -> Vec<u8> {
    let mut data = tls_client_hello();
    let sid_len = data[43] as usize;
    let ext_block = 44 + sid_len + 3;
    data[5] = 0x02;
    if data[43] != 0 {
        data[44] ^= 0x01;
    }
    write_u16(&mut data, ext_block, 5);
    write_u16(&mut data, ext_block + 2, 0x002b);
    write_u16(&mut data, ext_block + 4, 1);
    data[ext_block + 6] = 0;
    data
}

pub fn tls_client_hello_ech() -> Vec<u8> {
    let mut data = tls_client_hello();
    let ech_extension = decode_hex("fe0d000e000000000000000201020002aabb");
    let insert_at = find_extension(&data, 0x0015);

    data.splice(insert_at..insert_at, ech_extension.iter().copied());

    let ext_block = find_ext_block(&data);
    let ext_block_len = read_u16(&data, ext_block) + ech_extension.len() as u16;
    let record_len = read_u16(&data, 3) + ech_extension.len() as u16;
    write_u16(&mut data, ext_block, ext_block_len);
    write_u16(&mut data, 3, record_len);

    let handshake_len = (((data[6] as u32) << 16) | ((data[7] as u32) << 8) | data[8] as u32)
        + ech_extension.len() as u32;
    assert!(handshake_len <= 0x00ff_ffff, "handshake length overflow");
    write_u24(&mut data, 6, handshake_len);

    data
}
