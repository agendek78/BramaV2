package com.agendek.bramav2.protocol

/** Rejestracja (klucz `K_setup`, 11) albo zwykła sesja (klucz `K_client`, 12.1). */
enum class HandshakeKind(val begin: MessageType, val challenge: MessageType, val confirm: MessageType) {
    PAIR(MessageType.PAIR_BEGIN, MessageType.PAIR_CHALLENGE, MessageType.PAIR_CONFIRM),
    SESSION(MessageType.SESSION_BEGIN, MessageType.SESSION_CHALLENGE, MessageType.SESSION_CONFIRM),
}

/** `T = device_id || client_id || client_nonce || server_nonce || LE64(session_id)` — 104 B (11.2). */
class Transcript(
    val deviceId: ByteArray,
    val clientId: ByteArray,
    val clientNonce: ByteArray,
    val serverNonce: ByteArray,
    val sessionId: Long,
) {
    init {
        require(deviceId.size == ID_SIZE && clientId.size == ID_SIZE)
        require(clientNonce.size == NONCE_SIZE && serverNonce.size == NONCE_SIZE)
        require(sessionId != 0L)
    }

    fun encode(): ByteArray = deviceId + clientId + clientNonce + serverNonce + le64(sessionId)

    companion object {
        const val ID_SIZE = 16
        const val NONCE_SIZE = 32

        /** `client_id || client_nonce` */
        const val BEGIN_BODY_SIZE = ID_SIZE + NONCE_SIZE

        /** `device_id || client_id || client_nonce || server_nonce` */
        const val CHALLENGE_BODY_SIZE = 2 * ID_SIZE + 2 * NONCE_SIZE
    }
}

/** Pola payloadu CHALLENGE (11.1, 12.1). */
class ChallengeBody(val deviceId: ByteArray, val clientId: ByteArray, val clientNonce: ByteArray, val serverNonce: ByteArray) {
    companion object {
        fun decode(body: ByteArray): ChallengeBody {
            if (body.size != Transcript.CHALLENGE_BODY_SIZE) throw ProtocolException("CHALLENGE musi mieć ${Transcript.CHALLENGE_BODY_SIZE} B")
            val id = Transcript.ID_SIZE
            val nonce = Transcript.NONCE_SIZE
            return ChallengeBody(
                deviceId = body.copyOfRange(0, id),
                clientId = body.copyOfRange(id, 2 * id),
                clientNonce = body.copyOfRange(2 * id, 2 * id + nonce),
                serverNonce = body.copyOfRange(2 * id + nonce, Transcript.CHALLENGE_BODY_SIZE),
            )
        }
    }
}

/** Klucze i prefiksy nonce obu kierunków jednej sesji. */
class SessionKeys(val c2sKey: ByteArray, val s2cKey: ByteArray, val c2sIv: ByteArray, val s2cIv: ByteArray) {
    fun wipe() {
        listOf(c2sKey, s2cKey, c2sIv, s2cIv).forEach { it.fill(0) }
    }
}

object KeySchedule {
    fun derive(kind: HandshakeKind, ikm: ByteArray, transcript: Transcript): SessionKeys {
        val prk = Crypto.hkdfExtract(Crypto.sha256(transcript.encode()), ikm)
        fun expand(suffix: String, len: Int) =
            Crypto.hkdfExpand(prk, "BRAMA/2/${kind.name}/$suffix".toByteArray(Charsets.US_ASCII), len)
        try {
            return SessionKeys(
                c2sKey = expand("c2s/key", 32),
                s2cKey = expand("s2c/key", 32),
                c2sIv = expand("c2s/iv", 4),
                s2cIv = expand("s2c/iv", 4),
            )
        } finally {
            // PRK pozwala odtworzyć wszystkie klucze; zerowanie jest najlepszym możliwym wysiłkiem (9.2).
            prk.fill(0)
        }
    }
}

/** Budowa i weryfikacja ramek BEGIN/CHALLENGE/CONFIRM z HMAC (11.2, 12.1). Porównania w stałym czasie. */
object HandshakeFrames {

