mod packet_test_support;

use std::{fs, path::PathBuf};

use ciadpi_packets::OracleRng;
use packet_test_support::{exercise_packets_input, mutate_bytes_like_c};

fn corpus_paths() -> Vec<PathBuf> {
    let corpus_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/corpus/packets");
    let mut paths = fs::read_dir(&corpus_dir)
        .expect("packet corpus directory should exist")
        .map(|entry| {
            entry
                .expect("packet corpus entry should be readable")
                .path()
        })
        .filter(|path| {
            path.file_name()
                .is_some_and(|name| !name.to_string_lossy().starts_with('.'))
        })
        .collect::<Vec<_>>();
    paths.sort();
    assert!(
        !paths.is_empty(),
        "packet corpus directory should contain at least one seed"
    );
    paths
}

#[test]
#[ignore = "mutation smoke over the packet corpus for make fuzz-packets"]
fn corpus_mutation_smoke_matches_c_lane() {
    let mut rng = OracleRng::seeded(1);

    for path in corpus_paths() {
        let seed = fs::read(&path).unwrap_or_else(|error| {
            panic!("failed to read {}: {error}", path.display());
        });

        exercise_packets_input(&seed);
        for _ in 0..512 {
            let mut mutant = seed.clone();
            mutate_bytes_like_c(&mut mutant, &mut rng);
            exercise_packets_input(&mutant);
        }
    }
}
