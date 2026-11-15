use super::EvolutionAttempt;
use std::io;

pub(super) struct EvolutionFeedbackLedger {
    pub(super) bound_attempt: Option<EvolutionAttempt>,
    send_in_flight: bool,
    response: Option<bool>,
    claimed: bool,
}

impl EvolutionFeedbackLedger {
    pub(super) fn new(bound_attempt: Option<EvolutionAttempt>, completed: bool) -> Self {
        Self { bound_attempt, send_in_flight: false, response: completed.then_some(false), claimed: completed }
    }

    pub(super) fn begin_send(&mut self) -> io::Result<Option<EvolutionAttempt>> {
        if self.send_in_flight {
            return Err(io::Error::other("overlapping outbound evolution sends"));
        }
        self.send_in_flight = true;
        Ok(self.bound_attempt)
    }

    pub(super) fn finish_send(
        &mut self,
        attempt: Option<EvolutionAttempt>,
    ) -> io::Result<Option<(EvolutionAttempt, bool)>> {
        if !self.send_in_flight {
            return Err(io::Error::other("outbound evolution send was not started"));
        }
        self.send_in_flight = false;
        if self.bound_attempt.is_none() {
            self.bound_attempt = attempt;
            if self.claimed {
                return Ok(attempt.map(|attempt| (attempt, false)));
            }
        }
        Ok(self.claim())
    }

    pub(super) fn finish_response(&mut self, success: bool) -> Option<(EvolutionAttempt, bool)> {
        if success {
            self.response.get_or_insert(true);
        } else {
            self.mark_neutral_response();
        }
        self.claim()
    }

    pub(super) fn mark_neutral_response(&mut self) {
        self.response = Some(false);
    }

    fn claim(&mut self) -> Option<(EvolutionAttempt, bool)> {
        if self.claimed {
            return None;
        }
        let success = self.response?;
        let Some(attempt) = self.bound_attempt else {
            // No current receipt can own this response. Close it now so a
            // future send cannot inherit the result of an earlier plain send.
            if !self.send_in_flight {
                self.claimed = true;
            }
            return None;
        };
        self.claimed = true;
        Some((attempt, success))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn response_before_receipt_uses_exact_attempt_once() {
        for success in [false, true] {
            let attempt = EvolutionAttempt::new(2, 7);
            let mut ledger = EvolutionFeedbackLedger::new(None, false);
            assert_eq!(ledger.begin_send().unwrap(), None);
            assert_eq!(ledger.finish_response(success), None);
            assert_eq!(ledger.finish_send(Some(attempt)).unwrap(), Some((attempt, success)));
            assert_eq!(ledger.finish_response(!success), None);
            assert_eq!(ledger.begin_send().unwrap(), Some(attempt));
            assert_eq!(ledger.finish_send(Some(attempt)).unwrap(), None);
        }
    }
    #[test]
    fn unowned_response_cannot_credit_a_future_send() {
        for overlap in [false, true] {
            let mut ledger = EvolutionFeedbackLedger::new(None, false);
            if overlap {
                ledger.begin_send().unwrap();
            }
            assert_eq!(ledger.finish_response(true), None);
            if overlap {
                assert_eq!(ledger.finish_send(None).unwrap(), None);
            }
            ledger.begin_send().unwrap();
            let attempt = EvolutionAttempt::new(2, 9);
            assert_eq!(ledger.finish_send(Some(attempt)).unwrap(), Some((attempt, false)));
            assert_eq!(ledger.finish_response(true), None);
        }
    }

    #[test]
    fn neutral_before_receipt_replaces_unclaimed_success() {
        let mut ledger = EvolutionFeedbackLedger::new(None, false);
        ledger.begin_send().unwrap();
        assert_eq!(ledger.finish_response(true), None);
        ledger.mark_neutral_response();
        assert_eq!(ledger.finish_response(true), None);
        let attempt = EvolutionAttempt::new(2, 10);
        assert_eq!(ledger.finish_send(Some(attempt)).unwrap(), Some((attempt, false)));
        assert_eq!(ledger.finish_response(true), None);
    }
}
