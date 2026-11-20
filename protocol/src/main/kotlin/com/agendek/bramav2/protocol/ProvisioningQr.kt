package com.agendek.bramav2.protocol

import java.util.Base64

enum class QrError { MALFORMED, UNSUPPORTED_VERSION, INVALID_DEVICE_ID, INVALID_KEY, INVALID_PIN }

sealed interface QrParseResult {
    data class Ok(val qr: ProvisioningQr) : QrParseResult
    data class Invalid(val error: QrError) : QrParseResult
}

/**
 * Dane z QR (9.2). `setupKey` to `K_setup`, `pin` to 6 cyfr ASCII PIN-u sterownika (11.4); nie logować
 * i nie utrwalać poza czasem rejestracji.
 */
class ProvisioningQr(val deviceId: ByteArray, val setupKey: ByteArray, val pin: ByteArray) {

    override fun toString(): String = "ProvisioningQr(deviceId=${deviceId.toHex()}, setupKey=<ukryty>, pin=<ukryty>)"

    companion object {
        private const val PREFIX = "brama://provision?"
        private val FIELDS = setOf("v", "id", "k", "p")
        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val KEY_PATTERN = Regex("[A-Za-z0-9_-]{43}")

        /** `brama://provision?v=2&id=<32_małe_znaki_hex>&k=<base64url_32_bajtów_bez_paddingu>&p=<6_cyfr>` */
        fun parse(text: String): QrParseResult {
            if (!text.startsWith(PREFIX)) return QrParseResult.Invalid(QrError.MALFORMED)
            val params = mutableMapOf<String, String>()
            for (part in text.substring(PREFIX.length).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) return QrParseResult.Invalid(QrError.MALFORMED)
                if (params.put(part.substring(0, eq), part.substring(eq + 1)) != null) {
                    return QrParseResult.Invalid(QrError.MALFORMED)
                }
            }
            // Wersja przed zestawem pól: QR nowszej wersji ma dostać UNSUPPORTED_VERSION, nie MALFORMED.
            val version = params["v"] ?: return QrParseResult.Invalid(QrError.MALFORMED)
            if (version != "2") return QrParseResult.Invalid(QrError.UNSUPPORTED_VERSION)
            if (params.keys != FIELDS) return QrParseResult.Invalid(QrError.MALFORMED)

            val id = params.getValue("id")
            if (!ID_PATTERN.matches(id)) return QrParseResult.Invalid(QrError.INVALID_DEVICE_ID)

            val k = params.getValue("k")
            if (!KEY_PATTERN.matches(k)) return QrParseResult.Invalid(QrError.INVALID_KEY)
            val key = Base64.getUrlDecoder().decode(k)
            // Odrzucenie niekanonicznych końcowych bitów: kodowanie musi być jednoznaczne.
            if (key.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(key) != k) {
                return QrParseResult.Invalid(QrError.INVALID_KEY)
            }
            val pin = PairingSecret.Pin.parse(params.getValue("p")) ?: run {
                key.fill(0)
                return QrParseResult.Invalid(QrError.INVALID_PIN)
            }
            return QrParseResult.Ok(ProvisioningQr(id.hexToBytes(), key, pin.pin))
        }
    }
}
