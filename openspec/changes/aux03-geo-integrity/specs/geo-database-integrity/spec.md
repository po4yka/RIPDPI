## Purpose

Keep supported geo rules correct and preserve installed working databases when input validation fails.

## ADDED Requirements

### Requirement: REQ-GEO-REGEX — Preserve supported regular expressions

The implementation MUST accept supported Go literal quoting and preserve pattern flags. It MUST reject an invalid or unsupported expression before a database is published.

#### Scenario: Quoted literal and invalid expression

- **WHEN** a category contains `^\Qexample.com\E$` or an invalid expression
- **THEN** the quoted literal matches `example.com`, while the invalid expression rejects the new database and preserves the working snapshot.

### Requirement: REQ-GEO-MMDB — Reject corrupted MMDB records

The implementation MUST reject malformed search trees and referenced data records before activation. A lookup error MUST NOT become a decisive negative geo match.

#### Scenario: Invalid data pointer

- **WHEN** a replacement MMDB has a valid metadata section and an invalid data pointer
- **THEN** load or reload reports an error and preserves the working database.

#### Scenario: Non-string key in an unknown map

- **WHEN** a referenced MMDB record contains a non-string key in an unknown nested map
- **THEN** admission, load and reload reject the record, while a valid UTF-8 string-key control remains accepted and a failed reload preserves the working pair.

### Requirement: REQ-GEO-ADMISSION — Validate complete assets before installation

The implementation MUST validate the complete selected format before either local import or download replaces a target. File names and format markers MUST NOT serve as proof of validity. Failure or cancellation before commit MUST preserve the target and remove temporary files.

#### Scenario: Malformed marker and wrong asset kind

- **WHEN** a file contains only a plausible marker or has the wrong selected format
- **THEN** installation fails and the working target bytes remain unchanged.

### Requirement: REQ-GEO-CASE — Preserve regex case flags

The implementation MUST normalize candidate hosts as required by the supported upstream matcher and MUST NOT force case-insensitive matching of the pattern.

#### Scenario: Uppercase pattern

- **WHEN** `^API\.example$` is tested against normalized `api.example`
- **THEN** the expression does not match unless it explicitly enables case-insensitive matching.

### Requirement: REQ-GEO-SUFFIX — Preserve subdomain-only suffixes

The implementation MUST distinguish a leading-dot sing-box suffix from a root-domain rule.

#### Scenario: Dotted suffix

- **WHEN** a category contains only `.example.com` as a sing-box suffix
- **THEN** it matches `a.example.com` and does not match `example.com` or `notexample.com`.

### Requirement: REQ-GEO-PROVENANCE — Report update status for the installed source

The implementation MUST associate update status with the installed file and source. Local import, missing or invalid files, and source changes MUST NOT reuse an unrelated release tag as proof that a file is current.

#### Scenario: Local replacement after remote installation

- **WHEN** a remote database tagged T is replaced by a local file and the remote tag remains T
- **THEN** the next check verifies the current file and source before it reports that the remote asset is current.
