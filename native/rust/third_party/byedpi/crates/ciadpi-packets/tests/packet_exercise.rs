mod packet_test_support;

use packet_test_support::{corpus_cases, exercise_packets_input};

#[test]
fn corpus_inputs_do_not_panic() {
    for (name, seed) in corpus_cases() {
        let result = std::panic::catch_unwind(|| exercise_packets_input(&seed));

        assert!(result.is_ok(), "exercise helper panicked for {name}");
    }
}
