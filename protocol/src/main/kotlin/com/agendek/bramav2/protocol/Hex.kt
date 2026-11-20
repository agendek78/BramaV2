package com.agendek.bramav2.protocol

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Dekoduje hex (wielkość liter dowolna, spacje pomijane). Ścisłą walidację formatu QR robi [ProvisioningQr]. */
fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    require(clean.length % 2 == 0) { "Nieparzysta liczba znaków hex" }
    return ByteArray(clean.length / 2) { i ->
        ((hexDigit(clean[2 * i]) shl 4) or hexDigit(clean[2 * i + 1])).toByte()
    }
}

/** Tylko ASCII `0-9a-fA-F`; `Character.digit` przyjmowałby też cyfry Unicode spoza ASCII. */
private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("Znak spoza hex")
}
