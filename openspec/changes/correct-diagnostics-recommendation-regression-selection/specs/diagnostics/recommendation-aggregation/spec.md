# Recommendation and home analysis evidence

## ADDED Requirements

### Requirement: REQ-DGN-1790433260349809-FREEZE

The strategy recommendation MUST treat `tcp_freeze_after_threshold` as threshold blocking when other evidence makes the recommendation actionable.

#### Scenario: Raw path has a freeze and a real target failure

- **GIVEN** a TCP freeze after the threshold and a failed domain reachability probe
- **WHEN** the current TCP family is `fake`
- **THEN** the strategy recommendation selects the threshold-block pattern and the `disorder` family

### Requirement: REQ-DGN-1790433260349809-PREVIOUS

Home analysis MUST compare a run with the last completed run in this process. It MUST NOT choose a predecessor by session count or use the current run as its own predecessor.

#### Scenario: Older run has more sessions

- **GIVEN** an older completed run with more sessions and a newer completed run with fewer sessions
- **WHEN** another home run finishes
- **THEN** its regression delta uses the newer completed run

#### Scenario: Session counts tie

- **GIVEN** two completed runs with the same session count
- **WHEN** another home run finishes
- **THEN** its predecessor is the one completed last

### Requirement: REQ-DGN-1790433260349809-DNS

Resolver recommendation MUST skip a secondary DNS trigger that has a healthy primary result for the same target and MUST continue to a later independent trigger.

#### Scenario: A later primary trigger is actionable

- **GIVEN** a secondary trigger that is suppressed by a healthy primary result and a later primary trigger on another target
- **WHEN** an encrypted candidate has healthy answers for the later trigger
- **THEN** the engine recommends that candidate for the later trigger
