package com.agendek.bramav2.protocol

/** Sekret rejestracji: QR z `K_setup` (11.3) albo PIN uzgadniany przez EC-JPAKE (11.4). */
sealed interface PairingSecret {

    /** Zeruje sekrety (po zakończeniu próby rejestracji). */
    fun wipe()

    class Qr(val qr: ProvisioningQr) : PairingSecret {
        override fun wipe() {
            qr.setupKey.fill(0)
            qr.pin.fill(0)
        }

        override fun toString(): String = "PairingSecret.Qr($qr)"
    }

    /** PIN sterownika: dokładnie 6 cyfr ASCII `0`–`9`; konstruktor kopiuje bajty. */
    class Pin(pin: ByteArray) : PairingSecret {
        val pin: ByteArray = pin.copyOf()

        init {
            require(isValid(this.pin)) { "PIN musi mieć dokładnie $LENGTH cyfr ASCII" }
        }

        override fun wipe() {
            pin.fill(0)
        }

        override fun toString(): String = "PairingSecret.Pin(<ukryty>)"

        companion object {
            const val LENGTH = 6

            /** PIN z tekstu albo `null`, gdy to nie jest dokładnie 6 cyfr ASCII. */
            fun parse(text: String): Pin? {
                val bytes = text.toByteArray(Charsets.UTF_8)
                return if (isValid(bytes)) Pin(bytes).also { bytes.fill(0) } else null
            }

            private fun isValid(bytes: ByteArray) = bytes.size == LENGTH && bytes.all { it in '0'.code.toByte()..'9'.code.toByte() }
        }
    }
}
