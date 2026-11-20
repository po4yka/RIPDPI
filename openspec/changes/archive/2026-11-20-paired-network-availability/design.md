## Context

`SelectiveMatrixEvidence.kt` and the selective matrix UI models hold one session's results. Stored sessions do not record a target set revision or a configuration revision, so two sessions cannot be checked for comparability today.

## Goals / Non-Goals

Goal: a descriptive per-target comparison of two sessions the user chose, with clear reasons when they cannot be compared.

Non-goals: automatic network switching, running both legs in one action, turning off VPN protection, allowlist or ASN inference, upload.

## Decisions

Revisions. Each matrix session stores three values: a hash of the ordered target set, a hash of the probe parameters, and the app configuration revision. A session saved before this change has no revisions and cannot be paired.

Eligibility. Two sessions are comparable when all three revisions match, their transport labels differ (for example Wi-Fi and cellular), and the second leg started no more than 30 minutes after the first. Otherwise the result is `comparison unavailable` and names each mismatch. The 30-minute limit is one constant in the comparison code.

Leg validity. A leg is invalid when the selected underlying network changed during it. An invalid leg is never replaced by another session.

Rows. A row counts as path-specific only when it has a verified TLS result and a complete bounded payload result on one leg and a failure on the other. Rows that failed on both legs, failed their control, or were not measured are inconclusive and stay out of the denominator.

Identity. The comparison stores transport labels and timestamps only. It does not store SIM, SSID, or public IP identifiers.

Export. The redacted export keeps per-leg stage outcomes and repetition counts. It does not claim permitted prefixes, ASN-wide availability, or nationwide results.

## Migration Plan

Revisions are new optional fields on stored sessions. Older sessions stay readable and are shown as not pairable.
