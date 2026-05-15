/// Integration test: asserts the zapret QUIC/UDP desync taxonomy spike note
/// exists and contains the required section headers.
///
/// This test makes `just lint` / `cargo test` catch regressions where the
/// spike document is accidentally deleted or its mandatory sections are removed.
#[test]
fn spike_doc_exists_and_has_required_sections() {
    let spike_path = concat!(env!("CARGO_MANIFEST_DIR"), "/docs/spikes/zapret-quic-desync-taxonomy-2026-05-16.md");

    let content =
        std::fs::read_to_string(spike_path).unwrap_or_else(|e| panic!("spike note not found at {spike_path}: {e}"));

    let required_sections = ["## Primitives", "## Mapping to RIPDPI arms", "## Recommendation", "## Source pointers"];

    for section in required_sections {
        assert!(content.contains(section), "spike note is missing required section: {section}");
    }
}
