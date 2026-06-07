#![no_main]

use libfuzzer_sys::fuzz_target;
use ripdpi_config::{DesyncGroup, OffsetExpr, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, plan_tcp};
use ripdpi_packets::{DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS};

fn offset_expr_from_bytes(data: &[u8]) -> String {
    let signed = if data.first().copied().unwrap_or(0) & 0x1 == 0 {
        format!("{}", i16::from_be_bytes([data.first().copied().unwrap_or(0), data.get(1).copied().unwrap_or(0)]))
    } else {
        let base = match data.first().copied().unwrap_or(0) % 10 {
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

    let with_repeat = if data.get(2).copied().unwrap_or(0) & 0x1 == 0 {
        signed
    } else {
        format!("{}:{}", signed, 1 + i32::from(data.get(3).copied().unwrap_or(0) % 4))
    };

    match data.get(4).copied().unwrap_or(0) % 4 {
        0 => with_repeat,
        1 => {
            let adaptive = match data.get(5).copied().unwrap_or(0) % 7 {
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

fn tcp_context(payload_len: usize, data: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1 + i64::from(data.get(7).copied().unwrap_or(0) % 3),
        payload_size: payload_len as i64,
        stream_start: 0,
        stream_end: payload_len.saturating_sub(1) as i64,
        seqovl_supported: data.get(8).copied().unwrap_or(0) & 0x1 != 0,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

fn tcp_step_kind(data: &[u8]) -> TcpChainStepKind {
    match data.get(9).copied().unwrap_or(0) % 10 {
        0 => TcpChainStepKind::Split,
        1 => TcpChainStepKind::Disorder,
        2 => TcpChainStepKind::Oob,
        3 => TcpChainStepKind::Disoob,
        4 => TcpChainStepKind::Fake,
        5 => TcpChainStepKind::FakeSplit,
        6 => TcpChainStepKind::FakeDisorder,
        7 => TcpChainStepKind::HostFake,
        8 => TcpChainStepKind::IpFrag2,
        _ => TcpChainStepKind::SeqOverlap,
    }
}

fn plan_with_offset(expr: OffsetExpr, data: &[u8]) {
    let mut generated = Vec::with_capacity(data.len().saturating_add(42));
    generated.extend_from_slice(b"GET / HTTP/1.1\r\nHost: ");
    generated.extend(data.iter().copied().take(96).map(|byte| match byte {
        b'\r' | b'\n' | 0 => b'a',
        value => value,
    }));
    generated.extend_from_slice(b".example\r\n\r\n");

    let payloads: [&[u8]; 5] = [
        &[],
        &generated[..generated.len().min(data.first().copied().unwrap_or(0) as usize)],
        generated.as_slice(),
        &DEFAULT_FAKE_HTTP[..DEFAULT_FAKE_HTTP.len().min(data.get(10).copied().unwrap_or(0) as usize)],
        &DEFAULT_FAKE_TLS[..DEFAULT_FAKE_TLS.len().min(data.get(11).copied().unwrap_or(0) as usize)],
    ];

    for payload in payloads {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(tcp_step_kind(data), expr));
        if data.get(12).copied().unwrap_or(0) & 0x1 != 0 {
            group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, expr));
        }
        if data.get(13).copied().unwrap_or(0) & 0x1 != 0 {
            group.actions.tcp_chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, expr));
        }
        let _ = plan_tcp(
            &group,
            payload,
            u32::from(data.get(14).copied().unwrap_or(0)),
            64,
            tcp_context(payload.len(), data),
        );
    }
}

fuzz_target!(|data: &[u8]| {
    for seed in [
        "host+2:3",
        "endhost-1:2:1",
        "auto(balanced)",
        "method+2",
        "extlen+32767",
        "echext-32768",
        "sniext+32767",
        "0",
        "abs+0",
    ] {
        if let Ok(expr) = ripdpi_config::parse_offset_expr(seed) {
            plan_with_offset(expr, data);
        }
    }

    let raw = String::from_utf8_lossy(data);
    let _ = ripdpi_config::parse_offset_expr(&raw);

    let shaped = offset_expr_from_bytes(data);
    if let Ok(expr) = ripdpi_config::parse_offset_expr(&shaped) {
        plan_with_offset(expr, data);
    }
});
