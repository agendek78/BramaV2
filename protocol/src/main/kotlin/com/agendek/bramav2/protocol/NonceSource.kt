package com.agendek.bramav2.protocol

import java.security.SecureRandom

/** Źródło nonce i identyfikatorów. Produkcyjnie wyłącznie CSPRNG (15.3). */
fun interface NonceSource {
    fun next(size: Int): ByteArray
}

class SecureRandomNonceSource(private val random: SecureRandom = SecureRandom()) : NonceSource {
    override fun next(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
