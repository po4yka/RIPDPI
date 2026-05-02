pub(super) struct ActionContext {
    pub(super) strategy_family: Option<&'static str>,
    pub(super) default_ttl: u8,
    pub(super) md5sig: bool,
    pub(super) ip_id_mode: Option<ripdpi_config::IpIdMode>,
}

pub(super) struct FallbackAccounting {
    fallback: Option<&'static str>,
    bytes_committed: usize,
}

impl FallbackAccounting {
    pub(super) fn new(fallback: Option<&'static str>) -> Self {
        Self { fallback, bytes_committed: 0 }
    }

    pub(super) fn fallback(&self) -> Option<&'static str> {
        self.fallback
    }

    pub(super) fn bytes_committed(&self) -> usize {
        self.bytes_committed
    }

    pub(super) fn set_bytes_committed(&mut self, bytes_committed: usize) {
        self.bytes_committed = bytes_committed;
    }

    pub(super) fn add_bytes_committed(&mut self, bytes_committed: usize) {
        self.bytes_committed += bytes_committed;
    }

    pub(super) fn has_android_ttl_fallback(&self) -> bool {
        self.fallback.is_some()
    }
}
