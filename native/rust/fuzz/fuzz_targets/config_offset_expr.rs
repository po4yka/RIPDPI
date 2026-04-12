#![no_main]

use libfuzzer_sys::fuzz_target;

fn offset_expr_from_bytes(data: &[u8]) -> String {
    let signed =
        if data.first().copied().unwrap_or(0) & 0x1 == 0 {
            format!("{}", i16::from_be_bytes([data.first().copied().unwrap_or(0), data.get(1).copied().unwrap_or(0)]))
        } else {
            let base =
                match data.first().copied().unwrap_or(0) % 10 {
                    0 => "abs",
                    1 => "host",
                    2 => "endhost",
                    3 => "sld",
                    4 => "midsld",
                    5 => "endsld",
                    6 => "method",
                    7 => "extlen",
                    8 => "echext",
                    _ => "sniext",
                };
            let delta = i8::from_be_bytes([data.get(1).copied().unwrap_or(0)]);
            format!("{base}{delta:+}")
        };

    let with_repeat =
        if data.get(2).copied().unwrap_or(0) & 0x1 == 0 {
            signed
        } else {
            format!("{}:{}", signed, 1 + i32::from(data.get(3).copied().unwrap_or(0) % 4))
        };

    match data.get(4).copied().unwrap_or(0) % 4 {
        0 => with_repeat,
        1 => {
            let adaptive =
                match data.get(5).copied().unwrap_or(0) % 7 {
                    0 => "auto(balanced)",
                    1 => "auto(host)",
                    2 => "auto(midsld)",
                    3 => "auto(endhost)",
                    4 => "auto(method)",
                    5 => "auto(sniext)",
                    _ => "auto(extlen)",
                };
            adaptive.to_string()
        }
        2 => format!("{}:{}", with_repeat, i32::from(data.get(6).copied().unwrap_or(0) % 3)),
        _ => String::from_utf8_lossy(data).into_owned(),
    }
}

fuzz_target!(|data: &[u8]| {
    for seed in [
        "host+2:3",
        "endhost-1:2:1",
        "auto(balanced)",
        "method+2",
        "0",
        "abs+0",
    ] {
        let _ = ripdpi_config::parse_offset_expr(seed);
    }

    let raw = String::from_utf8_lossy(data);
    let _ = ripdpi_config::parse_offset_expr(&raw);

    let shaped = offset_expr_from_bytes(data);
    let _ = ripdpi_config::parse_offset_expr(&shaped);
});
