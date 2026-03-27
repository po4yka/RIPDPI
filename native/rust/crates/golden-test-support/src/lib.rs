use std::env;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};
use similar::TextDiff;

pub fn canonicalize_json(input: &str) -> Result<String, serde_json::Error> {
    let value: Value = serde_json::from_str(input)?;
    let sorted = sort_json_value(value);
    serde_json::to_string_pretty(&sorted)
}

pub fn canonicalize_json_with<F>(input: &str, scrub: F) -> Result<String, serde_json::Error>
where
    F: FnOnce(&mut Value),
{
    let mut value: Value = serde_json::from_str(input)?;
    scrub(&mut value);
    let sorted = sort_json_value(value);
    serde_json::to_string_pretty(&sorted)
}

pub fn normalize_text(value: &str) -> String {
    let normalized = value.replace("\r\n", "\n");
    if normalized.ends_with('\n') {
        normalized
    } else {
        format!("{normalized}\n")
    }
}

pub fn assert_text_golden(manifest_dir: &str, relative_path: &str, actual: &str) {
    let golden_path = Path::new(manifest_dir).join(relative_path);
    let actual = normalize_text(actual);

    if env::var_os("RIPDPI_BLESS_GOLDENS").is_some() {
        if let Some(parent) = golden_path.parent() {
            fs::create_dir_all(parent).expect("create golden parent");
        }
        fs::write(&golden_path, &actual).expect("write golden fixture");
        return;
    }

    let expected = fs::read_to_string(&golden_path)
        .unwrap_or_else(|err| panic!("read golden fixture {}: {err}", golden_path.display()));
    let expected = normalize_text(&expected);
    if expected == actual {
        return;
    }

    write_failure_artifacts(&golden_path, &expected, &actual).expect("write golden diff artifacts");
    panic!("golden mismatch for {}", golden_path.display());
}

fn sort_json_value(value: Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(items.into_iter().map(sort_json_value).collect()),
        Value::Object(map) => {
            let mut keys = map.keys().cloned().collect::<Vec<_>>();
            keys.sort();
            let mut sorted = Map::with_capacity(keys.len());
            for key in keys {
                if let Some(value) = map.get(&key) {
                    sorted.insert(key, sort_json_value(value.clone()));
                }
            }
            Value::Object(sorted)
        }
        other => other,
    }
}

fn write_failure_artifacts(golden_path: &Path, expected: &str, actual: &str) -> io::Result<()> {
    let artifact_dir = artifact_root();
    fs::create_dir_all(&artifact_dir)?;

    let stem = golden_path
        .strip_prefix(workspace_root(golden_path))
        .unwrap_or(golden_path)
        .to_string_lossy()
        .replace(['/', '\\'], "__");
    let expected_path = artifact_dir.join(format!("{stem}.expected"));
    let actual_path = artifact_dir.join(format!("{stem}.actual"));
    let diff_path = artifact_dir.join(format!("{stem}.diff"));

    fs::write(expected_path, expected)?;
    fs::write(actual_path, actual)?;
    let diff = TextDiff::from_lines(expected, actual).unified_diff().header("expected", "actual").to_string();
    fs::write(diff_path, diff)?;
    Ok(())
}

fn artifact_root() -> PathBuf {
    env::var_os("RIPDPI_GOLDEN_ARTIFACT_DIR").map_or_else(|| PathBuf::from("target/golden-diffs"), PathBuf::from)
}

fn workspace_root(path: &Path) -> PathBuf {
    path.ancestors()
        .find(|ancestor| ancestor.join("Cargo.toml").exists() && ancestor.ends_with("native/rust"))
        .map_or_else(|| PathBuf::from("."), PathBuf::from)
}

/// Returns the path to a file inside the shared `contract-fixtures/` directory
/// at the repository root. Navigates from the Cargo workspace root (`native/rust/`)
/// up to the repo root, then into `contract-fixtures/`.
pub fn contract_fixture_path(name: &str) -> PathBuf {
    let workspace = cargo_workspace_root();
    // native/rust/ -> repo root
    let repo_root = workspace.parent().and_then(Path::parent).unwrap_or(&workspace);
    repo_root.join("contract-fixtures").join(name)
}

/// Asserts that `actual` matches the shared contract fixture at
/// `contract-fixtures/<name>`. Blesses the fixture when
/// `RIPDPI_BLESS_GOLDENS` is set.
pub fn assert_contract_fixture(name: &str, actual: &str) {
    let fixture_path = contract_fixture_path(name);
    let actual = normalize_text(actual);

    if env::var_os("RIPDPI_BLESS_GOLDENS").is_some() {
        if let Some(parent) = fixture_path.parent() {
            fs::create_dir_all(parent).expect("create contract-fixtures parent");
        }
        fs::write(&fixture_path, &actual).expect("write contract fixture");
        return;
    }

    let expected = fs::read_to_string(&fixture_path)
        .unwrap_or_else(|err| panic!("read contract fixture {}: {err}", fixture_path.display()));
    let expected = normalize_text(&expected);
    if expected == actual {
        return;
    }

    write_failure_artifacts(&fixture_path, &expected, &actual).expect("write contract fixture diff");
    panic!("contract fixture mismatch for {}", fixture_path.display());
}

/// Extracts all JSON field key paths from a `serde_json::Value`, recursively.
/// Returns a sorted list of dot-separated paths (e.g., `["health", "tunnelStats.rxBytes"]`).
pub fn extract_field_paths(value: &Value) -> Vec<String> {
    let mut paths = Vec::new();
    collect_field_paths(value, "", &mut paths);
    paths.sort();
    paths
}

fn collect_field_paths(value: &Value, prefix: &str, paths: &mut Vec<String>) {
    if let Value::Object(map) = value {
        for (key, child) in map {
            let path = if prefix.is_empty() { key.clone() } else { format!("{prefix}.{key}") };
            match child {
                Value::Object(_) => collect_field_paths(child, &path, paths),
                Value::Array(items) => {
                    // For arrays of objects, extract the element schema from the first item.
                    if let Some(first) = items.first() {
                        if first.is_object() {
                            collect_field_paths(first, &format!("{path}[]"), paths);
                        } else {
                            paths.push(format!("{path}[]"));
                        }
                    } else {
                        paths.push(format!("{path}[]"));
                    }
                }
                _ => paths.push(path),
            }
        }
    }
}

fn cargo_workspace_root() -> PathBuf {
    let manifest_dir = env::var_os("CARGO_MANIFEST_DIR").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    manifest_dir
        .ancestors()
        .find(|ancestor| {
            ancestor.join("Cargo.toml").exists()
                && ancestor.file_name().is_some_and(|name| name == "rust")
                && ancestor.parent().is_some_and(|parent| parent.file_name().is_some_and(|name| name == "native"))
        })
        .map_or(manifest_dir.clone(), PathBuf::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonicalize_json_sorts_nested_keys() {
        let actual = canonicalize_json(r#"{"b":1,"a":{"d":4,"c":3}}"#).expect("canonical json");
        assert_eq!(actual, "{\n  \"a\": {\n    \"c\": 3,\n    \"d\": 4\n  },\n  \"b\": 1\n}");
    }

    #[test]
    fn normalize_text_rewrites_crlf_and_appends_terminal_newline() {
        assert_eq!(normalize_text("a\r\nb"), "a\nb\n");
    }
}
