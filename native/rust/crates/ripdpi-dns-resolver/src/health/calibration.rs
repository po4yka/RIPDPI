//! Offline fit of next-exchange utility from pre-exchange health features.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use rand::{RngExt, SeedableRng, rngs::StdRng};

const MAX_SAMPLES: usize = 128;
const MIN_SAMPLES: usize = 64;
const FIT_INTERVAL: u64 = 32;
const POPULATION: usize = 12;
const GENERATIONS: usize = 20;
const IMPROVEMENT: f64 = 0.0001;
const MAX_IDLE: Duration = Duration::from_secs(600);

#[derive(Clone, Copy, Debug, PartialEq)]
pub(super) struct Weights(pub(super) [f64; 3]);

impl Default for Weights {
    fn default() -> Self {
        Self([0.55, 0.25, 0.20])
    }
}

impl Weights {
    pub(super) fn score(self, features: [f64; 3]) -> f64 {
        self.0.iter().zip(features).map(|(weight, feature)| weight * feature).sum()
    }

    fn bounded(success: f64, latency: f64) -> Self {
        let success = success.clamp(0.45, 0.70);
        let latency = latency.clamp(0.10, (0.80 - success).min(0.30));
        Self([success, latency, 1.0 - success - latency])
    }
}

#[derive(Clone, Copy)]
struct Sample {
    features: [f64; 3],
    utility: f64,
}

pub(super) struct Calibration {
    samples: VecDeque<(Instant, Sample)>,
    weights: Weights,
    generation: u64,
    last_sample: Instant,
    incarnation: u64,
    fitted_at: Option<Instant>,
    /// A scheduled fit lost to a concurrent outcome reruns once on the next outcome, not 32 outcomes later.
    retry_fit: bool,
}

pub(super) struct FitBatch {
    samples: Vec<Sample>,
    previous: Weights,
    pub(super) generation: u64,
    incarnation: u64,
}

impl Calibration {
    pub(super) fn new(now: Instant, incarnation: u64) -> Self {
        Self {
            samples: VecDeque::new(),
            weights: Weights::default(),
            generation: 0,
            last_sample: now,
            incarnation,
            fitted_at: None,
            retry_fit: false,
        }
    }

    pub(super) fn weights(&self, now: Instant) -> Weights {
        if now.saturating_duration_since(self.last_sample) > MAX_IDLE
            || self.fitted_at.is_some_and(|fitted| now.saturating_duration_since(fitted) > MAX_IDLE)
        {
            Weights::default()
        } else {
            self.weights
        }
    }

    pub(super) fn record(
        &mut self,
        features: [f64; 3],
        success: bool,
        latency_ms: u64,
        now: Instant,
    ) -> Option<FitBatch> {
        if now.saturating_duration_since(self.last_sample) > MAX_IDLE {
            self.samples.clear();
            self.weights = Weights::default();
        }
        self.last_sample = now;
        self.generation = self.generation.wrapping_add(1);
        while self.samples.front().is_some_and(|(recorded, _)| now.saturating_duration_since(*recorded) > MAX_IDLE) {
            self.samples.pop_front();
        }
        if self.samples.len() == MAX_SAMPLES {
            self.samples.pop_front();
        }
        let utility = if success { 1.0 - 0.25 * (latency_ms as f64 / 2000.0).clamp(0.0, 1.0) } else { 0.0 };
        self.samples.push_back((now, Sample { features, utility }));
        if self.samples.len() < MIN_SAMPLES {
            self.weights = Weights::default();
            self.fitted_at = None;
        }
        let retry = std::mem::take(&mut self.retry_fit);
        let scheduled = retry || self.generation.is_multiple_of(FIT_INTERVAL);
        (self.samples.len() >= MIN_SAMPLES && scheduled).then(|| FitBatch {
            samples: self.samples.iter().map(|(_, sample)| *sample).collect(),
            previous: self.weights,
            generation: self.generation,
            incarnation: self.incarnation,
        })
    }

    pub(super) fn apply(&mut self, batch: &FitBatch, candidate: Weights) {
        if self.incarnation != batch.incarnation {
            return;
        }
        if self.generation == batch.generation {
            self.weights = candidate;
            self.fitted_at = (candidate != Weights::default()).then_some(self.last_sample);
        } else if batch.generation.is_multiple_of(FIT_INTERVAL) {
            // Only an interval fit retries, so contention costs at most two fits per interval.
            self.retry_fit = true;
        }
    }
}

impl FitBatch {
    /// DE/rand/1/bin, at most 252 training evaluations and three validation evaluations.
    pub(super) fn fit(&self) -> Option<Weights> {
        let split = self.samples.len() * 3 / 4;
        let (training, validation) = self.samples.split_at(split);
        if !has_contrast(training) || !has_contrast(validation) {
            return (self.previous != Weights::default()).then_some(Weights::default());
        }
        let mut rng = StdRng::seed_from_u64(self.generation);
        let mut population = [Weights::default(); POPULATION];
        population[1] = self.previous;
        for entry in population.iter_mut().skip(2) {
            *entry = Weights::bounded(rng.random_range(0.45..=0.70), rng.random_range(0.10..=0.30));
        }
        let mut losses = population.map(|weights| loss(weights, training));
        for _ in 0..GENERATIONS {
            let previous = population;
            for index in 0..POPULATION {
                let mut donors = [0; POPULATION - 1];
                for (slot, donor) in (0..POPULATION).filter(|candidate| *candidate != index).enumerate() {
                    donors[slot] = donor;
                }
                for donor in 0..3 {
                    let next = rng.random_range(donor..donors.len());
                    donors.swap(donor, next);
                }
                let forced = rng.random_range(0..2);
                let mut trial = previous[index].0;
                for (dimension, value) in trial.iter_mut().enumerate().take(2) {
                    if dimension == forced || rng.random::<f64>() < 0.9 {
                        *value = previous[donors[0]].0[dimension]
                            + 0.8 * (previous[donors[1]].0[dimension] - previous[donors[2]].0[dimension]);
                    }
                }
                let trial = Weights::bounded(trial[0], trial[1]);
                let trial_loss = loss(trial, training);
                if trial_loss < losses[index] {
                    population[index] = trial;
                    losses[index] = trial_loss;
                }
            }
        }
        let best = losses.iter().enumerate().min_by(|a, b| a.1.total_cmp(b.1)).map(|(index, _)| population[index])?;
        let validation_loss = loss(best, validation);
        let default_loss = loss(Weights::default(), validation);
        let previous_loss = loss(self.previous, validation);
        if validation_loss + IMPROVEMENT < default_loss && validation_loss + IMPROVEMENT < previous_loss {
            Some(best)
        } else {
            (default_loss + IMPROVEMENT < previous_loss).then_some(Weights::default())
        }
    }
}

fn has_contrast(samples: &[Sample]) -> bool {
    samples.iter().any(|sample| sample.utility == 0.0) && samples.iter().any(|sample| sample.utility > 0.0)
}

fn loss(weights: Weights, samples: &[Sample]) -> f64 {
    samples.iter().map(|sample| (weights.score(sample.features) - sample.utility).powi(2)).sum::<f64>()
        / samples.len() as f64
}

#[cfg(test)]
mod tests;
