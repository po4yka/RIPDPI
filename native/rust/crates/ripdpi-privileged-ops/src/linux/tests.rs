mod fd;
mod ffi_boundary_validation;
mod ffi_layout;
mod raw_packet;
mod socket_options;
mod tcp_repair;

use std::net::{Ipv4Addr, TcpListener, TcpStream};

use crate::linux::tcp_repair::{
    TcpRepairOptionsSnapshot, TcpRepairSnapshot, TcpRepairWindow, TcpTimestampSnapshot, TcpWindowScaleSnapshot,
};

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
    let addr = listener.local_addr().expect("listener addr");
    let client = TcpStream::connect(addr).expect("connect client");
    let (server, _) = listener.accept().expect("accept client");
    (client, server)
}

fn sample_tcp_repair_snapshot() -> TcpRepairSnapshot {
    TcpRepairSnapshot {
        sequence_number: 0x0102_0304,
        acknowledgment_number: 0x0506_0708,
        window_size: 4096,
        repair_window: TcpRepairWindow { rcv_wnd: 4096, ..Default::default() },
        options: TcpRepairOptionsSnapshot {
            mss: Some(1440),
            sack_permitted: true,
            window_scale: Some(TcpWindowScaleSnapshot { send: 7, receive: 8 }),
            timestamp: Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0x5566_7788, usec_ts: false }),
        },
    }
}

fn missing_protect_socket_path(label: &str) -> String {
    let path = std::env::temp_dir().join(format!("ripdpi-missing-protect-{}-{label}.sock", std::process::id()));
    let _ = std::fs::remove_file(&path);
    path.into_os_string().into_string().expect("temp path is valid UTF-8")
}
