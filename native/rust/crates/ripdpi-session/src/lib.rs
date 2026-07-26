#![forbid(unsafe_code)]

mod http_connect;
mod replies;
mod socks4;
mod socks5;
mod state;
mod triggers;
mod types;

pub use http_connect::parse_http_connect_request;
pub use replies::{encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply};
pub use socks4::parse_socks4_request;
pub use socks5::parse_socks5_request;
pub use state::{OutboundProgress, SessionPhase, SessionState};
pub use triggers::{TriggerEvent, detect_response_trigger};
pub use types::{
    ClientRequest, NameResolver, ProxyReply, S_ATP_I4, S_ATP_I6, S_ATP_ID, S_ATP_RESOLVED_ID, S_AUTH_BAD, S_AUTH_NONE,
    S_AUTH_USERPASS, S_CMD_AUDP, S_CMD_BIND, S_CMD_CONN, S_ER_ATP, S_ER_CMD, S_ER_CONN, S_ER_DENY, S_ER_GEN, S_ER_HOST,
    S_ER_NET, S_ER_OK, S_ER_TTL, S_SIZE_I4, S_SIZE_I6, S_SIZE_ID, S_SIZE_MIN, S_VER4, S_VER5, S4_ER, S4_OK,
    SessionConfig, SessionError, SocketType, TargetAddr,
};
