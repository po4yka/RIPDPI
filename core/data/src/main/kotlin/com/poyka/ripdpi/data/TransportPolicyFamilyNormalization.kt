package com.poyka.ripdpi.data

import java.util.Locale

fun normalizeStrategyFamilyToTcpFamily(strategyFamily: String?): TcpFamily {
    val normalized =
        strategyFamily
            ?.trim()
            ?.lowercase(Locale.US)
            ?.removePrefix("circular_")
            .orEmpty()

    return when (normalized) {
        "rec_pre_sni" -> TcpFamily.REC_PRE_SNI

        "rec_mid_sni" -> TcpFamily.REC_MID_SNI

        "seg_post_sni" -> TcpFamily.SEG_POST_SNI

        "two_phase_send" -> TcpFamily.TWO_PHASE_SEND

        "tlsrec",
        "tlsrec_split",
        "tlsrec_seqovl",
        "tlsrec_multidisorder",
        "tlsrec_disorder",
        "tlsrec_fake",
        "tlsrandrec",
        -> TcpFamily.REC_PRE_SNI

        "seg_pre_sni" -> TcpFamily.SEG_PRE_SNI

        "seg_mid_sni" -> TcpFamily.SEG_MID_SNI

        "split",
        "seqovl",
        "multidisorder",
        "disorder",
        "oob",
        "disoob",
        "fake",
        "fakedsplit",
        "fakeddisorder",
        "hostfake",
        "ipfrag2",
        "fakerst",
        "fake_flags",
        "fake_approx",
        -> TcpFamily.SEG_PRE_SNI

        else -> TcpFamily.NONE
    }
}