    /**
     * `K_pin` (11.4): HKDF-Expand(HKDF-Extract(SHA-256(PT), S), "BRAMA/2/PAKE/setup", 32), gdzie `S` to sekret
     * EC-JPAKE, a `PT` — payloady rund 0x26, 0x27, 0x28, 0x29 w tej kolejności.
     */
    fun pinSetupKey(secret: ByteArray, pakeTranscript: ByteArray): ByteArray {
        val prk = Crypto.hkdfExtract(Crypto.sha256(pakeTranscript), secret)
        try {
            return Crypto.hkdfExpand(prk, "BRAMA/2/PAKE/setup".toByteArray(Charsets.US_ASCII), LONG_TERM_KEY_SIZE)
        } finally {
            prk.fill(0)
        }
    }

    fun begin(kind: HandshakeKind, key: ByteArray, deviceId: ByteArray, clientId: ByteArray, clientNonce: ByteArray): Frame {
        val body = clientId + clientNonce
        val h = FrameHeader(kind.begin.code, 0, 0, body.size)
        return Frame(h, body, Crypto.mac(key, kind.begin.macDomain, h.encode() + body + deviceId))
    }

    fun verifyBegin(kind: HandshakeKind, key: ByteArray, deviceId: ByteArray, frame: Frame): Boolean =
        frame.header.typeCode == kind.begin.code && frame.body.size == Transcript.BEGIN_BODY_SIZE &&
            Crypto.constantTimeEquals(
                Crypto.mac(key, kind.begin.macDomain, frame.header.encode() + frame.body + deviceId),
                frame.tag,
            )

    fun challenge(
        kind: HandshakeKind,
        key: ByteArray,
        sessionId: Long,
        deviceId: ByteArray,
        clientId: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): Frame {
        val body = deviceId + clientId + clientNonce + serverNonce
        val h = FrameHeader(kind.challenge.code, sessionId, 0, body.size)
        return Frame(h, body, Crypto.mac(key, kind.challenge.macDomain, h.encode() + body))
    }

    fun verifyChallenge(kind: HandshakeKind, key: ByteArray, frame: Frame): Boolean =
        frame.header.typeCode == kind.challenge.code && frame.body.size == Transcript.CHALLENGE_BODY_SIZE &&
            Crypto.constantTimeEquals(
                Crypto.mac(key, kind.challenge.macDomain, frame.header.encode() + frame.body),
                frame.tag,
            )

    fun confirm(kind: HandshakeKind, key: ByteArray, transcript: Transcript): Frame {
        val h = FrameHeader(kind.confirm.code, transcript.sessionId, 0, 0)
        return Frame(h, ByteArray(0), Crypto.mac(key, kind.confirm.macDomain, h.encode() + transcript.encode()))
    }

    fun verifyConfirm(kind: HandshakeKind, key: ByteArray, transcript: Transcript, frame: Frame): Boolean =
        frame.header.typeCode == kind.confirm.code && frame.body.isEmpty() &&
            frame.header.sessionId == transcript.sessionId &&
            Crypto.constantTimeEquals(
                Crypto.mac(key, kind.confirm.macDomain, frame.header.encode() + transcript.encode()),
                frame.tag,
            )
}

/** Ramki AES-256-GCM (12.3): `nonce = IV[4] || LE64(seq)`, `AAD = H`. */
object GcmFrames {

    fun seal(key: ByteArray, iv: ByteArray, typeCode: Int, sessionId: Long, seq: Long, plaintext: ByteArray): Frame {
        require(MessageType.tagKindOf(typeCode) == TagKind.GCM) { "Typ $typeCode nie jest wiadomością GCM" }
        require(seq >= 1)
        val h = FrameHeader(typeCode, sessionId, seq, plaintext.size)
        val sealed = Crypto.aesGcmSeal(key, nonce(iv, seq), plaintext, h.encode())
        val split = sealed.size - Crypto.GCM_TAG_LEN
        return Frame(h, sealed.copyOf(split), sealed.copyOfRange(split, sealed.size))
    }

    /** Zwraca jawny payload albo `null` przy niepoprawnym tagu. */
    fun open(key: ByteArray, iv: ByteArray, frame: Frame): ByteArray? {
        if (frame.tag.size != Crypto.GCM_TAG_LEN) return null
        return Crypto.aesGcmOpen(key, nonce(iv, frame.header.seq), frame.body + frame.tag, frame.header.encode())
    }

    private fun nonce(iv: ByteArray, seq: Long): ByteArray {
        require(iv.size == 4)
        return iv + le64(seq)
    }
}
