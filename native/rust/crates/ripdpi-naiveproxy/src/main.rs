use std::collections::HashMap;
use std::env;
use std::io;

use tokio::net::TcpListener;

fn parse_args() -> HashMap<String, String> {
    let mut parsed = HashMap::new();
    let mut args = env::args().skip(1);
    while let Some(flag) = args.next() {
        if !flag.starts_with("--") {
            continue;
        }
        let value = args.next().unwrap_or_default();
        parsed.insert(flag.trim_start_matches("--").to_owned(), value);
    }
    parsed
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    let args = parse_args();
    let listen = args
        .get("listen")
        .cloned()
        .unwrap_or_else(|| "127.0.0.1:11980".to_owned());
    let listener = TcpListener::bind(&listen).await?;
    loop {
        let (socket, _) = listener.accept().await?;
        drop(socket);
    }
}
