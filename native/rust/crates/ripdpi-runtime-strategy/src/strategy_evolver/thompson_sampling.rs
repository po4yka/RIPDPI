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
        let total = self.alpha + self.beta;
        if total.is_infinite() && self.alpha.is_finite() && self.beta.is_finite() {
            // Scale only overflowing sums so ordinary priors keep their rounding.
            let scale = self.alpha.abs().max(self.beta.abs());
            let alpha = self.alpha / scale;
            let beta = self.beta / scale;
            alpha / (alpha + beta)
        } else {
            self.alpha / total
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn beta_mean_preserves_positive_finite_ratios() {
        let smallest = f64::from_bits(1);
        for (alpha, beta, expected) in [
            (3.0, 1.0, 0.75),
            (1e308, 1e308, 0.5),
            (f64::MAX, f64::MAX, 0.5),
            (1.6e308, 8e307, 2.0 / 3.0),
            (8e307, 1.6e308, 1.0 / 3.0),
            (smallest, smallest, 0.5),
            (smallest, 3.0 * smallest, 0.25),
        ] {
            let mean = BetaParams { alpha, beta }.mean();
            assert!((mean - expected).abs() <= f64::EPSILON, "alpha={alpha}, beta={beta}: {mean}");
        }
    }

    #[test]
    fn beta_params_default_is_uniform() {
        let p = BetaParams::default();
        assert_eq!(p.alpha, 1.0);
        assert_eq!(p.beta, 1.0);
        // Uniform prior: mean = 0.5.
        assert!((p.mean() - 0.5).abs() < f64::EPSILON);
    }
}
