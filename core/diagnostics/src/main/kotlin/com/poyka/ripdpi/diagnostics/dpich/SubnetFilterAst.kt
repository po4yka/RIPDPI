package com.poyka.ripdpi.diagnostics.dpich

sealed interface SubnetFilterAst {
    object Empty : SubnetFilterAst

    data class Org(
        val args: List<String>,
    ) : SubnetFilterAst

    data class As(
        val args: List<String>,
    ) : SubnetFilterAst

    data class Country(
        val args: List<String>,
    ) : SubnetFilterAst

    data class Subnet(
        val args: List<String>,
    ) : SubnetFilterAst

    data class Host(
        val args: List<String>,
    ) : SubnetFilterAst

    data class And(
        val left: SubnetFilterAst,
        val right: SubnetFilterAst,
    ) : SubnetFilterAst

    data class Or(
        val left: SubnetFilterAst,
        val right: SubnetFilterAst,
    ) : SubnetFilterAst
}
