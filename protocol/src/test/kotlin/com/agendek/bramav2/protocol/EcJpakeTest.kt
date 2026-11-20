package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class EcJpakeTest {

    private val v = vectors("mbedtls_selftest.txt")
    private val pin = "482915".toByteArray(Charsets.US_ASCII)

    /** Skalary self-testu w kolejności pobierania (x1, v1, x2, v2); nonce ZKP dowolne — rundy nie są porównywane. */
    private fun fixed(vararg xs: String) = sequence(*xs.map { BigInteger(1, v.getValue(it)) }.toTypedArray())

    @Test
    fun clientWithTestScalarsDerivesMbedtlsPms() {
        val cli = EcJpake(JpakeRole.CLIENT, v.getValue("password"), fixed("x1", "x3", "x2", "x4"))
        cli.writeRound1()
        cli.readRound1(v.getValue("srv_one"))
        cli.readRound2(v.getValue("srv_two"))
        assertEquals(v.getValue("pms").toHex(), cli.deriveSecret().toHex())
    }

    @Test
    fun serverWithTestScalarsDerivesMbedtlsPms() {
        val srv = EcJpake(JpakeRole.SERVER, v.getValue("password"), fixed("x3", "x1", "x4", "x2"))
        srv.writeRound1()
        srv.readRound1(v.getValue("cli_one"))
        srv.readRound2(v.getValue("cli_two"))
        assertEquals(v.getValue("pms").toHex(), srv.deriveSecret().toHex())
    }

    @Test
    fun clientAndServerAgree() {
        val (c, s) = exchange(pin, pin)
        assertArrayEquals(c, s)
        assertEquals(32, c.size)
    }

    @Test
    fun wrongPasswordGivesDifferentSecret() {
        val (c, s) = exchange(pin, "482916".toByteArray(Charsets.US_ASCII))
        assertFalse(c.contentEquals(s))
    }

    @Test
    fun serverRound2CarriesEcParameters() {
        val cli = EcJpake(JpakeRole.CLIENT, pin)
        val srv = EcJpake(JpakeRole.SERVER, pin)
        srv.readRound1(cli.writeRound1())
        cli.readRound1(srv.writeRound1())
        val s2 = srv.writeRound2()
        assertEquals("030017", s2.copyOfRange(0, 3).toHex())
        assertEquals(0x41, cli.writeRound2()[0].toInt() and 0xff)
    }

    @Test
    fun flippedZkpBitIsRejected() {
        val bad = v.getValue("srv_one").copyOf()
        bad[100] = (bad[100].toInt() xor 1).toByte() // wnętrze dowodu ZKP pierwszego klucza
        val cli = EcJpake(JpakeRole.CLIENT, v.getValue("password"))
        assertThrows(ProtocolException::class.java) { cli.readRound1(bad) }
    }

    @Test
    fun malformedRoundsAreRejected() {
        val good = v.getValue("srv_one")
        fun reject(bytes: ByteArray) =
            assertThrows(ProtocolException::class.java) { EcJpake(JpakeRole.CLIENT, pin).readRound1(bytes) }
        reject(good.copyOf(good.size - 1)) // obcięcie
        reject(good + byteArrayOf(0)) // nadmiarowy bajt
        reject(byteArrayOf(0x01, 0x00) + good.copyOfRange(66, good.size)) // punkt w nieskończoności zamiast X
        reject(byteArrayOf(0x40) + good.copyOfRange(1, good.size)) // zła długość ECPoint
        val offCurve = good.copyOf().also { it[66 - 1] = (it[66 - 1].toInt() xor 1).toByte() } // ostatni bajt Y punktu X
        reject(offCurve)
        // r + n: ta sama wartość modulo n, więc dowód by przeszedł — wymagane r < n (jak mbedTLS)
        val rOffset = 66 + 66 // X, V
        val r = BigInteger(1, good.copyOfRange(rOffset + 1, rOffset + 1 + good[rOffset]))
        val rPlusN = (r + EcJpake.ORDER).toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val withBigR = good.copyOfRange(0, rOffset) + byteArrayOf(rPlusN.size.toByte()) + rPlusN +
            good.copyOfRange(rOffset + 1 + good[rOffset], good.size)
        reject(withBigR)
        // klient wymaga ECParameters w rundzie 2 serwera
        val cli = EcJpake(JpakeRole.CLIENT, v.getValue("password"), fixed("x1", "x3", "x2", "x4"))
        cli.writeRound1()
        cli.readRound1(v.getValue("srv_one"))
        val s2 = v.getValue("srv_two")
        assertThrows(ProtocolException::class.java) { cli.readRound2(s2.copyOfRange(3, s2.size)) }
        assertThrows(ProtocolException::class.java) { cli.readRound2(byteArrayOf(0x03, 0x00, 0x18) + s2.copyOfRange(3, s2.size)) }
    }

    @Test
    fun destroyZeroesPassword() {
        val pw = pin.copyOf()
        val cli = EcJpake(JpakeRole.CLIENT, pw)
        pw.fill(1) // konstruktor kopiuje hasło
        cli.writeRound1()
        cli.destroy()
        assertTrue(cli.isDestroyed)
        assertThrows(IllegalStateException::class.java) { cli.writeRound2() }
    }

    private fun exchange(cliPw: ByteArray, srvPw: ByteArray): Pair<ByteArray, ByteArray> {
        val cli = EcJpake(JpakeRole.CLIENT, cliPw)
        val srv = EcJpake(JpakeRole.SERVER, srvPw)
        srv.readRound1(cli.writeRound1())
        cli.readRound1(srv.writeRound1())
        cli.readRound2(srv.writeRound2())
        srv.readRound2(cli.writeRound2())
        return cli.deriveSecret() to srv.deriveSecret()
    }

    companion object {
        fun vectors(name: String): Map<String, ByteArray> =
            EcJpakeTest::class.java.getResource("/ecjpake/$name")!!.readText().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore('=') to line.substringAfter('=').hexToBytes() }

        /** Źródło skalarów z ustaloną sekwencją (powtarza ostatni, gdy zabraknie). */
        fun sequence(vararg xs: BigInteger): ScalarSource {
            var i = 0
            return ScalarSource { xs[minOf(i++, xs.size - 1)] }
        }
    }

    @Test
    fun infinityKeyWithMatchingProofIsRejected() {
        // Dla X = nieskończoność dowód V = r·G przechodzi weryfikację h·X + r·G == V — klucz musi być odrzucony wprost.
        val params = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
        val r = BigInteger.valueOf(12345)
        val vPoint = params.g.multiply(r).normalize().getEncoded(false)
        val kkp = byteArrayOf(0x01, 0x00) + byteArrayOf(vPoint.size.toByte()) + vPoint + byteArrayOf(r.toByteArray().size.toByte()) + r.toByteArray()
        val good = v.getValue("srv_one")
        val secondKkp = good.copyOfRange(66 + 66 + 1 + good[132], good.size)
        assertThrows(ProtocolException::class.java) { EcJpake(JpakeRole.CLIENT, pin).readRound1(kkp + secondKkp) }
    }
}
