package com.agendek.bramav2.protocol

/** Żądania telefonu w sesji READY (13.1, 15.5). */
sealed interface SessionRequest {
    data class Open(val autoCloseOnDisconnect: Boolean) : SessionRequest
    data object Close : SessionRequest
    data object Ping : SessionRequest

    /** Otwarcie okna rejestracji z zarejestrowanego telefonu (phone_window_design §3); odpowiedź — COMMAND_ACK. */
    data object OpenPairingWindow : SessionRequest

    /** Lista telefonów na sterowniku (client_management_design.md); odpowiedź — CLIENT_LIST (albo COMMAND_ACK z błędem). */
    data object ListClients : SessionRequest

    /** Usunięcie innego telefonu; odpowiedź — COMMAND_ACK. Równość przez tożsamość. */
    class RemoveClient(clientId: ByteArray) : SessionRequest {
        val clientId: ByteArray = clientId.copyOf()

        init {
            require(clientId.size == 16) { "client_id musi mieć 16 B" }
        }

        override fun toString() = "RemoveClient(${this.clientId.toHex().take(8)})"
    }

    /** Nazwa telefonu żądającego (pusta czyści); odpowiedź — COMMAND_ACK. Równość przez tożsamość. */
    class SetClientName(val name: String) : SessionRequest {
        init {
            require(ClientName.isValid(name)) { "Niepoprawna nazwa telefonu" }
        }

        override fun toString() = "SetClientName(\"$name\")"
    }

    /**
     * Żądania aktualizacji firmware (15.5). [OtaBegin] i [OtaData] mają równość przez tożsamość: zdarzenia wyniku
     * niosą ten sam obiekt, który wysłano.
     */
    class OtaBegin(val imageSize: Long, val sha256: ByteArray) : SessionRequest {
        override fun toString() = "OtaBegin(imageSize=$imageSize)"
    }

    class OtaData(val offset: Long, val data: ByteArray) : SessionRequest {
        override fun toString() = "OtaData(offset=$offset, size=${data.size})"
    }

    data object OtaEnd : SessionRequest
    data object OtaAbort : SessionRequest
}

/** Żądanie aktualizacji firmware — odpowiedzią jest OTA_ACK, nie COMMAND_ACK. */
val SessionRequest.isOta: Boolean
    get() = this is SessionRequest.OtaBegin || this is SessionRequest.OtaData || this == SessionRequest.OtaEnd || this == SessionRequest.OtaAbort

sealed interface SessionEvent {
    /** ACK z poprawną referencją. `snapshotApplied = false`, gdy snapshot był starszy niż już znany. */
    data class CommandAcknowledged(val request: SessionRequest, val ack: CommandAck, val snapshotApplied: Boolean) : SessionEvent

    /** OTA_ACK z poprawną referencją żądania OTA (15.5); bez snapshotu. */
    data class OtaAcknowledged(val request: SessionRequest, val ack: OtaAck) : SessionEvent
    /** CLIENT_LIST z referencją żądania `ListClients` (bez snapshotu). */
    data class ClientListReceived(val request: SessionRequest, val list: ClientList) : SessionEvent
    data class Pong(val snapshot: RuleSnapshot, val snapshotApplied: Boolean) : SessionEvent
    data class RuleStatus(val snapshot: RuleSnapshot, val applied: Boolean) : SessionEvent

    /** Identyczna, już przyjęta ramka: ten sam wynik, bez zmiany stanu. */
    data object Duplicate : SessionEvent

    data class Ignored(val reason: String) : SessionEvent

    /** Sesja unieważniona; `unresolved` ma wynik OUTCOME_UNKNOWN i nie jest ponawiane w nowej sesji. */
    data class Failed(val reason: String, val unresolved: SessionRequest?) : SessionEvent
}

