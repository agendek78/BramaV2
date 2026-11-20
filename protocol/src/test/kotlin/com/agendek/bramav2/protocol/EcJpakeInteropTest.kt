package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * Zgodność z `mbedtls_ecjpake` w roli serwera (firmware ESP32): rundy klienta Kotlina zweryfikował mbedTLS
 * (`resources/ecjpake/harness.c`), a Kotlin wylicza ten sam `S` co mbedTLS. Generacja: `resources/ecjpake/README.md`.
 */
class EcJpakeInteropTest {

    @Test
    fun kotlinClientInteropsWithMbedtlsServer() {
        val v = EcJpakeTest.vectors("interop.txt")
        val cli = EcJpake(JpakeRole.CLIENT, v.getValue("pin"), deterministicScalars())
        assertEquals(v.getValue("cli_one").toHex(), cli.writeRound1().toHex())
        cli.readRound1(v.getValue("srv_one"))
        cli.readRound2(v.getValue("srv_two"))
        assertEquals(v.getValue("cli_two").toHex(), cli.writeRound2().toHex())
        assertEquals(v.getValue("pms").toHex(), cli.deriveSecret().toHex())
    }

    /** Krok generacji (tylko z `-PecjpakeGen=<plik serwera albo "start">`): zapisuje rundy klienta. */
    @Test
    fun generateClientRounds() {
        val arg = System.getProperty("ecjpake.gen")
        assumeTrue(arg != null)
        val cli = EcJpake(JpakeRole.CLIENT, PIN.toByteArray(Charsets.US_ASCII), deterministicScalars())
        val lines = mutableListOf("pin=${PIN.toByteArray(Charsets.US_ASCII).toHex()}", "cli_one=${cli.writeRound1().toHex()}")
        if (arg != "start") {
            val srv = File(arg).readLines().associate { it.substringBefore('=') to it.substringAfter('=').hexToBytes() }
            cli.readRound1(srv.getValue("srv_one"))
            cli.readRound2(srv.getValue("srv_two"))
            lines += "cli_two=${cli.writeRound2().toHex()}"
            lines += "kotlin_secret=${cli.deriveSecret().toHex()}"
        }
        File("build/ecjpake").mkdirs()
        File("build/ecjpake/client.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val PIN = "482915"

        /** Skalary k_i = SHA-256("BRAMA/test/ecjpake" || i) mod (n − 1) + 1. */
        fun deterministicScalars(): ScalarSource {
            var i = 0
            return ScalarSource {
                val h = Crypto.sha256("BRAMA/test/ecjpake".toByteArray(Charsets.US_ASCII) + byteArrayOf((i++).toByte()))
                BigInteger(1, h).mod(EcJpake.ORDER - BigInteger.ONE) + BigInteger.ONE
            }
        }
    }
}
