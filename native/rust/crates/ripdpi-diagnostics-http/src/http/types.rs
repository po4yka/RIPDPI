use std::collections::HashMap;

#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status_code: u16,
    pub reason: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct HttpObservation {
    pub status: String,
    pub response: Option<HttpResponse>,
    pub error: Option<String>,
    pub ttfb_ms: Option<u64>,
}
