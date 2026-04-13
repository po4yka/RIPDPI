#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NumericRange<T> {
    pub start: T,
    pub end: T,
}

impl<T> NumericRange<T> {
    pub const fn new(start: T, end: T) -> Self {
        Self { start, end }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ActivationFilter {
    pub round: Option<NumericRange<i64>>,
    pub payload_size: Option<NumericRange<i64>>,
    pub stream_bytes: Option<NumericRange<i64>>,
    pub tcp_has_timestamp: Option<bool>,
    pub tcp_has_ech: Option<bool>,
    pub tcp_window_below: Option<u16>,
    pub tcp_mss_below: Option<u16>,
}

impl ActivationFilter {
    pub const fn is_unbounded(self) -> bool {
        self.round.is_none()
            && self.payload_size.is_none()
            && self.stream_bytes.is_none()
            && self.tcp_has_timestamp.is_none()
            && self.tcp_has_ech.is_none()
            && self.tcp_window_below.is_none()
            && self.tcp_mss_below.is_none()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OffsetProto {
    Any,
    TlsOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OffsetBase {
    Abs,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    AutoBalanced,
    AutoHost,
    AutoMidSld,
    AutoEndHost,
    AutoMethod,
    AutoSniExt,
    AutoExtLen,
}

impl OffsetBase {
    pub const fn is_adaptive(self) -> bool {
        matches!(
            self,
            Self::AutoBalanced
                | Self::AutoHost
                | Self::AutoMidSld
                | Self::AutoEndHost
                | Self::AutoMethod
                | Self::AutoSniExt
                | Self::AutoExtLen
        )
    }

    pub const fn supports_fake_offset(self) -> bool {
        !self.is_adaptive() && !matches!(self, Self::EchExt)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OffsetExpr {
    pub base: OffsetBase,
    pub proto: OffsetProto,
    pub delta: i64,
    pub repeats: i32,
    pub skip: i32,
}

impl OffsetExpr {
    pub const fn absolute(delta: i64) -> Self {
        Self { base: OffsetBase::Abs, proto: OffsetProto::Any, delta, repeats: 0, skip: 0 }
    }

    pub const fn marker(base: OffsetBase, delta: i64) -> Self {
        Self { base, proto: OffsetProto::Any, delta, repeats: 0, skip: 0 }
    }

    pub const fn tls_marker(base: OffsetBase, delta: i64) -> Self {
        Self { base, proto: OffsetProto::TlsOnly, delta, repeats: 0, skip: 0 }
    }

    pub const fn host(delta: i64) -> Self {
        Self::marker(OffsetBase::Host, delta)
    }

    pub const fn tls_host(delta: i64) -> Self {
        Self::tls_marker(OffsetBase::Host, delta)
    }

    pub const fn adaptive(base: OffsetBase) -> Self {
        Self { base, proto: OffsetProto::Any, delta: 0, repeats: 0, skip: 0 }
    }

    pub const fn with_repeat_skip(self, repeats: i32, skip: i32) -> Self {
        Self { repeats, skip, ..self }
    }

    pub const fn needs_tls_record_adjustment(self) -> bool {
        !matches!(self.base, OffsetBase::Abs) || self.delta < 0
    }

    pub const fn absolute_positive(self) -> Option<i64> {
        if matches!(self.base, OffsetBase::Abs) && self.delta >= 0 {
            Some(self.delta)
        } else {
            None
        }
    }

    pub const fn supports_fake_offset(self) -> bool {
        self.base.supports_fake_offset()
    }
}
