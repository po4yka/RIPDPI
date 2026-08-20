#[derive(Clone, PartialEq, Eq)]
pub struct CandidateAttemptCorrelationId {
    value: String,
    role: CandidateAttemptRole,
}

impl CandidateAttemptCorrelationId {
    pub fn evaluated(generation: u64, ordinal: u64) -> Option<Self> {
        (generation != 0 && ordinal != 0).then(|| Self {
            value: format!("p-{generation:016x}-{ordinal:016x}"),
            role: CandidateAttemptRole::Evaluated,
        })
    }

    pub fn warmup(generation: u64) -> Option<Self> {
        (generation != 0).then(|| Self {
            value: format!("w-{generation:016x}-0000000000000000"),
            role: CandidateAttemptRole::Warmup,
        })
    }

    pub fn from_opaque(value: impl Into<String>) -> Option<Self> {
        let value = value.into();
        let role = match value.as_bytes().first().copied() {
            Some(b'p') => CandidateAttemptRole::Evaluated,
            Some(b'w') => CandidateAttemptRole::Warmup,
            _ => return None,
        };
        let valid_shape = value.len() == 35
            && value.as_bytes().get(1) == Some(&b'-')
            && value.as_bytes().get(18) == Some(&b'-')
            && value
                .bytes()
                .enumerate()
                .all(|(index, byte)| matches!(index, 1 | 18) || byte.is_ascii_hexdigit() || index == 0);
        valid_shape.then_some(Self { value, role })
    }

    pub fn as_opaque_str(&self) -> &str {
        &self.value
    }

    pub fn is_evaluable(&self) -> bool {
        self.role == CandidateAttemptRole::Evaluated
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CandidateAttemptRole {
    Warmup,
    Evaluated,
}

impl std::fmt::Debug for CandidateAttemptCorrelationId {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("CandidateAttemptCorrelationId(<redacted>)")
    }
}
