package com.agendek.bramav2.protocol

/**
 * Minimalna symulacja strony ESP32 do testów klienta: weryfikuje handshake, wysyła READY/ACK/PONG
 * i zachowuje cache odpowiedzi. Nie implementuje automatu reguły poza prostym ARMED/DISARMED.
 */
class FakeGate(
    val deviceId: ByteArray = ByteArray(16) { 0x11 },
    val setupKey: ByteArray = ByteArray(32) { 0x22 },
    private val nonces: NonceSource = CountingNonceSource(0x70),
    /** Skalary EC-JPAKE serwera; deterministyczne w generatorze wektorów. */
    private val serverScalars: () -> ScalarSource = { SecureRandomScalars() },
) {
    val clients = mutableMapOf<String, ByteArray>()   // client_id hex → K_client
    var nextSessionId = 0x0102030405060708L
    var revision = 1L
    var phase = AutoClosePhase.DISARMED
    var impulses = mutableListOf<String>()

    /** Zarządzanie telefonami: nazwy, telefony z sesją READY (żądający zawsze) i właściciel reguły (hex client_id). */
    val clientNames = mutableMapOf<String, String>()
    val connected = mutableSetOf<String>()
    var ruleOwner: String? = null
    private var sessionClientId = ByteArray(16)

    /** Liczba przyjętych OPEN_PAIRING_WINDOW (bez impulsu). */
    var windowsOpened = 0
    var issuedClientKey = ByteArray(32) { 0x33 }

    /** PIN sterownika (11.4); `K_pin` z ukończonego PAKE zastępuje `K_setup` w PAIR_BEGIN, jednorazowo. */
    var pin: ByteArray = "482915".toByteArray(Charsets.US_ASCII)
    var pakeKey: ByteArray? = null
        private set

    /** Sekret `S` ostatniego ukończonego PAKE (do wektorów 11.4). */
    var lastPakeSecret: ByteArray? = null
        private set
    private var pake: EcJpake? = null
    private var pakeTranscript = ByteArray(0)

    private var kind = HandshakeKind.SESSION
    private var key = ByteArray(0)
    private var transcript: Transcript? = null
    private var keys: SessionKeys? = null
    private var s2cSeq = 0L
    private var lastC2s = 0L
    private val cache = mutableMapOf<String, ByteArray>()
    private var pendingClientId: ByteArray? = null

    fun snapshot() = RuleSnapshot(
        revision, phase,
        if (phase == AutoClosePhase.DISARMED) OwnerRelation.NONE else OwnerRelation.SELF,
        RuleReason.NONE, 0,
    )

    /** Zwraca odpowiedź albo `null`. */
    fun receive(bytes: ByteArray): ByteArray? {
        cache[bytes.toHex()]?.let { return it }
        val f = Frame.decode(bytes)
        val response = when (f.header.messageType) {
            MessageType.SESSION_BEGIN -> begin(HandshakeKind.SESSION, f)
            MessageType.PAIR_BEGIN -> begin(HandshakeKind.PAIR, f)
            MessageType.PAKE_ROUND1 -> pakeRound1(f)
            MessageType.PAKE_ROUND2 -> pakeRound2(f)
            MessageType.SESSION_CONFIRM, MessageType.PAIR_CONFIRM -> confirm(f)
            MessageType.PAIR_COMMIT -> commit(f)
            else -> command(f)
        }
        if (response != null) cache[bytes.toHex()] = response
        return response
    }

    private fun begin(k: HandshakeKind, f: Frame): ByteArray? {
        val clientId = f.body.copyOf(16)
        val pinKey = pakeKey
        val beginKey = if (k == HandshakeKind.PAIR) pinKey ?: setupKey else clients[clientId.toHex()] ?: return reject(f, 1)
        if (!HandshakeFrames.verifyBegin(k, beginKey, deviceId, f)) {
            if (k == HandshakeKind.PAIR) pakeKey = null // K_pin jednorazowy (11.4)
            return reject(f, 1)
        }
        kind = k
        key = beginKey
        val sid = nextSessionId++
        val t = Transcript(deviceId, clientId, f.body.copyOfRange(16, 48), nonces.next(32), sid)
        transcript = t
        return HandshakeFrames.challenge(k, key, sid, deviceId, clientId, t.clientNonce, t.serverNonce).encode()
    }

    private fun pakeRound1(f: Frame): ByteArray? {
        val srv = EcJpake(JpakeRole.SERVER, pin, serverScalars())
        try {
            srv.readRound1(f.body)
        } catch (e: ProtocolException) {
            return reject(f, 1)
        }
        pake = srv
        pakeKey = null
        val reply = srv.writeRound1()
        val sid = nextSessionId++
        pakeTranscript = f.body + reply
        return Frame(FrameHeader(MessageType.PAKE_ROUND1_REPLY.code, sid, 0, reply.size), reply, ByteArray(0)).encode()
    }

    private fun pakeRound2(f: Frame): ByteArray? {
        val srv = pake ?: return reject(f, 1)
        try {
            srv.readRound2(f.body)
        } catch (e: ProtocolException) {
            return reject(f, 1)
        }
        val reply = srv.writeRound2()
        val secret = srv.deriveSecret()
        lastPakeSecret = secret.copyOf()
        pakeKey = HandshakeFrames.pinSetupKey(secret, pakeTranscript + f.body + reply)
        pake = null
        return Frame(FrameHeader(MessageType.PAKE_ROUND2_REPLY.code, f.header.sessionId, 0, reply.size), reply, ByteArray(0)).encode()
    }

    private fun confirm(f: Frame): ByteArray? {
        val t = transcript ?: return null
        if (!HandshakeFrames.verifyConfirm(kind, key, t, f)) return reject(f, 1)
        val k = KeySchedule.derive(kind, key, t)
        keys = k
        sessionClientId = t.clientId
        s2cSeq = 1
        lastC2s = 0
        return if (kind == HandshakeKind.PAIR) {
            pendingClientId = t.clientId
            GcmFrames.seal(k.s2cKey, k.s2cIv, MessageType.PAIR_GRANT.code, t.sessionId, 1, issuedClientKey).encode()
        } else {
            GcmFrames.seal(k.s2cKey, k.s2cIv, MessageType.SESSION_READY.code, t.sessionId, 1, snapshot().encode()).encode()
        }
    }

    private fun commit(f: Frame): ByteArray? {
        val k = keys ?: return null
        GcmFrames.open(k.c2sKey, k.c2sIv, f) ?: return null
        clients[pendingClientId!!.toHex()] = issuedClientKey
        return GcmFrames.seal(k.s2cKey, k.s2cIv, MessageType.PAIR_DONE.code, f.header.sessionId, 2, ByteArray(0)).encode()
    }

    private fun command(f: Frame): ByteArray? {
        val k = keys ?: return null
        if (f.header.seq != lastC2s + 1) return null
        val p = GcmFrames.open(k.c2sKey, k.c2sIv, f) ?: return null
        lastC2s = f.header.seq
        val type = f.header.messageType
        if (type == MessageType.PING) return send(MessageType.PONG, snapshot().encode())
        if (type == MessageType.LIST_CLIENTS && p.isEmpty()) return send(MessageType.CLIENT_LIST, clientList(f.header.seq).encode())
        if (type != null && type.code in MessageType.OTA_BEGIN.code..MessageType.OTA_ABORT.code) return ota(type, f.header.seq, p)
        val result = when {
            type == MessageType.OPEN && p.size == 1 && (p[0].toInt() == 0 || p[0].toInt() == 1) -> {
                changePhase(if (p[0].toInt() == 1) AutoClosePhase.MONITORING else AutoClosePhase.DISARMED)
                impulses += "OPEN"
                AckResult.OUTPUT_TRIGGERED
            }
            type == MessageType.CLOSE && p.isEmpty() -> {
                changePhase(AutoClosePhase.DISARMED)
                impulses += "CLOSE"
                AckResult.OUTPUT_TRIGGERED
            }
            type == MessageType.OPEN_PAIRING_WINDOW && p.isEmpty() -> {
                windowsOpened++
                AckResult.WINDOW_OPENED
            }
            type == MessageType.REMOVE_CLIENT -> removeClient(p)
            type == MessageType.SET_CLIENT_NAME -> setClientName(p)
            type == MessageType.OPEN || type == MessageType.CLOSE || type == MessageType.OPEN_PAIRING_WINDOW ||
                type == MessageType.LIST_CLIENTS -> AckResult.INVALID_ARGUMENT
            else -> AckResult.UNSUPPORTED_TYPE
        }
        return send(MessageType.COMMAND_ACK, CommandAck(f.header.typeCode, result, f.header.seq, snapshot()).encode())
    }

    private fun clientList(requestSeq: Long) = ClientList(
        requestSeq,
        clients.keys.map { hex ->
            val id = hex.hexToBytes()
            ClientEntry(id, clientNames[hex] ?: "", isSelf = id.contentEquals(sessionClientId),
                isConnected = id.contentEquals(sessionClientId) || hex in connected, ownsRule = hex == ruleOwner)
        },
    )

    private fun removeClient(p: ByteArray): AckResult {
        if (p.size != 16) return AckResult.INVALID_ARGUMENT
        val hex = p.toHex()
        return when {
            p.contentEquals(sessionClientId) -> AckResult.CANNOT_REMOVE_SELF
            hex !in clients -> AckResult.CLIENT_NOT_FOUND
            hex == ruleOwner -> AckResult.CLIENT_OWNS_RULE
            else -> {
                clients.remove(hex)
                clientNames.remove(hex)
                connected.remove(hex)
                AckResult.CLIENT_REMOVED
            }
        }
    }

    private fun setClientName(p: ByteArray): AckResult {
        if (p.isEmpty() || p.size != 1 + (p[0].toInt() and 0xff)) return AckResult.INVALID_ARGUMENT
        val name = try {
            ClientName.decode(p.copyOfRange(1, p.size))
        } catch (_: ProtocolException) {
            return AckResult.INVALID_ARGUMENT
        }
        if (name.isEmpty()) clientNames.remove(sessionClientId.toHex()) else clientNames[sessionClientId.toHex()] = name
        return AckResult.NAME_SET
    }

    /** Przyjęte bajty bieżącego transferu OTA (15.5); [otaResult] wymusza wynik następnego OTA_ACK. */
    var otaNextOffset = 0L
    var otaResult: OtaResult? = null

    private fun ota(type: MessageType, seq: Long, p: ByteArray): ByteArray {
        val forced = otaResult
        val result = forced ?: OtaResult.OK
        if (forced == null) {
            when (type) {
                MessageType.OTA_BEGIN, MessageType.OTA_ABORT -> otaNextOffset = 0
                MessageType.OTA_DATA -> otaNextOffset += p.size - 4
                else -> Unit
            }
        }
        return send(MessageType.OTA_ACK, OtaAck(type.code, result, seq, otaNextOffset).encode())
    }

    /** Asynchroniczna zmiana reguły po stronie sterownika. */
    fun ruleStatus(newPhase: AutoClosePhase): ByteArray {
        changePhase(newPhase)
        return send(MessageType.RULE_STATUS, snapshot().encode())
    }

    /** Ramka s2c z dowolnym seq — do symulacji błędów strony sterownika. */
    fun sealRaw(type: MessageType, seq: Long, payload: ByteArray): ByteArray {
        val k = keys!!
        return GcmFrames.seal(k.s2cKey, k.s2cIv, type.code, transcript!!.sessionId, seq, payload).encode()
    }

    private fun changePhase(p: AutoClosePhase) {
        if (p != phase) revision++
        phase = p
    }

    private fun send(type: MessageType, payload: ByteArray): ByteArray {
        val k = keys!!
        return GcmFrames.seal(k.s2cKey, k.s2cIv, type.code, transcript!!.sessionId, ++s2cSeq, payload).encode()
    }

    private fun reject(f: Frame, code: Int) =
        Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, f.header.sessionId, 0, 1), byteArrayOf(code.toByte()), ByteArray(0)).encode()
}

/** Deterministyczne nonce do testów. */
class CountingNonceSource(private var seed: Int) : NonceSource {
    override fun next(size: Int): ByteArray = ByteArray(size) { (seed + it).toByte() }.also { seed++ }
}
