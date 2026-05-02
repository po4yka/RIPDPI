use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_runtime_platform as runtime_platform;

pub(crate) fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    runtime_platform::set_tcp_md5sig(stream, key_len)
}

pub(crate) fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    runtime_platform::set_tcp_window_clamp(stream, size)
}

pub(crate) fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    runtime_platform::wait_tcp_stage(stream, wait_send, await_interval)
}
