package com.agendek.bramav2.protocol

sealed interface PakeStep {
    /** Wyślij PAKE_ROUND2; te same bajty są w `pendingRequest`. */
    class Send(val frame: ByteArray) : PakeStep

    /** `K_pin` (32 B) do `PairingHandshake` na tym samym połączeniu; wołający zeruje go po użyciu. */
    class KeyReady(val pinKey: ByteArray) : PakeStep

    /** Podpowiedź HANDSHAKE_REJECT (12.4): koniec próby. */
    data class Rejected(val reason: HandshakeRejectReason) : PakeStep

    /** Błąd formatu, dowodu ZKP lub fazy: próba zakończona. */
    data class Failed(val reason: String) : PakeStep

    data object Duplicate : PakeStep

    data class Ignored(val reason: String) : PakeStep
}

/**
 * Rundy EC-JPAKE rejestracji PIN-em po stronie telefonu (11.4): PAKE_ROUND1 → ROUND1_REPLY → PAKE_ROUND2 →
 * ROUND2_REPLY → `K_pin`. Retransmisje i limity czasu prowadzi transport, wysyłając ponownie [pendingRequest].
 * Nie jest bezpieczna wątkowo.
 */
class PinPakeHandshake(pin: PairingSecret.Pin, scalars: ScalarSource = SecureRandomScalars()) {

    private enum class State { AWAIT_REPLY1, AWAIT_REPLY2, FINISHED }

    private val jpake = EcJpake(JpakeRole.CLIENT, pin.pin, scalars)
    private var state = State.AWAIT_REPLY1
    private var sessionId = 0L
    private var reply1Bytes: ByteArray? = null
    private var transcript: ByteArray
    private var pending: ByteArray?

    init {
        val round1 = jpake.writeRound1()
        transcript = round1
        pending = frame(MessageType.PAKE_ROUND1, 0, round1)
    }

    /** Bieżące żądanie do (re)transmisji jako identyczne bajty; `null`, gdy nic nie oczekuje. */
    val pendingRequest: ByteArray? get() = pending?.copyOf()

    fun onFrame(bytes: ByteArray): PakeStep {
        if (state == State.FINISHED) return PakeStep.Ignored("Próba zakończona")
        val f = try {
            Frame.decode(bytes)
        } catch (e: ProtocolException) {
            return fail(e.message ?: "Zła ramka")
        }
        val type = f.header.messageType
        if (type == MessageType.HANDSHAKE_REJECT) {
            if (f.header.sessionId != sessionId) return PakeStep.Ignored("Odmowa innej próby")
            finish()
            return PakeStep.Rejected(HandshakeRejectReason.fromCode(f.body[0].toInt() and 0xff))
        }
        return when (state) {
            State.AWAIT_REPLY1 -> {
                if (type != MessageType.PAKE_ROUND1_REPLY) return PakeStep.Ignored("Oczekiwano PAKE_ROUND1_REPLY")
                if (f.header.sessionId == 0L) return fail("PAKE_ROUND1_REPLY z session_id = 0")
                try {
                    jpake.readRound1(f.body)
                } catch (e: ProtocolException) {
                    return fail(e.message ?: "Zła runda 1")
                }
                sessionId = f.header.sessionId
                reply1Bytes = bytes.copyOf()
                val round2 = jpake.writeRound2()
                transcript += f.body + round2
                pending = frame(MessageType.PAKE_ROUND2, sessionId, round2)
                state = State.AWAIT_REPLY2
                PakeStep.Send(pending!!.copyOf())
            }
            State.AWAIT_REPLY2 -> {
                if (f.header.sessionId != sessionId) return PakeStep.Ignored("Inny session_id")
                if (reply1Bytes!!.contentEquals(bytes)) return PakeStep.Duplicate
                if (type != MessageType.PAKE_ROUND2_REPLY) return fail("Ramka ${f.header.typeCode} w fazie PAKE")
                val secret = try {
                    jpake.readRound2(f.body)
                    jpake.deriveSecret()
                } catch (e: ProtocolException) {
                    return fail(e.message ?: "Zła runda 2")
                }
                val key = HandshakeFrames.pinSetupKey(secret, transcript + f.body)
                secret.fill(0)
                finish()
                PakeStep.KeyReady(key)
            }
            State.FINISHED -> PakeStep.Ignored("Próba zakończona")
        }
    }

    /** Porzucenie próby przez transport; zeruje PIN i stan EC-JPAKE. */
    fun abort() = finish()

    private fun finish() {
        state = State.FINISHED
        pending = null
        jpake.destroy()
        transcript.fill(0)
    }

    private fun fail(reason: String): PakeStep {
        finish()
        return PakeStep.Failed(reason)
    }

    private fun frame(type: MessageType, sid: Long, payload: ByteArray) =
        Frame(FrameHeader(type.code, sid, 0, payload.size), payload, ByteArray(0)).encode()
}
