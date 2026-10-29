//! Beta-Bernoulli posterior parameters for strategy arms.
//!
//! [`BetaParams`] carries the shared-prior posterior that the UCB1 selection
//! path blends with local arm statistics.

// ---------------------------------------------------------------------------
// BetaParams
// ---------------------------------------------------------------------------

/// Beta distribution parameters for a single arm.
///
/// The posterior starts at `Beta(1, 1)` (uniform prior) and is updated by
/// adding observed successes to `alpha` and failures to `beta`.
#[derive(Debug, Clone, Copy)]
pub struct BetaParams {
    /// α = prior + success count. Starts at 1 (uniform prior).
    pub alpha: f64,
    /// β = prior + failure count. Starts at 1 (uniform prior).
    pub beta: f64,
}

impl Default for BetaParams {
    fn default() -> Self {
        Self { alpha: 1.0, beta: 1.0 }
    }
}

impl BetaParams {
    /// Return the posterior mean, useful for diagnostics.
    pub fn mean(&self) -> f64 {
        self.alpha / (self.alpha + self.beta)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn beta_params_default_is_uniform() {
        let p = BetaParams::default();
        assert_eq!(p.alpha, 1.0);
        assert_eq!(p.beta, 1.0);
        // Uniform prior: mean = 0.5.
        assert!((p.mean() - 0.5).abs() < f64::EPSILON);
    }
}
