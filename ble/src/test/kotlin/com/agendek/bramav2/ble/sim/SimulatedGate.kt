package com.agendek.bramav2.ble.sim

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.ClientEntry
import com.agendek.bramav2.protocol.ClientList
import com.agendek.bramav2.protocol.ClientName
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.EcJpake
import com.agendek.bramav2.protocol.JpakeRole
import com.agendek.bramav2.protocol.ProtocolException
import com.agendek.bramav2.protocol.Frame
import com.agendek.bramav2.protocol.FrameHeader
import com.agendek.bramav2.protocol.GcmFrames
import com.agendek.bramav2.protocol.HandshakeFrames
import com.agendek.bramav2.protocol.HandshakeKind
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.KeySchedule
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.NonceSource
import com.agendek.bramav2.protocol.OtaAck
import com.agendek.bramav2.protocol.OtaPayloads
import com.agendek.bramav2.protocol.OtaResult
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionKeys
import com.agendek.bramav2.protocol.Transcript
import com.agendek.bramav2.protocol.hexToBytes
import com.agendek.bramav2.protocol.toHex

/** Deterministyczne nonce do testów. */
class CountingNonces(private var seed: Int = 0x70) : NonceSource {
    override fun next(size: Int): ByteArray = ByteArray(size) { (seed + it).toByte() }.also { seed++ }
}

/**
 * Symulowany sterownik ESP32 po stronie protokołu (spec 11–13), jedna sesja naraz: handshake SESSION/PAIR,
 * GRANT/COMMIT/DONE, ACK/PONG/RULE_STATUS, cache odpowiedzi po bajtach żądania (13.4), odmowy HANDSHAKE_REJECT.
 */
