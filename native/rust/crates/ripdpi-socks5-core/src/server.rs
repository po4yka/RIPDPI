use crate::util::stream::{ConnectError, tcp_connect_with_timeout};
use crate::util::target_addr::{AddrError, TargetAddr, read_address};
use crate::{
    AuthenticationMethod, ReplyError, Socks5Command, SocksError, UdpHeaderError, consts, new_udp_header,
    parse_udp_request, read_exact, ready,
};
use anyhow::Context;
use socket2::{Domain, Socket, Type};
use std::future::Future;
use std::io;
use std::marker::PhantomData;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs as StdToSocketAddrs};
use std::ops::Deref;
use std::pin::Pin;
use std::string::FromUtf8Error;
use std::sync::Arc;
use std::task::{Context as AsyncContext, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, ToSocketAddrs as AsyncToSocketAddrs, UdpSocket};
use tokio::try_join;
use tokio_stream::Stream;

include!("server/error.rs");
include!("server/auth.rs");
include!("server/config.rs");
include!("server/listener.rs");
include!("server/protocol.rs");
include!("server/dns.rs");
include!("server/tcp.rs");
include!("server/udp.rs");
include!("server/stream.rs");
include!("server/reply.rs");
include!("server/tests.rs");
