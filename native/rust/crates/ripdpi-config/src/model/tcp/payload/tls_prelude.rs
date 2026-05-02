#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpTlsRandRecPayload {
    pub fragment_count: i32,
    pub min_fragment_size: i32,
    pub max_fragment_size: i32,
}
