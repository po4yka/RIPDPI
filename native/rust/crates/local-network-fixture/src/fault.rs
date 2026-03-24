use std::sync::{Arc, Mutex};

use crate::types::{FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget};

#[derive(Clone)]
pub struct FaultController {
    inner: Arc<Mutex<Vec<FixtureFaultSpec>>>,
}

impl FaultController {
    pub(crate) fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(Vec::new())) }
    }

    pub fn set(&self, spec: FixtureFaultSpec) {
        if let Ok(mut faults) = self.inner.lock() {
            faults.push(spec);
        }
    }

    pub fn clear(&self) {
        if let Ok(mut faults) = self.inner.lock() {
            faults.clear();
        }
    }

    pub fn snapshot(&self) -> Vec<FixtureFaultSpec> {
        self.inner.lock().map(|faults| faults.clone()).unwrap_or_default()
    }

    pub(crate) fn take_matching<F>(&self, target: FixtureFaultTarget, predicate: F) -> Option<FixtureFaultSpec>
    where
        F: Fn(&FixtureFaultOutcome) -> bool,
    {
        let mut faults = self.inner.lock().ok()?;
        let index = faults.iter().position(|fault| fault.target == target && predicate(&fault.outcome))?;
        let fault = faults[index].clone();
        if fault.scope == FixtureFaultScope::OneShot {
            faults.remove(index);
        }
        Some(fault)
    }
}
