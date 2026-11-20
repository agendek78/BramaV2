package com.agendek.bramav2.protocol

/** Wyniki COMMAND_ACK (13.2). */
enum class AckResult(val code: Int) {
    OUTPUT_TRIGGERED(0), BUSY(1), LEASE_OWNED_BY_OTHER(2), INTERLOCK_BLOCKED(3),
    STORAGE_FAILURE(4), IO_FAULT(5), INVALID_ARGUMENT(6), UNSUPPORTED_TYPE(7),

    /** Okno rejestracji otwarte (albo uruchomione od nowa) na 60 s przez `OPEN_PAIRING_WINDOW`; bez impulsu. */
    WINDOW_OPENED(8),

    /** Zarządzanie telefonami (client_management_design.md): klient usunięty. */
    CLIENT_REMOVED(9),

    /** Nazwa telefonu żądającego zapisana. */
    NAME_SET(10),
    CLIENT_NOT_FOUND(11),
    CANNOT_REMOVE_SELF(12),

    /** Wskazany klient jest właścicielem reguły; najpierw CLOSE albo autozamknięcie. */
    CLIENT_OWNS_RULE(13),
}

/** `request_type[1] || result[1] || request_seq[8 LE] || RuleSnapshot[15]` — 25 B (13.1). */
data class CommandAck(
    val requestTypeCode: Int,
    val result: AckResult,
    val requestSeq: Long,
    val snapshot: RuleSnapshot,
) {
    fun encode(): ByteArray =
        byteArrayOf(requestTypeCode.toByte(), result.code.toByte()) + le64(requestSeq) + snapshot.encode()

    companion object {
        const val SIZE = 2 + 8 + RuleSnapshot.SIZE

        fun decode(bytes: ByteArray): CommandAck {
            if (bytes.size != SIZE) throw ProtocolException("COMMAND_ACK musi mieć $SIZE B")
            return CommandAck(
                requestTypeCode = bytes[0].toInt() and 0xff,
                result = codeOf(AckResult.entries, bytes[1], AckResult::code, "result"),
                requestSeq = bytes.getLe(2, 8),
                snapshot = RuleSnapshot.decode(bytes.copyOfRange(10, SIZE)),
            )
        }
    }
}

/** Kody nieuwierzytelnionej podpowiedzi HANDSHAKE_REJECT (12.4). */
enum class HandshakeRejectReason(val code: Int) {
    AUTH_FAILED(1), UNAVAILABLE(2), RATE_LIMITED(3), NO_CAPACITY(4),
    CLIENT_ALREADY_ACTIVE(5), REPLACEMENT_BLOCKED(6), STORAGE_FAILURE(7), WINDOW_CLOSED(8);

    companion object {
        /** Inne wartości kodu telefon traktuje jak AUTH_FAILED. */
        fun fromCode(code: Int): HandshakeRejectReason = entries.firstOrNull { it.code == code } ?: AUTH_FAILED
    }
}

/** Jawne payloady komend sesji przed szyfrowaniem (13.1). */
object Commands {
    val CLOSE_PAYLOAD = ByteArray(0)
    val PING_PAYLOAD = ByteArray(0)
    val OPEN_PAIRING_WINDOW_PAYLOAD = ByteArray(0)
    val LIST_CLIENTS_PAYLOAD = ByteArray(0)

    /** `name_len[1] || name[name_len]` (13.1). */
    fun setClientNamePayload(name: String): ByteArray = ClientName.encode(name).let { byteArrayOf(it.size.toByte()) + it }

    fun openPayload(autoCloseOnDisconnect: Boolean): ByteArray =
        byteArrayOf(if (autoCloseOnDisconnect) 0x01 else 0x00)
}
