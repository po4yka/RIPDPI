package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubnetFilterDslTest {
    @Test
    fun parses_single_org_term() {
        assertEquals(
            SubnetFilterAst.Org(listOf("hetzner")),
            SubnetFilterDsl.parse("""org("hetzner")"""),
        )
    }

    @Test
    fun parses_each_leaf_combinator() {
        assertEquals(SubnetFilterAst.As(listOf("199524")), SubnetFilterDsl.parse("as(199524)"))
        assertEquals(SubnetFilterAst.Country(listOf("de")), SubnetFilterDsl.parse("""country("de")"""))
        assertEquals(SubnetFilterAst.Subnet(listOf("1.2.3.0/24")), SubnetFilterDsl.parse("""subnet("1.2.3.0/24")"""))
        assertEquals(SubnetFilterAst.Host(listOf("example.org")), SubnetFilterDsl.parse("""host("example.org")"""))
    }

    @Test
    fun parses_or_with_two_groups() {
        assertEquals(
            SubnetFilterAst.Or(
                left = SubnetFilterAst.Org(listOf("a")),
                right = SubnetFilterAst.As(listOf("123")),
            ),
            SubnetFilterDsl.parse("""org("a") || as(123)"""),
        )
    }

    @Test
    fun parses_and_groups_tighter_than_or() {
        assertEquals(
            SubnetFilterAst.Or(
                left =
                    SubnetFilterAst.And(
                        left = SubnetFilterAst.Org(listOf("a")),
                        right = SubnetFilterAst.Country(listOf("de")),
                    ),
                right = SubnetFilterAst.As(listOf("123")),
            ),
            SubnetFilterDsl.parse("""org("a") && country("de") || as(123)"""),
        )
    }

    @Test
    fun parses_parenthesized_group() {
        assertEquals(
            SubnetFilterAst.And(
                left =
                    SubnetFilterAst.Or(
                        left = SubnetFilterAst.Org(listOf("a")),
                        right = SubnetFilterAst.As(listOf("123")),
                    ),
                right = SubnetFilterAst.Country(listOf("de")),
            ),
            SubnetFilterDsl.parse("""(org("a") || as(123)) && country("de")"""),
        )
    }

    @Test
    fun parser_error_position_reported_on_unclosed_paren() {
        var error: SubnetFilterParseError? = null
        try {
            SubnetFilterDsl.parse("org(\"a\"")
            fail("Expected parse error")
        } catch (caught: SubnetFilterParseError) {
            error = caught
        }

        val captured = requireNotNull(error)
        assertEquals(7, captured.position)
        assertTrue(captured.message.orEmpty().contains("position 7"))
    }
}
