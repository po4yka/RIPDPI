use std::time::Duration;

use criterion::{criterion_group, criterion_main, Criterion};
use ripdpi_config::{parse_cli, ParseResult, StartupEnv};

fn bench_cli_parse(c: &mut Criterion) {
    let args: Vec<String> =
        ["--ip", "127.0.0.1", "-p", "1080", "--window-clamp", "2"].iter().map(|s| (*s).to_string()).collect();
    let startup = StartupEnv::default();

    c.bench_function("cli-parse/basic", |b| {
        b.iter(|| {
            let result = parse_cli(&args, &startup).expect("parse");
            match result {
                ParseResult::Run(config) => std::hint::black_box(config),
                other => panic!("unexpected: {other:?}"),
            };
        });
    });
}

fn bench_proxy_config_json(c: &mut Criterion) {
    let json = serde_json::json!({
        "kind": "command_line",
        "args": ["--ip", "127.0.0.1", "-p", "1080", "--window-clamp", "2"]
    });
    let json_str = json.to_string();

    c.bench_function("json-config-parse/command-line-payload", |b| {
        b.iter(|| {
            let payload = ripdpi_proxy_config::parse_proxy_config_json(&json_str).expect("parse json");
            let config = ripdpi_proxy_config::runtime_config_from_payload(payload).expect("convert");
            std::hint::black_box(config);
        });
    });
}

criterion_group! {
    name = proxy_cold_start;
    config = Criterion::default()
        .sample_size(50)
        .measurement_time(Duration::from_secs(10));
    targets = bench_cli_parse, bench_proxy_config_json
}

criterion_main!(proxy_cold_start);
