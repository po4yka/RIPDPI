internal fun verifiedHttpsDownloadProfile() =
    DiagnosticsProfileDefinition(
        id = "verified-https-download",
        name = "Verified HTTPS download",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
    )
