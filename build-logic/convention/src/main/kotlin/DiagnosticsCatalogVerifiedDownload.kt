// The manual verified HTTPS download: the user supplies the URL and hash, so the profile carries no targets.
internal fun verifiedHttpsDownloadProfile() =
    DiagnosticsProfileDefinition(
        id = "verified-https-download",
        name = "Verified HTTPS download",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
    )