/**
 * Sesja READY po stronie telefonu (12.3, 13.3, 13.4): liczniki, duplikaty, dopasowanie ACK i kolejność snapshotów.
 * Najwyżej jedno oczekujące żądanie; retransmisja to ponowne wysłanie [pendingRequest] bez zmian.
 *
 * Nie jest bezpieczna wątkowo: [send], [onFrame] i [invalidate] muszą być wołane z jednego wątku lub aktora
 * (rozdział 6: jeden `Mutex`/aktor dla wszystkich źródeł komend), także dla callbacków BLE.
 */
class ClientSession internal constructor(
    val sessionId: Long,
    private val keys: SessionKeys,
    private val challengeFrame: ByteArray,
    readyFrame: ByteArray,
    initialSnapshot: RuleSnapshot,
) {
    private class Pending(val request: SessionRequest, val typeCode: Int, val seq: Long, val bytes: ByteArray)

    private var nextC2sSeq = 1L
    private var lastS2cSeq = 1L
    private val accepted = object : LinkedHashMap<Long, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > CACHE_SIZE
    }
    private var pending: Pending? = null

    var latestSnapshot: RuleSnapshot = initialSnapshot
        private set

    var isValid: Boolean = true
        private set

    val pendingRequest: ByteArray? get() = pending?.bytes?.copyOf()

    /** Licznik bliski wyczerpania: potrzebny świeży handshake na zachowanym łączu. */
    val needsRekey: Boolean get() = nextC2sSeq >= SEQ_LIMIT || lastS2cSeq >= SEQ_LIMIT

    init {
        accepted[1L] = readyFrame
    }

    fun send(request: SessionRequest): ByteArray {
        check(isValid) { "Sesja unieważniona" }
        check(pending == null) { "Poprzednie żądanie nie zostało rozstrzygnięte" }
        check(!needsRekey) { "Wymagany nowy handshake" }
        val (type, payload) = when (request) {
            is SessionRequest.Open -> MessageType.OPEN to Commands.openPayload(request.autoCloseOnDisconnect)
            SessionRequest.Close -> MessageType.CLOSE to Commands.CLOSE_PAYLOAD
            SessionRequest.Ping -> MessageType.PING to Commands.PING_PAYLOAD
            SessionRequest.OpenPairingWindow -> MessageType.OPEN_PAIRING_WINDOW to Commands.OPEN_PAIRING_WINDOW_PAYLOAD
            SessionRequest.ListClients -> MessageType.LIST_CLIENTS to Commands.LIST_CLIENTS_PAYLOAD
            is SessionRequest.RemoveClient -> MessageType.REMOVE_CLIENT to request.clientId
            is SessionRequest.SetClientName -> MessageType.SET_CLIENT_NAME to Commands.setClientNamePayload(request.name)
            is SessionRequest.OtaBegin -> MessageType.OTA_BEGIN to OtaPayloads.begin(request.imageSize, request.sha256)
            is SessionRequest.OtaData -> MessageType.OTA_DATA to OtaPayloads.data(request.offset, request.data)
            SessionRequest.OtaEnd -> MessageType.OTA_END to OtaPayloads.END_PAYLOAD
            SessionRequest.OtaAbort -> MessageType.OTA_ABORT to OtaPayloads.ABORT_PAYLOAD
        }
        val seq = nextC2sSeq++
        val bytes = GcmFrames.seal(keys.c2sKey, keys.c2sIv, type.code, sessionId, seq, payload).encode()
        pending = Pending(request, type.code, seq, bytes)
        return bytes.copyOf()
    }

    fun onFrame(bytes: ByteArray): SessionEvent {
        if (!isValid) return SessionEvent.Ignored("Sesja unieważniona")
        val frame = try {
            Frame.decode(bytes)
        } catch (e: ProtocolException) {
            return fail(e.message ?: "Zła ramka")
        }
        val h = frame.header
        if (h.messageType == MessageType.HANDSHAKE_REJECT) return SessionEvent.Ignored("HANDSHAKE_REJECT w sesji READY")
        if (h.sessionId != sessionId) return SessionEvent.Ignored("Inny session_id")

        if (h.seq <= lastS2cSeq) {
            // seq 0 ma wyłącznie CHALLENGE tej sesji; jego kopia może dotrzeć po READY (retransmisja BEGIN).
            val known = if (h.seq == 0L) challengeFrame else accepted[h.seq]
            return if (known?.contentEquals(bytes) == true) SessionEvent.Duplicate
            else fail("Powtórzony seq ${h.seq} z inną treścią lub poza pamięcią duplikatów")
        }
        if (h.seq != lastS2cSeq + 1) return fail("Luka seq: ${h.seq} po $lastS2cSeq")
        val type = h.messageType
        if (type == null || type.direction != Direction.S2C || type.phase != MessagePhase.SESSION) {
            return fail("Typ ${h.typeCode} niedozwolony w sesji s2c")
        }
        val plain = GcmFrames.open(keys.s2cKey, keys.s2cIv, frame) ?: return fail("Zły tag GCM")
        lastS2cSeq = h.seq
        accepted[h.seq] = bytes.copyOf()

        return try {
            when (type) {
                MessageType.RULE_STATUS -> RuleSnapshot.decode(plain).let { SessionEvent.RuleStatus(it, apply(it)) }
                MessageType.PONG -> onPong(RuleSnapshot.decode(plain))
                MessageType.COMMAND_ACK -> onAck(CommandAck.decode(plain))
                MessageType.CLIENT_LIST -> onClientList(ClientList.decode(plain))
                MessageType.OTA_ACK -> onOtaAck(OtaAck.decode(plain))
                else -> fail("Nieoczekiwany ${type.name}")
            }
        } catch (e: ProtocolException) {
            fail(e.message ?: "Zły payload")
        }
    }

    /** Unieważnia sesję (np. po trzecim T_response lub utracie łącza); zwraca żądanie o nieznanym wyniku. */
    fun invalidate(): SessionRequest? {
        isValid = false
        keys.wipe()
        return pending?.request.also { pending = null }
    }

    private fun onPong(snapshot: RuleSnapshot): SessionEvent {
        val p = pending
        if (p?.request != SessionRequest.Ping) return fail("PONG bez oczekującego PING")
        pending = null
        return SessionEvent.Pong(snapshot, apply(snapshot))
    }

    private fun onAck(ack: CommandAck): SessionEvent {
        val p = pending
        if (p == null || p.request == SessionRequest.Ping || p.request.isOta || ack.requestTypeCode != p.typeCode || ack.requestSeq != p.seq) {
            return fail("ACK bez zgodnej referencji żądania")
        }
        pending = null
        return SessionEvent.CommandAcknowledged(p.request, ack, apply(ack.snapshot))
    }

    private fun onClientList(list: ClientList): SessionEvent {
        val p = pending
        if (p == null || p.request != SessionRequest.ListClients || list.requestSeq != p.seq) {
            return fail("CLIENT_LIST bez zgodnej referencji żądania")
        }
        pending = null
        return SessionEvent.ClientListReceived(p.request, list)
    }

    private fun onOtaAck(ack: OtaAck): SessionEvent {
        val p = pending
        if (p == null || !p.request.isOta || ack.requestTypeCode != p.typeCode || ack.requestSeq != p.seq) {
            return fail("OTA_ACK bez zgodnej referencji żądania")
        }
        pending = null
        return SessionEvent.OtaAcknowledged(p.request, ack)
    }

    private fun apply(snapshot: RuleSnapshot): Boolean {
        if (java.lang.Long.compareUnsigned(snapshot.revision, latestSnapshot.revision) <= 0) return false
        latestSnapshot = snapshot
        return true
    }

    private fun fail(reason: String): SessionEvent = SessionEvent.Failed(reason, invalidate())

    private companion object {
        const val CACHE_SIZE = 32
        const val SEQ_LIMIT = Long.MAX_VALUE - 1
    }
}
