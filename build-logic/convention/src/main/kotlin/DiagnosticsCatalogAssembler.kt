internal class DiagnosticsCatalogAssembler(
    private val packSource: DiagnosticsCatalogPackSource,
    private val profileSource: DiagnosticsCatalogProfileSource,
    private val legalSafetyRegistry: DiagnosticsCatalogLegalSafetyRegistry,
    private val validator: DiagnosticsCatalogValidator,
    private val renderer: DiagnosticsCatalogJsonRenderer,
) {
    fun renderCatalog(): String {
        val packs = packSource.load()
        val profiles = profileSource.load(DiagnosticsCatalogIndex(packs))
        val catalog =
            legalSafetyRegistry.annotate(
                DiagnosticsCatalog(
                    packs = packs,
                    profiles = profiles,
                ),
            )
        validator.validate(catalog)
        return renderer.render(catalog).also(validator::validateGeneratedArtifacts)
    }
}
