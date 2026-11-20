package com.agendek.bramav2.protocol

/** Długość `K_setup` i `K_client` (9.2, 11.3). */
internal const val LONG_TERM_KEY_SIZE = 32

sealed interface HandshakeStep {
    /** Wyślij następną ramkę handshake (CONFIRM); te same bajty są dostępne w `pendingRequest`. */
    class Send(val frame: ByteArray) : HandshakeStep

    /** SESSION_READY poprawny: stan READY. Snapshot z READY jest w `session.latestSnapshot`. */
    class Established(val session: ClientSession) : HandshakeStep

    /** PAIR_GRANT odszyfrowany. Zapisać `K_client` jako UNCONFIRMED, potem wywołać `commit()`. */
    class GrantReceived(val clientKey: ByteArray) : HandshakeStep

    /** PAIR_DONE poprawny. Potwierdzeniem aktywacji jest dopiero SESSION_READY (11.3, krok 8). */
    data object Paired : HandshakeStep

    /** Podpowiedź HANDSHAKE_REJECT (12.4): koniec próby, bez zmiany poświadczeń. */
    data class Rejected(val reason: HandshakeRejectReason) : HandshakeStep

    /** Błąd autoryzacji lub protokołu: próba zakończona definitywnie. */
    data class Failed(val reason: String) : HandshakeStep

    /** Identyczna, już obsłużona ramka. */
    data object Duplicate : HandshakeStep

    /** Ramka nie dotyczy tej próby (np. stary session_id podczas odtwarzania sesji). */
    data class Ignored(val reason: String) : HandshakeStep
}

/**
 * Wspólny przebieg BEGIN → CHALLENGE → CONFIRM po stronie telefonu (11.3, 12.1).
 * Retransmisje i limity czasu prowadzi warstwa transportu, wysyłając ponownie [pendingRequest].
 *
 * Nie jest bezpieczna wątkowo: wszystkie wywołania muszą pochodzić z jednego wątku lub aktora
 * (np. kolejki GATT z rozdziału 6), także callbacki BLE.
 */
abstract class ClientHandshake internal constructor(
    private val kind: HandshakeKind,
    key: ByteArray,
    deviceId: ByteArray,
    clientId: ByteArray,
    nonces: NonceSource,
) {
    // Kopie: wywołujący może wyzerować lub ponownie użyć swoich buforów (np. klucza z magazynu).
    private val key = key.copyOf()
    private val deviceId = deviceId.copyOf()
    private val clientId = clientId.copyOf()

    protected enum class State { AWAIT_CHALLENGE, AWAIT_FINAL, GRANTED, AWAIT_DONE, FINISHED }

    init {
        require(this.key.size == LONG_TERM_KEY_SIZE) { "K_setup/K_client musi mieć $LONG_TERM_KEY_SIZE B" }
        require(this.deviceId.size == Transcript.ID_SIZE && this.clientId.size == Transcript.ID_SIZE)
    }

    protected var state = State.AWAIT_CHALLENGE
    protected var keys: SessionKeys? = null
    protected var sessionId = 0L
    protected var challengeBytes: ByteArray? = null
    private val clientNonce = nonces.next(Transcript.NONCE_SIZE).also {
        require(it.size == Transcript.NONCE_SIZE) { "NonceSource zwrócił ${it.size} B zamiast ${Transcript.NONCE_SIZE}" }
    }
    private var pending: ByteArray? = HandshakeFrames.begin(kind, this.key, this.deviceId, this.clientId, clientNonce).encode()

    /** Bieżące żądanie do (re)transmisji jako identyczne bajty; `null`, gdy nic nie oczekuje na odpowiedź. */
    val pendingRequest: ByteArray? get() = pending?.copyOf()

    fun onFrame(bytes: ByteArray): HandshakeStep {
        if (state == State.FINISHED) return HandshakeStep.Ignored("Próba zakończona")
        val frame = try {
            Frame.decode(bytes)
        } catch (e: ProtocolException) {
            return fail(e.message ?: "Zła ramka")
        }
        val type = frame.header.messageType

        if (type == MessageType.HANDSHAKE_REJECT) {
            if (pending == null || frame.header.sessionId != sessionId) return HandshakeStep.Ignored("Odmowa innej próby")
            finish()
            return HandshakeStep.Rejected(HandshakeRejectReason.fromCode(frame.body[0].toInt() and 0xff))
        }
        if (state == State.AWAIT_CHALLENGE) {
            return if (type == kind.challenge) onChallenge(frame, bytes) else HandshakeStep.Ignored("Oczekiwano CHALLENGE")
        }
        if (frame.header.sessionId != sessionId) return HandshakeStep.Ignored("Inny session_id")
        if (challengeBytes!!.contentEquals(bytes)) return HandshakeStep.Duplicate
        if (type == null || type.direction != Direction.S2C) return fail("Ramka w złym kierunku lub nieznany typ ${frame.header.typeCode}")
        return onSessionFrame(frame, bytes)
    }

    protected abstract fun onSessionFrame(frame: Frame, bytes: ByteArray): HandshakeStep

    private fun onChallenge(frame: Frame, bytes: ByteArray): HandshakeStep {
        if (!HandshakeFrames.verifyChallenge(kind, key, frame)) return fail("Zły HMAC CHALLENGE")
        val sid = frame.header.sessionId
        val body = ChallengeBody.decode(frame.body)
        if (sid == 0L) return fail("CHALLENGE z session_id = 0")
        if (!body.deviceId.contentEquals(deviceId)) return fail("Niezgodny device_id")
        if (!body.clientId.contentEquals(clientId)) return fail("Niezgodny client_id")
        // Poprawnie podpisany CHALLENGE na wcześniejszy BEGIN (retransmisja z cache sterownika, odtworzenie sesji 12.1).
        if (!body.clientNonce.contentEquals(clientNonce)) return HandshakeStep.Ignored("CHALLENGE innej próby")

        val transcript = Transcript(deviceId, clientId, clientNonce, body.serverNonce, sid)
        sessionId = sid
        keys = KeySchedule.derive(kind, key, transcript)
        challengeBytes = bytes.copyOf()
        val confirm = HandshakeFrames.confirm(kind, key, transcript).encode()
        pending = confirm
        state = State.AWAIT_FINAL
        return HandshakeStep.Send(confirm.copyOf())
    }

    /** Odszyfrowuje oczekiwaną wiadomość s2c o danym typie i seq; `null` oznacza błąd. */
    protected fun openExpected(frame: Frame, type: MessageType, seq: Long): ByteArray? {
        if (frame.header.messageType != type || frame.header.seq != seq) return null
        val k = keys!!
        return GcmFrames.open(k.s2cKey, k.s2cIv, frame)
    }

    protected fun setPending(bytes: ByteArray?) {
        pending = bytes
    }

    /**
     * Porzucenie próby przez warstwę transportu (trzeci T_response, upływ T_attempt, rozłączenie BLE,
     * nieudany zapis `K_client`). Zeruje sekrety; kolejne ramki są ignorowane.
     */
    fun abort() {
        finish()
    }

    /** Kończy próbę i zeruje kopię klucza długoterminowego oraz klucze próby, których nikt nie przejął. */
    protected fun finish() {
        state = State.FINISHED
        pending = null
        key.fill(0)
        keys?.wipe()
        keys = null
    }

    /** Przekazuje klucze sesji na zewnątrz (READY); po tym `finish()` ich nie zeruje. */
    protected fun takeKeys(): SessionKeys = keys!!.also { keys = null }

    protected fun fail(reason: String): HandshakeStep {
        finish()
        return HandshakeStep.Failed(reason)
    }
}

