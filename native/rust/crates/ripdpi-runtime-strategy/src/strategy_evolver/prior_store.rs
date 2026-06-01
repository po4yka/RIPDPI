use super::StrategyEvolver;
use super::shared_priors::{ApplyError, SHARED_PRIORS_PUB_KEY, apply_priors, canonical_combo_hash};
use super::thompson_sampling::BetaParams;
use super::types::StrategyCombo;

impl StrategyEvolver {
    /// Verify a signed shared-priors bundle and load its posteriors into the
    /// evolver's prior store. Fail-secure: any verification or parse error
    /// returns `Err` without touching existing prior state.
    /// On success returns the number of records loaded.
    ///
    /// The priors live in a parallel store consulted by Thompson-style
    /// scoring; the UCB1 selection path is unaffected, so
    /// existing field data continues to dominate ranking decisions.
    pub fn apply_shared_priors(
        &mut self,
        manifest_bytes: &[u8],
        priors_bytes: &[u8],
        public_key: &[u8; 32],
    ) -> Result<usize, ApplyError> {
        let applied = apply_priors(manifest_bytes, priors_bytes, public_key)?;
        let count = applied.priors.len();
        // Atomic replace: a successful refresh swaps the store wholesale.
        // Field data wins: the local `combos` map is not touched here, and
        // the merge happens at consumption time.
        self.shared_priors = applied
            .priors
            .into_iter()
            .map(|(hash, prior)| (hash, BetaParams { alpha: prior.alpha, beta: prior.beta }))
            .collect();
        Ok(count)
    }

    /// Production entry point for shared-priors application. Uses the
    /// embedded release public key; on a build with no production key
    /// (`is_production_key_set() == false`), this always returns
    /// `Err(ApplyError::Manifest(ManifestError::NoProductionKey))`.
    pub fn apply_shared_priors_with_embedded_key(
        &mut self,
        manifest_bytes: &[u8],
        priors_bytes: &[u8],
    ) -> Result<usize, ApplyError> {
        self.apply_shared_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
    }

    /// Returns the shared Beta prior for `combo`, if loaded. Used by
    /// diagnostics and Thompson-style scoring; the production UCB1 path does
    /// not consult this map.
    pub fn shared_prior_for(&self, combo: &StrategyCombo) -> Option<&BetaParams> {
        let hash = canonical_combo_hash(combo);
        self.shared_priors.get(&hash)
    }

    /// Number of priors currently loaded. Zero on a freshly-constructed
    /// evolver and after a verification failure.
    pub fn shared_priors_len(&self) -> usize {
        self.shared_priors.len()
    }
}
