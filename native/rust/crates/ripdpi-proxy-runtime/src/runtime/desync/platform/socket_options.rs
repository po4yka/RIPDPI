use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync_runtime::platform::TcpSocketOptions;
use ripdpi_proxy_runtime_adapter::platform as runtime_platform;

use super::RuntimeTcpDesyncPlatform;

impl TcpSocketOptions for RuntimeTcpDesyncPlatform {
    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()> {
        set_tcp_md5sig(stream, key_len)
    }

    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()> {
        set_tcp_window_clamp(stream, size)
    }

    fn wait_tcp_stage(&self, stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
        wait_tcp_stage(stream, wait_send, await_interval)
    }
}

pub(crate) fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    runtime_platform::socket::set_tcp_md5sig(stream, key_len)
}

pub(crate) fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    runtime_platform::socket::set_tcp_window_clamp(stream, size)
}

pub(crate) fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    runtime_platform::socket::wait_tcp_stage(stream, wait_send, await_interval)
}
