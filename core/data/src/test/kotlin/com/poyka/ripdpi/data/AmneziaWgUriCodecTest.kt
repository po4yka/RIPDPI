package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.AmneziaWgUriCodec
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AmneziaWgUriCodec]: the locally-defined `amneziawg://` share
 * URI. Layout (no upstream analog — invented here):
 *
 * ```
 * amneziawg://<base64url(private-key)>@<host>:<port>
 *   ?public_key=<base64url>&allowed_ips=<cidr,cidr>&mtu=<n>
 *   &preshared_key=<base64url>&dns=<ip,ip>
 *   &jc=&jmin=&jmax=&s1=&s2=&s3=&s4=&h1=&h2=&h3=&h4=&i1=&i2=&i3=&i4=&i5=
 *   #<name>
 * ```
 *
 * The codec must round-trip an [AmneziaWgProfile] losslessly.
 */
class AmneziaWgUriCodecTest {
    private fun fullProfile() =
        AmneziaWgProfile(
            name = "Tokyo edge",
            host = "awg.example.com",
            port = 51820,
            privateKey = "cHJpdmF0ZS1rZXktZml4dHVyZS1ieXRlcy0wMDAwMDAwMA==",
            publicKey = "cHVibGljLWtleS1maXh0dXJlLWJ5dGVzLTAwMDAwMDAwMA==",
            presharedKey = "cHJlc2hhcmVkLWtleS1maXh0dXJlLWJ5dGVzLTAwMDAwMA==",
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            dns = listOf("1.1.1.1", "8.8.8.8"),
            mtu = 1280,
            awg =
                AmneziaWgParameters(
                    jc = 4,
                    jmin = 40,
                    jmax = 70,
                    s1 = 30,
                    s2 = 50,
                    s3 = 0,
                    s4 = 0,
                    h1 = 1234567L,
                    h2 = 2345678L,
                    h3 = 3456789L,
                    h4 = 4567890L,
                    i1 = "deadbeef",
                    i2 = "cafebabe",
                    i3 = "0badf00d",
                    i4 = "feedface",
                    i5 = "8badf00d",
                ),
        )

    @Test
    fun `encode produces an amneziawg scheme uri with host port and fragment`() {
        val uri = AmneziaWgUriCodec.encode(fullProfile())

        assertTrue(uri.startsWith("amneziawg://"))
        assertTrue(uri.contains("@awg.example.com:51820"))
        // The display name is URL-encoded into the fragment.
        assertTrue(uri.endsWith("#Tokyo%20edge"))
    }

    @Test
    fun `encode then decode round-trips the full AWG field set losslessly`() {
        val original = fullProfile()

        val decoded = AmneziaWgUriCodec.decode(AmneziaWgUriCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encode emits safe zero s3 and s4 params and decode restores them`() {
        val original = fullProfile()

        val uri = AmneziaWgUriCodec.encode(original)
        assertTrue("s3 param present", uri.contains("s3=0"))
        assertTrue("s4 param present", uri.contains("s4=0"))

        val decoded = AmneziaWgUriCodec.decode(uri)
        assertEquals(0, decoded?.awg?.s3)
        assertEquals(0, decoded?.awg?.s4)
    }

    @Test
    fun `encode rejects non-zero s3 or s4`() {
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgUriCodec.encode(fullProfile().copy(awg = fullProfile().awg.copy(s3 = 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgUriCodec.encode(fullProfile().copy(awg = fullProfile().awg.copy(s4 = 1)))
        }
    }

    @Test
    fun `decode returns null for non-zero s3 or s4`() {
        val safe = AmneziaWgUriCodec.encode(fullProfile())

        assertNull(AmneziaWgUriCodec.decode(safe.replace("s3=0", "s3=1")))
        assertNull(AmneziaWgUriCodec.decode(safe.replace("s4=0", "s4=1")))
    }

    @Test
    fun `decode round-trips a profile with no optional fields`() {
        val minimal =
            AmneziaWgProfile(
                name = "minimal",
                host = "min.example.com",
                port = 443,
                privateKey = "bWluaW1hbC1wcml2YXRlLWtleS1maXh0dXJlLTAwMDAwMA==",
                publicKey = "bWluaW1hbC1wdWJsaWMta2V5LWZpeHR1cmUtMDAwMDAwMA==",
                presharedKey = null,
                allowedIps = emptyList(),
                dns = emptyList(),
                mtu = null,
                awg = AmneziaWgParameters(),
            )

        val decoded = AmneziaWgUriCodec.decode(AmneziaWgUriCodec.encode(minimal))

        assertEquals(minimal, decoded)
    }

    @Test
    fun `decode tolerates a uri with no fragment by falling back to the host as name`() {
        val original = fullProfile()
        val withoutFragment = AmneziaWgUriCodec.encode(original).substringBefore('#')

        val decoded = AmneziaWgUriCodec.decode(withoutFragment)

        assertEquals("awg.example.com", decoded?.name)
    }

    @Test
    fun `decode returns null for a non-amneziawg scheme`() {
        assertNull(AmneziaWgUriCodec.decode("wireguard://something"))
        assertNull(AmneziaWgUriCodec.decode("https://example.com"))
    }

    @Test
    fun `decode returns null for a structurally broken uri`() {
        assertNull(AmneziaWgUriCodec.decode("amneziawg://"))
        assertNull(AmneziaWgUriCodec.decode("amneziawg://@host"))
        assertNull(AmneziaWgUriCodec.decode("not even a uri"))
    }

    @Test
    fun `decode returns null when the mandatory public_key param is missing`() {
        val noPublicKey =
            "amneziawg://cHJpdmF0ZS1rZXktZml4dHVyZS1ieXRlcy0wMDAwMDAwMA==@host.example.com:51820?mtu=1280#x"

        assertNull(AmneziaWgUriCodec.decode(noPublicKey))
    }

    @Test
    fun `malformed numeric params are dropped rather than throwing`() {
        val original = fullProfile()
        // Corrupt a numeric query param; the codec must not throw.
        val corrupted =
            AmneziaWgUriCodec.encode(original).replace("mtu=1280", "mtu=not-a-number")

        val decoded = AmneziaWgUriCodec.decode(corrupted)

        // The profile still decodes; only the corrupted field is dropped.
        assertNull(decoded?.mtu)
        assertEquals(original.host, decoded?.host)
    }
}