class SimulatedGate(
    val deviceId: ByteArray = ByteArray(16) { 0x11 },
    val setupKey: ByteArray = ByteArray(32) { 0x22 },
    private val nonces: NonceSource = CountingNonces(),
) {
    val clients = mutableMapOf<String, ByteArray>()
    var issuedClientKey = ByteArray(32) { 0x33 }

    /** PIN sterownika i okno rejestracji (11.4); po 5 nieudanych potwierdzeniach PIN-u okno się zamyka. */
    var pin: ByteArray = "482915".toByteArray(Charsets.US_ASCII)
    var windowOpen = true

    /** Zarządzanie telefonami: nazwy, telefony z sesją READY (żądający zawsze) i właściciel reguły (hex client_id). */
    val clientNames = mutableMapOf<String, String>()
    val connected = mutableSetOf<String>()
    var ruleOwner: String? = null
    private var sessionClientId = ByteArray(16)

    /** Przyjęte OPEN_PAIRING_WINDOW (phone_window_design). */
    var windowsOpened = 0
    var pinFailures = 0
        private set

    /** Fałszywy sterownik: odpowiada na PAIR_BEGIN po PAKE bez sprawdzenia tagu (nie zna PIN-u). */
    var forgeChallenge = false
    private var pake: EcJpake? = null
    private var pakeTranscript = ByteArray(0)
    private var pakeKey: ByteArray? = null
    /** Kolejne BEGIN do odrzucenia (po jednym kodzie na BEGIN). */
    val rejectBegins = ArrayDeque<HandshakeRejectReason>()

    var rejectNextBegin: HandshakeRejectReason?
        get() = rejectBegins.firstOrNull()
        set(value) {
            rejectBegins.clear()
            value?.let(rejectBegins::addLast)
        }
    val impulses = mutableListOf<String>()

    /** Typy kompletnych ramek odebranych od telefonu (także duplikatów). */
    val received = mutableListOf<MessageType?>()
    private var revision = 1L
    private var phase = AutoClosePhase.DISARMED

    private var kind = HandshakeKind.SESSION
    private var key = ByteArray(0)
    private var transcript: Transcript? = null
    private var keys: SessionKeys? = null
    private var s2cSeq = 0L
    private var lastC2s = 0L
    private val cache = mutableMapOf<String, ByteArray>()
    private var pendingClientId: ByteArray? = null

    val sessionId: Long? get() = transcript?.sessionId

    fun snapshot() = RuleSnapshot(
        revision, phase,
        if (phase == AutoClosePhase.DISARMED) OwnerRelation.NONE else OwnerRelation.SELF,
        RuleReason.NONE, 0,
    )

    /** Odpowiedź na kompletną ramkę od telefonu albo `null`. */
    fun receive(bytes: ByteArray): ByteArray? {
        received += runCatching { Frame.decode(bytes).header.messageType }.getOrNull()
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

    /** Asynchroniczna zmiana reguły — ramka RULE_STATUS w bieżącej sesji. */
    fun pushRuleStatus(newPhase: AutoClosePhase): ByteArray {
        changePhase(newPhase)
        return send(MessageType.RULE_STATUS, snapshot().encode())
    }

    private fun begin(k: HandshakeKind, f: Frame): ByteArray? {
        rejectBegins.removeFirstOrNull()?.let { return reject(f, it) }
        val clientId = f.body.copyOf(16)
        val pinKey = pakeKey
        val beginKey = if (k == HandshakeKind.PAIR) pinKey ?: setupKey else clients[clientId.toHex()] ?: return reject(f, HandshakeRejectReason.AUTH_FAILED)
        val forged = k == HandshakeKind.PAIR && pinKey != null && forgeChallenge
        if (!forged && !HandshakeFrames.verifyBegin(k, beginKey, deviceId, f)) {
            if (k == HandshakeKind.PAIR && pinKey != null) {
                pakeKey = null // jednorazowy
                if (++pinFailures >= 5) windowOpen = false
            }
            return reject(f, HandshakeRejectReason.AUTH_FAILED)
        }
        kind = k
        key = beginKey
        val sid = 0x0102030405060708L + (nonces.next(1)[0].toLong() and 0xff)
        val t = Transcript(deviceId, clientId, f.body.copyOfRange(16, 48), nonces.next(32), sid)
        transcript = t
        return HandshakeFrames.challenge(k, key, sid, deviceId, clientId, t.clientNonce, t.serverNonce).encode()
    }

    private fun pakeRound1(f: Frame): ByteArray? {
        if (!windowOpen) return reject(f, HandshakeRejectReason.AUTH_FAILED)
        val srv = EcJpake(JpakeRole.SERVER, pin)
        try {
            srv.readRound1(f.body)
        } catch (e: ProtocolException) {
            return reject(f, HandshakeRejectReason.AUTH_FAILED)
        }
        pake = srv
        pakeKey = null
        val reply = srv.writeRound1()
        pakeTranscript = f.body + reply
        val sid = 0x0A0B0C0D00000000L + (nonces.next(1)[0].toLong() and 0xff)
        return Frame(FrameHeader(MessageType.PAKE_ROUND1_REPLY.code, sid, 0, reply.size), reply, ByteArray(0)).encode()
    }

    private fun pakeRound2(f: Frame): ByteArray? {
        val srv = pake ?: return reject(f, HandshakeRejectReason.AUTH_FAILED)
        try {
            srv.readRound2(f.body)
        } catch (e: ProtocolException) {
            return reject(f, HandshakeRejectReason.AUTH_FAILED)
        }
        val reply = srv.writeRound2()
        pakeKey = HandshakeFrames.pinSetupKey(srv.deriveSecret(), pakeTranscript + f.body + reply)
        pake = null
        return Frame(FrameHeader(MessageType.PAKE_ROUND2_REPLY.code, f.header.sessionId, 0, reply.size), reply, ByteArray(0)).encode()
    }

    private fun confirm(f: Frame): ByteArray? {
        val t = transcript ?: return null
        if (!HandshakeFrames.verifyConfirm(kind, key, t, f)) return reject(f, HandshakeRejectReason.AUTH_FAILED)
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
        clients[pendingClientId!!.toHex()] = issuedClientKey.copyOf()
        return GcmFrames.seal(k.s2cKey, k.s2cIv, MessageType.PAIR_DONE.code, f.header.sessionId, 2, ByteArray(0)).encode()
    }

    private fun command(f: Frame): ByteArray? {
        val k = keys ?: return null
        if (f.header.sessionId != transcript?.sessionId || f.header.seq != lastC2s + 1) return null
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
            else -> AckResult.INVALID_ARGUMENT
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

    // --- OTA (15.5), uproszczony odpowiednik brama_ota ---

    /** Firmware Revision String działającego obrazu (Device Information, 8.2). */
    var firmwareVersion = "0.1.0"

    /** Wersja po restarcie z poprawnie przyjętym obrazem; `null` — „rollback” do bieżącej. */
    var versionAfterUpdate: String? = "0.2.0"

    /** Wynegocjowane MTU łącza (ustawia radio). */
    var linkMtu = 23

    /** Sterownik czeka na restart po OTA_OK na END (obsługuje radio). */
    var restartPending = false
    private var otaActive = false
    private var otaSize = 0L
    private var otaSha = ByteArray(0)
    private var otaData = java.io.ByteArrayOutputStream()

    /** Przyjęte bajty bieżącego transferu (do asercji). */
    val otaReceived: Int get() = otaData.size()

    private fun ota(type: MessageType, seq: Long, p: ByteArray): ByteArray {
        val result = when (type) {
            MessageType.OTA_BEGIN -> when {
                phase != AutoClosePhase.DISARMED -> OtaResult.RULE_ACTIVE
                linkMtu < 247 -> OtaResult.LINK_TOO_SMALL
                p.size != 36 -> OtaResult.INVALID_ARGUMENT
                else -> {
                    val size = p.getLe32(0)
                    if (size == 0L || size > OtaPayloads.MAX_IMAGE_SIZE) {
                        OtaResult.TOO_LARGE
                    } else {
                        otaActive = true
                        otaSize = size
                        otaSha = p.copyOfRange(4, 36)
                        otaData = java.io.ByteArrayOutputStream()
                        OtaResult.OK
                    }
                }
            }
            MessageType.OTA_DATA -> when {
                !otaActive -> OtaResult.NOT_IN_PROGRESS
                p.size < 5 -> abortOta(OtaResult.INVALID_ARGUMENT)
                p.getLe32(0) != otaData.size().toLong() -> abortOta(OtaResult.BAD_OFFSET)
                otaData.size() + p.size - 4 > otaSize -> abortOta(OtaResult.IMAGE_REJECTED)
                else -> {
                    otaData.write(p, 4, p.size - 4)
                    OtaResult.OK
                }
            }
            MessageType.OTA_END -> when {
                !otaActive -> OtaResult.NOT_IN_PROGRESS
                phase != AutoClosePhase.DISARMED -> OtaResult.RULE_ACTIVE // dane zostają, END można ponowić
                otaData.size().toLong() != otaSize ||
                    !java.security.MessageDigest.getInstance("SHA-256").digest(otaData.toByteArray()).contentEquals(otaSha) ->
                    abortOta(OtaResult.IMAGE_REJECTED)
                else -> {
                    otaActive = false
                    restartPending = true
                    OtaResult.OK
                }
            }
            else -> { // OTA_ABORT
                otaActive = false
                otaData = java.io.ByteArrayOutputStream()
                OtaResult.OK
            }
        }
        val next = if (otaActive) otaData.size().toLong() else if (type == MessageType.OTA_END && result == OtaResult.OK) otaSize else 0L
        return send(MessageType.OTA_ACK, OtaAck(type.code, result, seq, next).encode())
    }

    private fun abortOta(result: OtaResult): OtaResult {
        otaActive = false
        otaData = java.io.ByteArrayOutputStream()
        return result
    }

    /** Restart programowy: sesja i cache znikają, wersja zmienia się, jeśli obraz został przyjęty. */
    fun restart() {
        versionAfterUpdate?.let { firmwareVersion = it }
        transcript = null
        keys = null
        cache.clear()
        otaActive = false
    }

    private fun ByteArray.getLe32(offset: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((this[offset + i].toLong() and 0xff) shl (8 * i)) }

    private fun changePhase(p: AutoClosePhase) {
        if (p != phase) revision++
        phase = p
    }

    private fun send(type: MessageType, payload: ByteArray): ByteArray {
        val k = keys!!
        return GcmFrames.seal(k.s2cKey, k.s2cIv, type.code, transcript!!.sessionId, ++s2cSeq, payload).encode()
    }

    private fun reject(f: Frame, reason: HandshakeRejectReason) =
        Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, f.header.sessionId, 0, 1), byteArrayOf(reason.code.toByte()), ByteArray(0)).encode()
}