/** Zwykła sesja z `K_client` (12.1). */
class SessionHandshake(
    clientKey: ByteArray,
    deviceId: ByteArray,
    clientId: ByteArray,
    nonces: NonceSource = SecureRandomNonceSource(),
) : ClientHandshake(HandshakeKind.SESSION, clientKey, deviceId, clientId, nonces) {

    override fun onSessionFrame(frame: Frame, bytes: ByteArray): HandshakeStep {
        val plain = openExpected(frame, MessageType.SESSION_READY, 1) ?: return fail("Niepoprawny SESSION_READY")
        val snapshot = try {
            RuleSnapshot.decode(plain)
        } catch (e: ProtocolException) {
            return fail(e.message ?: "Zły snapshot")
        }
        val session = ClientSession(sessionId, takeKeys(), challengeBytes!!, bytes.copyOf(), snapshot)
        finish()
        return HandshakeStep.Established(session)
    }
}

/** Rejestracja telefonu z `K_setup` (11.3). */
class PairingHandshake(
    setupKey: ByteArray,
    deviceId: ByteArray,
    clientId: ByteArray,
    nonces: NonceSource = SecureRandomNonceSource(),
) : ClientHandshake(HandshakeKind.PAIR, setupKey, deviceId, clientId, nonces) {

    private var grantBytes: ByteArray? = null

    override fun onSessionFrame(frame: Frame, bytes: ByteArray): HandshakeStep {
        if (grantBytes?.contentEquals(bytes) == true) return HandshakeStep.Duplicate
        return when (state) {
            State.AWAIT_FINAL -> {
                val key = openExpected(frame, MessageType.PAIR_GRANT, 1) ?: return fail("Niepoprawny PAIR_GRANT")
                if (key.size != LONG_TERM_KEY_SIZE) {
                    key.fill(0)
                    return fail("K_client musi mieć $LONG_TERM_KEY_SIZE B")
                }
                grantBytes = bytes.copyOf()
                setPending(null)
                state = State.GRANTED
                HandshakeStep.GrantReceived(key)
            }
            State.AWAIT_DONE -> {
                val plain = openExpected(frame, MessageType.PAIR_DONE, 2) ?: return fail("Niepoprawny PAIR_DONE")
                if (plain.isNotEmpty()) return fail("PAIR_DONE z payloadem")
                finish()
                HandshakeStep.Paired
            }
            else -> fail("Nieoczekiwana ramka w fazie $state")
        }
    }

    /**
     * PAIR_COMMIT; wołać dopiero po bezpiecznym zapisie `K_client` jako UNCONFIRMED. Zwraca `null`, jeśli próba
     * zakończyła się w trakcie zapisu (ramka kończąca, `abort()`); wtedy obowiązuje recovery z 11.3.
     */
    fun commit(): ByteArray? {
        if (state == State.FINISHED) return null
        check(state == State.GRANTED) { "COMMIT dozwolony tylko po PAIR_GRANT" }
        val k = keys!!
        val commit = GcmFrames.seal(k.c2sKey, k.c2sIv, MessageType.PAIR_COMMIT.code, sessionId, 1, ByteArray(0)).encode()
        setPending(commit)
        state = State.AWAIT_DONE
        return commit.copyOf()
    }
}
