internal object DiagnosticsCatalogDefinitions {
    private val assembler =
        DiagnosticsCatalogAssembler(
            packSource = DefaultDiagnosticsCatalogPackSource,
            profileSource = DefaultDiagnosticsCatalogProfileSource,
            validator = DiagnosticsCatalogValidator(),
            renderer = DiagnosticsCatalogJsonRenderer(),
        )

    internal fun renderCatalog(): String = assembler.renderCatalog()
}
