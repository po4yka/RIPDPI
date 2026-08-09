## Purpose

Define the observable completion contract for Add network-security-config with opportunistic domainEncryption. Add res/xml/networksecurityconfig.xml with <domainEncryption mode="opportunistic"/> as the base config, and point AndroidManifest.xml at it. Opportunistic unlocks platform ECH when both the library and DNS say yes

## ADDED Requirements

### Requirement: REQ-DGN-1786264762917626-001 — Config file exists with the base domainEncryption block on the Android-17+ reso…

The RIPDPI implementation MUST satisfy this portfolio criterion: Config file exists with the base domainEncryption block on the Android-17+ resource path.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Config file exists with the base domainEncryption block on the Android-17+ resource path

### Requirement: REQ-DGN-1786264762917626-002 — Manifest references the config via android:networkSecurityConfig="@xml/networks…

The RIPDPI implementation MUST satisfy this portfolio criterion: Manifest references the config via android:networkSecurityConfig="@xml/networksecurityconfig".

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Manifest references the config via android:networkSecurityConfig="@xml/networksecurityconfig"

### Requirement: REQ-DGN-1786264762917626-003 — App still builds on minSdk targets below Android 17; the new attribute is ignor…

The RIPDPI implementation MUST satisfy this portfolio criterion: App still builds on minSdk targets below Android 17; the new attribute is ignored harmlessly on older versions.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that App still builds on minSdk targets below Android 17; the new attribute is ignored harmlessly on older versions

### Requirement: REQ-DGN-1786264762917626-004 — Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an…

The RIPDPI implementation MUST satisfy this portfolio criterion: Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an ECH config.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an ECH config
