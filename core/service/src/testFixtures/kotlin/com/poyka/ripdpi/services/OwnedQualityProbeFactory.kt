package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.XrayRuntimeOwner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Test-only trust for the freshly created owned peer certificate; no production trust or hostname bypass. */
object OwnedQualityProbeFactory {
    /** Trust applies only to the two owned HTTPS controls, with normal hostname checks. */
    fun http(
        certificateBase64: () -> String,
        port: () -> Int,
    ): CandidateHttpPayloadProbe =
        CandidateHttpPayloadProbe(
            OkHttpRelayTcpProbe(),
            System::nanoTime,
            strictProbe = { control, timeout ->
                val url = control.url.toHttpUrl()
                if (url.host in setOf("quality-a.example.test", "quality-b.example.test") &&
                    url.port == port() && url.encodedPath == "/quality-payload" && url.query == null
                ) {
                    val trust = trust(certificateBase64())
                    OkHttpRelayTcpProbe(timeout, control) {
                        OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                    }
                } else {
                    OkHttpRelayTcpProbe(timeout, control)
                }
            },
        )

    private fun trust(certificateBase64: String): HandshakeCertificates {
        val certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(Base64.getDecoder().decode(certificateBase64)),
            ) as X509Certificate
        return HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
    }

    fun xray(
        owner: XrayRuntimeOwner,
        protection: ActiveProtectSocketPathProvider,
        environment: suspend () -> CandidateRelayProbeEnvironment,
        certificateBase64: String,
    ): CandidateXrayPayloadProbe {
        val certificate =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(
                    ByteArrayInputStream(Base64.getDecoder().decode(certificateBase64)),
                ) as X509Certificate
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val http =
            CandidateHttpPayloadProbe(
                OkHttpRelayTcpProbe(),
                System::nanoTime,
                qualityProbe = { timeout ->
                    QualityHttpPayloadProbe(timeout, {
                        OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                    })
                },
            )
        return CandidateXrayPayloadProbe(owner, protection, environment, http)
    }
}
