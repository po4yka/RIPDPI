use super::FakeTtlAttempt;
use std::io;

#[derive(Debug, PartialEq, Eq)]
pub(in crate::runtime::relay) struct FakeTtlFeedback {
    pub(in crate::runtime::relay) attempt: FakeTtlAttempt,
    pub(in crate::runtime::relay) server_ttl: Option<u8>,
}

enum FirstResponseFeedback {
    Waiting,
    Accepted { server_ttl: Option<u8> },
    Claimed,
    Completed,
}

pub(super) struct FakeTtlFeedbackLedger {
    pub(super) bound_attempt: Option<FakeTtlAttempt>,
    send_in_flight: bool,
    response: FirstResponseFeedback,
}

impl FakeTtlFeedbackLedger {
    pub(super) fn new(bound_attempt: Option<FakeTtlAttempt>, response_completed: bool) -> Self {
        Self {
            bound_attempt,
            send_in_flight: false,
            response: if response_completed {
                FirstResponseFeedback::Completed
            } else {
                FirstResponseFeedback::Waiting
            },
        }
    }

    pub(super) fn begin_send(&mut self) -> io::Result<Option<FakeTtlAttempt>> {
        if self.send_in_flight {
            return Err(io::Error::other("overlapping outbound fake TTL sends"));
        }
        self.send_in_flight = true;
        Ok(self.bound_attempt)
    }

    pub(super) fn finish_send(&mut self, attempt: Option<FakeTtlAttempt>) -> io::Result<Option<FakeTtlFeedback>> {
        if !self.send_in_flight {
            return Err(io::Error::other("outbound fake TTL send was not started"));
        }
        if self.bound_attempt.is_none() {
            self.bound_attempt = attempt;
        }
        self.send_in_flight = false;
        let FirstResponseFeedback::Accepted { server_ttl } = self.response else {
            return Ok(None);
        };
        Ok(self.claim_or_complete(server_ttl))
    }

    pub(super) fn accept_response(&mut self, server_ttl: Option<u8>) -> Option<FakeTtlFeedback> {
        if !matches!(self.response, FirstResponseFeedback::Waiting) {
            return None;
        }
        if self.bound_attempt.is_some() {
            return self.claim_or_complete(server_ttl);
        }
        self.response = if self.send_in_flight {
            FirstResponseFeedback::Accepted { server_ttl }
        } else {
            FirstResponseFeedback::Completed
        };
        None
    }

    pub(super) fn complete_feedback(&mut self) {
        if matches!(self.response, FirstResponseFeedback::Claimed) {
            self.response = FirstResponseFeedback::Completed;
        }
    }

    fn claim_or_complete(&mut self, server_ttl: Option<u8>) -> Option<FakeTtlFeedback> {
        if let Some(attempt) = self.bound_attempt {
            self.response = FirstResponseFeedback::Claimed;
            Some(FakeTtlFeedback { attempt, server_ttl })
        } else {
            self.response = FirstResponseFeedback::Completed;
            None
        }
    }
}
