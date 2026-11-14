use super::*;

#[test]
fn boundary_windows_contain_each_candidate_once_for_every_bias() {
    for (min_ttl, max_ttl) in [(1, 1), (1, 2), (1, 255), (254, 255), (255, 255)] {
        for delta in [-1, 0, 1] {
            for seed in min_ttl..=max_ttl {
                let config = AutoTtlConfig { delta, min_ttl, max_ttl };
                let candidates = candidate_order(config, Some(seed));
                assert_eq!(candidates[0], seed, "{config:?}");
                let mut sorted = candidates;
                sorted.sort_unstable();
                assert_eq!(sorted, (min_ttl..=max_ttl).collect::<Vec<_>>(), "{config:?}, seed={seed}");
            }
        }
    }
}

#[test]
fn negative_bias_at_max_ttl_wraps_without_repeating_the_seed() {
    assert_eq!(candidate_order(AutoTtlConfig { delta: -1, min_ttl: 254, max_ttl: 255 }, Some(255)), vec![255, 254]);
}

#[test]
fn neutral_bias_stops_after_the_last_candidate() {
    assert_eq!(candidate_order(AutoTtlConfig { delta: 0, min_ttl: 254, max_ttl: 255 }, Some(254)), vec![254, 255]);
}
