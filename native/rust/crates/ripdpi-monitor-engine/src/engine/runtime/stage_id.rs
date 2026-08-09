#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(in crate::engine) enum ExecutionStageId {
    Environment,
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    DohJsonSurvey,
    StrategyDnsBaseline,
    StrategyTcpCandidates,
    StrategyQuicCandidates,
    StrategyConnectionConcurrency,
    StrategyRecommendation,
}

impl ExecutionStageId {
    pub(in crate::engine) const fn as_str(&self) -> &'static str {
        match self {
            Self::Environment => "environment",
            Self::Dns => "dns",
            Self::Web => "web",
            Self::Quic => "quic",
            Self::Tcp => "tcp",
            Self::Service => "service",
            Self::Circumvention => "circumvention",
            Self::Telegram => "telegram",
            Self::Throughput => "throughput",
            Self::DohJsonSurvey => "doh_json_survey",
            Self::StrategyDnsBaseline => "strategy_dns_baseline",
            Self::StrategyTcpCandidates => "strategy_tcp_candidates",
            Self::StrategyQuicCandidates => "strategy_quic_candidates",
            Self::StrategyConnectionConcurrency => "strategy_connection_concurrency",
            Self::StrategyRecommendation => "strategy_recommendation",
        }
    }
}
