//! Loopback acceptance client for scripts/tests/run-finalmask-peer.py.
use std::error::Error;
use std::time::Duration;

use ripdpi_vless::config::VlessRealityConfig;
use ripdpi_xhttp::{FinalmaskConfig, XhttpClient, XhttpProtocolMode, XhttpRealityConfig, XmuxConfig};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// # Cancel safety:
/// NOT cancel-safe: this process owns the test carrier and discards it on timeout.
#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn Error>> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    if args.len() != 5 {
        return Err("expected loopback-port target-port public-key mask mode".into());
    }
    let mask = &args[3];
    let mut vless = VlessRealityConfig::from_strings(
        "127.0.0.1",
        args[0].parse()?,
        "00000000-0000-4000-8000-000000000006",
        "fixture.example",
        &args[2],
        "ab12",
        "chrome_stable",
    )?;
    vless.flow = ripdpi_vless::addons::VlessFlow::None;
    let finalmask = FinalmaskConfig {
        r#type: mask.clone(),
        header_hex: "abcd".into(),
        trailer_hex: "ef01".into(),
        rand_range: "4-4".into(),
        sudoku_seed: " fixture-seed ".into(),
        fragment_packets: 3,
        fragment_min_bytes: 10,
        fragment_max_bytes: 30,
    };
    let client = XhttpClient::new_reality(XhttpRealityConfig {
        vless,
        path: "/xhttp".into(),
        host: Some("fixture.example".into()),
        bind_ip: None,
        socket_protector: None,
        xmux: XmuxConfig::default(),
        finalmask,
        protocol_mode: XhttpProtocolMode::parse(&args[4], true)?,
    });
    tokio::time::timeout(Duration::from_secs(15), async {
        let mut stream = client.connect(&format!("127.0.0.1:{}", args[1])).await?;
        eprintln!("{mask} {}: session opened", args[4]);
        let payload = (0..=255).collect::<Vec<u8>>();
        stream.write_all(&payload).await?;
        eprintln!("{mask} {}: payload written", args[4]);
        let mut reply = vec![0; payload.len()];
        stream.read_exact(&mut reply).await?;
        if reply != payload {
            return Err(std::io::Error::other("peer changed the application bytes"));
        }
        Ok::<(), std::io::Error>(())
    })
    .await??;
    println!("{mask} {}: all 256 byte values round-trip", args[4]);
    Ok(())
}
