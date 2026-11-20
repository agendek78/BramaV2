package com.agendek.bramav2.protocol

import java.math.BigInteger

/**
 * Deterministyczne scenariusze wymian dla testów firmware (strona serwera odtwarza te bajty).
 * Format pliku: `klucz=hex`, kolejność wymian `c2s_N`/`s2c_N`; dla ramek GCM także `*_plain` (jawny payload).
 * Opis kluczy: `resources/vectors/README.md`.
 */
object VectorScenarios {

    private val clientId = ByteArray(16) { (0x40 + it).toByte() }
    private val clientKey = ByteArray(32) { (0x50 + it).toByte() }
    private const val PIN = "482915"
    private val otherId = ByteArray(16) { (0x60 + it).toByte() }
    private val thirdId = ByteArray(16) { (0x70 + it).toByte() }

    /** Nazwa pliku → linie `klucz=hex` (komentarz w pierwszej linii). */
    fun all(): Map<String, List<String>> = linkedMapOf(
        "session.txt" to session(),
        "pair_qr.txt" to pairQr(),
        "pair_pin.txt" to pairPin(),
        "commands.txt" to commands(),
        "ota.txt" to ota(),
        "fragments.txt" to fragments(),
        "reject.txt" to reject(),
        "negative_session.txt" to negativeSession(),
        "negative_handshake.txt" to negativeHandshake(),
        "negative_client.txt" to negativeClient(),
        "negative_fragments.txt" to negativeFragments(),
    )

    /** Oczekiwany skutek przypadku c2s (`<przypadek>_expect`, vectors_design §2). */
    const val EXPECT_IGNORED = 0x00
    const val EXPECT_INVALIDATED = 0x01
    const val EXPECT_REPLY = 0x02
    const val EXPECT_COMMAND = 0x03

    private class Recorder(comment: String) {
        val lines = mutableListOf("# $comment")
        private var c2s = 0
        private var s2c = 0
        var keys: SessionKeys? = null

        fun put(key: String, value: ByteArray) {
            lines += "$key=${value.toHex()}"
        }

        fun c2s(bytes: ByteArray) = record("c2s_${++c2s}", bytes, keys?.let { it.c2sKey to it.c2sIv })
        fun s2c(bytes: ByteArray) = record("s2c_${++s2c}", bytes, keys?.let { it.s2cKey to it.s2cIv })

        private fun record(key: String, bytes: ByteArray, k: Pair<ByteArray, ByteArray>?): ByteArray {
            put(key, bytes)
            val f = Frame.decode(bytes)
            if (f.header.messageType?.tagKind == TagKind.GCM && k != null) {
                put("${key}_plain", GcmFrames.open(k.first, k.second, f) ?: error("Zły tag w $key"))
            }
            return bytes
        }
    }

    private fun gate() = FakeGate(
        deviceId = ByteArray(16) { (0x10 + it).toByte() },
        setupKey = ByteArray(32) { (0x20 + it).toByte() },
        nonces = CountingNonceSource(0x70),
        serverScalars = { scalars("BRAMA/test/ecjpake/server") },
    ).also {
        it.issuedClientKey = clientKey.copyOf()
        it.pin = PIN.toByteArray(Charsets.US_ASCII)
    }

    /** Wspólne wejścia, klucze sesji i wymiana BEGIN/CHALLENGE/CONFIRM/finał. */
    private fun handshake(r: Recorder, gate: FakeGate, kind: HandshakeKind, key: ByteArray, hs: ClientHandshake): ByteArray {
        r.put("device_id", gate.deviceId)
        r.put("client_id", clientId)
        r.put(if (kind == HandshakeKind.PAIR) "k_handshake" else "k_client", key)
        val begin = r.c2s(hs.pendingRequest!!)
        val challenge = r.s2c(gate.receive(begin)!!)
        val body = ChallengeBody.decode(Frame.decode(challenge).body)
        val sid = Frame.decode(challenge).header.sessionId
        r.put("client_nonce", body.clientNonce)
        r.put("server_nonce", body.serverNonce)
        r.put("session_id", le64(sid))
        val keys = KeySchedule.derive(kind, key, Transcript(body.deviceId, body.clientId, body.clientNonce, body.serverNonce, sid))
        r.put("c2s_key", keys.c2sKey)
        r.put("s2c_key", keys.s2cKey)
        r.put("c2s_iv", keys.c2sIv)
        r.put("s2c_iv", keys.s2cIv)
        val confirm = (hs.onFrame(challenge) as HandshakeStep.Send).frame
        r.c2s(confirm)
        r.keys = keys
        return r.s2c(gate.receive(confirm)!!)
    }

    private fun establish(r: Recorder, gate: FakeGate): Pair<ClientSession, SessionKeys> {
        gate.clients[clientId.toHex()] = clientKey
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(1))
        val ready = handshake(r, gate, HandshakeKind.SESSION, clientKey, hs)
        return (hs.onFrame(ready) as HandshakeStep.Established).session to r.keys!!
    }

    private fun session(): List<String> {
        val r = Recorder("SESSION: BEGIN, CHALLENGE, CONFIRM, READY (12.1)")
        establish(r, gate())
        return r.lines
    }

    private fun pair(r: Recorder, gate: FakeGate, key: ByteArray) {
        val hs = PairingHandshake(key, gate.deviceId, clientId, CountingNonceSource(1))
        val grant = handshake(r, gate, HandshakeKind.PAIR, key, hs)
        hs.onFrame(grant) as HandshakeStep.GrantReceived
        r.put("k_client", clientKey)
        val done = r.s2c(gate.receive(r.c2s(hs.commit()!!))!!)
        check(hs.onFrame(done) == HandshakeStep.Paired)
    }

    private fun pairQr(): List<String> {
        val r = Recorder("PAIR z K_setup (QR): BEGIN, CHALLENGE, CONFIRM, GRANT, COMMIT, DONE (11.3)")
        val gate = gate()
        pair(r, gate, gate.setupKey)
        return r.lines
    }

    private fun pairPin(): List<String> {
        val r = Recorder("PAKE (EC-JPAKE, PIN) + PAIR z K_pin (11.4); skalary: SHA-256(etykieta || i) mod (n-1) + 1")
        val gate = gate()
        r.put("pin", PIN.toByteArray(Charsets.US_ASCII))
        val pake = PinPakeHandshake(PairingSecret.Pin.parse(PIN)!!, scalars("BRAMA/test/ecjpake/client"))
        var out = gate.receive(r.c2s(pake.pendingRequest!!))!!
        while (true) {
            when (val step = pake.onFrame(r.s2c(out))) {
                is PakeStep.Send -> out = gate.receive(r.c2s(step.frame))!!
                is PakeStep.KeyReady -> {
                    r.put("pake_secret", gate.lastPakeSecret!!)
                    r.put("k_pin", step.pinKey)
                    break
                }
                else -> error("PAKE: $step")
            }
        }
        val pairPart = Recorder("")
        pair(pairPart, gate, gate.pakeKey!!)
        // Numeracja wymian ciągła: PAIR po czterech rundach PAKE.
        r.lines += pairPart.lines.drop(1).map { renumber(it, 2) }
        return r.lines
    }

    private fun renumber(line: String, shift: Int): String {
        val m = Regex("^(c2s|s2c)_(\\d+)(.*)$").find(line) ?: return line
        return "${m.groupValues[1]}_${m.groupValues[2].toInt() + shift}${m.groupValues[3]}"
    }

    private fun commands(): List<String> {
        val r = Recorder("Sesja + OPEN(false), OPEN(true), PING/PONG, RULE_STATUS, CLOSE, OPEN_PAIRING_WINDOW, zarządzanie telefonami (13.1)")
        val gate = gate()
        val (s, _) = establish(r, gate)
        for (req in listOf(SessionRequest.Open(false), SessionRequest.Open(true), SessionRequest.Ping)) {
            s.onFrame(r.s2c(gate.receive(r.c2s(s.send(req)))!!))
        }
        s.onFrame(r.s2c(gate.ruleStatus(AutoClosePhase.DISARMED)))
        s.onFrame(r.s2c(gate.receive(r.c2s(s.send(SessionRequest.Close)))!!))
        s.onFrame(r.s2c(gate.receive(r.c2s(s.send(SessionRequest.OpenPairingWindow)))!!))
        // Zarządzanie telefonami (client_management_design.md): trzy telefony, nazwa własna, lista, usunięcie, lista.
        r.put("other_client_id", otherId)
        r.put("third_client_id", thirdId)
        for (id in listOf(otherId, thirdId)) gate.clients[id.toHex()] = clientKey
        gate.clientNames[otherId.toHex()] = "Ania"
        gate.connected += otherId.toHex()
        for (req in listOf(
            SessionRequest.SetClientName("Pixel 8"), SessionRequest.ListClients,
            SessionRequest.RemoveClient(thirdId), SessionRequest.ListClients,
        )) {
            s.onFrame(r.s2c(gate.receive(r.c2s(s.send(req)))!!))
        }
        return r.lines
    }

    private fun ota(): List<String> {
        val r = Recorder("Sesja + OTA (15.5): BEGIN, DATA 470 B, DATA 1 B, END, ABORT; ACK z każdym wynikiem")
        val gate = gate()
        val (_, k) = establish(r, gate)
        val sid = Frame.decode(r.lines.first { it.startsWith("s2c_1=") }.substringAfter('=').hexToBytes()).header.sessionId
        var c2sSeq = 0L
        var s2cSeq = 1L
        fun c2s(type: MessageType, payload: ByteArray) =
            r.c2s(GcmFrames.seal(k.c2sKey, k.c2sIv, type.code, sid, ++c2sSeq, payload).encode())
        fun ack(type: MessageType, result: OtaResult, next: Long) = r.s2c(
            GcmFrames.seal(k.s2cKey, k.s2cIv, MessageType.OTA_ACK.code, sid, ++s2cSeq, OtaAck(type.code, result, c2sSeq, next).encode()).encode(),
        )
        val image = ByteArray(471) { (it * 7).toByte() }
        r.put("image", image)
        r.put("image_sha256", Crypto.sha256(image))
        c2s(MessageType.OTA_BEGIN, OtaPayloads.begin(image.size.toLong(), Crypto.sha256(image)))
        ack(MessageType.OTA_BEGIN, OtaResult.OK, 0)
        c2s(MessageType.OTA_DATA, OtaPayloads.data(0, image.copyOf(470)))
        ack(MessageType.OTA_DATA, OtaResult.OK, 470)
        c2s(MessageType.OTA_DATA, OtaPayloads.data(470, image.copyOfRange(470, 471)))
        ack(MessageType.OTA_DATA, OtaResult.OK, 471)
        c2s(MessageType.OTA_END, OtaPayloads.END_PAYLOAD)
        ack(MessageType.OTA_END, OtaResult.OK, 471)
        c2s(MessageType.OTA_ABORT, OtaPayloads.ABORT_PAYLOAD)
        ack(MessageType.OTA_ABORT, OtaResult.OK, 0)
        for (result in OtaResult.entries) r.put("ota_ack_${result.name.lowercase()}", OtaAck(MessageType.OTA_DATA.code, result, 9, 940).encode())
        return r.lines
    }

    private fun fragments(): List<String> {
        val r = Recorder("Fragmenty GATT (10.2): ramka OPEN przy MTU 23, ramka 512 B przy MTU 247 i 517")
        val open = GcmFrames.seal(ByteArray(32) { 1 }, ByteArray(4) { 2 }, MessageType.OPEN.code, 0x0102030405060708L, 1, byteArrayOf(1)).encode()
        val big = GcmFrames.seal(ByteArray(32) { 1 }, ByteArray(4) { 2 }, MessageType.OTA_DATA.code, 0x0102030405060708L, 2,
            OtaPayloads.data(0, ByteArray(OtaPayloads.MAX_DATA) { it.toByte() })).encode()
        for ((name, frame, mtu, tid) in listOf(Quad("mtu23", open, 23, 1), Quad("mtu247", big, 247, 2), Quad("mtu517", big, 517, 255))) {
            r.put("${name}_frame", frame)
            Fragmenter.fragment(frame, tid, TimingBudget(mtu, 30, 0)).forEachIndexed { i, f -> r.put("${name}_frag_$i", f) }
        }
        return r.lines
    }

    private data class Quad(val name: String, val frame: ByteArray, val mtu: Int, val tid: Int)

    private fun reject(): List<String> {
        val r = Recorder("HANDSHAKE_REJECT (12.4): kody 1–8, session_id z BEGIN (0) i z CHALLENGE")
        for (reason in HandshakeRejectReason.entries) {
            for (sid in listOf(0L, 0x0102030405060708L)) {
                r.put("reject_${reason.name.lowercase()}_${if (sid == 0L) "begin" else "session"}",
                    Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, sid, 0, 1), byteArrayOf(reason.code.toByte()), ByteArray(0)).encode())
            }
        }
        return r.lines
    }

    // --- Przypadki negatywne (16.3, vectors_design §3) ---

    private fun ByteArray.flipped(index: Int, mask: Int = 0x01): ByteArray =
        copyOf().also { val i = if (index < 0) size + index else index; it[i] = (it[i].toInt() xor mask).toByte() }

    private fun Recorder.expect(case: String, code: Int) = put("${case}_expect", byteArrayOf(code.toByte()))

    private fun rejectFrame(sessionId: Long, reason: Int) =
        Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, sessionId, 0, 1), byteArrayOf(reason.toByte()), ByteArray(0)).encode()

    private fun sessionIdOf(r: Recorder, key: String) =
        Frame.decode(r.lines.first { it.startsWith("$key=") }.substringAfter('=').hexToBytes()).header.sessionId

    private fun negativeSession(): List<String> {
        val r = Recorder("Przypadki negatywne sesji READY (16.3): baza jak commands.txt; _expect 00 ignorowana, 01 unieważnienie, 02 odpowiedź, 03 komenda")
        val gate = gate()
        val (_, k) = establish(r, gate)
        val sid = sessionIdOf(r, "s2c_1")
        fun c2s(type: Int, seq: Long, payload: ByteArray, keys: SessionKeys = k, sessionId: Long = sid) =
            GcmFrames.seal(keys.c2sKey, keys.c2sIv, type, sessionId, seq, payload).encode()
        fun s2c(type: Int, seq: Long, payload: ByteArray) = GcmFrames.seal(k.s2cKey, k.s2cIv, type, sid, seq, payload).encode()
        val openFalse = c2s(MessageType.OPEN.code, 1, byteArrayOf(0))

        fun case(name: String, frame: ByteArray, code: Int) {
            r.put("${name}_frame", frame)
            r.expect(name, code)
        }
        case("header_bit", openFalse.flipped(3, 0x02), EXPECT_INVALIDATED) // typ 0x01 → 0x03
        case("payload_bit", openFalse.flipped(FrameHeader.SIZE), EXPECT_INVALIDATED)
        case("tag_bit", openFalse.flipped(-1), EXPECT_INVALIDATED)
        case("seq_gap", c2s(MessageType.OPEN.code, 2, byteArrayOf(0)), EXPECT_INVALIDATED)
        r.put("same_seq_changed_first", openFalse)
        case("same_seq_changed", c2s(MessageType.CLOSE.code, 1, ByteArray(0)), EXPECT_INVALIDATED)

        // Poprzednia sesja: inne nonce, klucze i session_id tego samego klienta.
        val oldGate = gate().also { it.nextSessionId = 0x1112131415161718L; it.clients[clientId.toHex()] = clientKey }
        val oldHs = SessionHandshake(clientKey, oldGate.deviceId, clientId, CountingNonceSource(0x30))
        val oldChallenge = oldGate.receive(oldHs.pendingRequest!!)!!
        val oldReady = oldGate.receive((oldHs.onFrame(oldChallenge) as HandshakeStep.Send).frame)!!
        val oldBody = ChallengeBody.decode(Frame.decode(oldChallenge).body)
        val oldSid = Frame.decode(oldChallenge).header.sessionId
        check(oldHs.onFrame(oldReady) is HandshakeStep.Established)
        val oldKeys = KeySchedule.derive(HandshakeKind.SESSION, clientKey, Transcript(oldBody.deviceId, oldBody.clientId, oldBody.clientNonce, oldBody.serverNonce, oldSid))
        case("prev_session", c2s(MessageType.OPEN.code, 1, byteArrayOf(0), oldKeys, oldSid), EXPECT_IGNORED)

        case("version1", openFalse.copyOf().also { it[2] = 0x01 }, EXPECT_INVALIDATED)
        case("wrong_direction", c2s(MessageType.PONG.code, 1, gate.snapshot().encode()), EXPECT_INVALIDATED)
        case("reject_via_rx", rejectFrame(sid, 1), EXPECT_INVALIDATED)

        // Komendy z błędnym payloadem przechodzą kryptografię; wynik ACK ustala aktor (13.2).
        fun command(name: String, type: Int, payload: ByteArray, result: AckResult) {
            case(name, c2s(type, 1, payload), EXPECT_COMMAND)
            r.put("${name}_type", byteArrayOf(type.toByte()))
            r.put("${name}_plain", payload)
            val ack = CommandAck(type, result, 1, gate.snapshot()).encode()
            r.put("${name}_ack_plain", ack)
            r.put("${name}_ack", s2c(MessageType.COMMAND_ACK.code, 2, ack))
        }
        command("open_bool_02", MessageType.OPEN.code, byteArrayOf(2), AckResult.INVALID_ARGUMENT)
        command("close_extra_byte", MessageType.CLOSE.code, byteArrayOf(0), AckResult.INVALID_ARGUMENT)
        command("unknown_type", 0x60, byteArrayOf(1, 2, 3), AckResult.UNSUPPORTED_TYPE)

        // Rekey na zachowanym łączu (12.1): nowy BEGIN na READY, CHALLENGE z nowym session_id, potem READY.
        val rekey = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(0x09))
        val rekeyBegin = rekey.pendingRequest!!
        val rekeyChallenge = gate.receive(rekeyBegin)!!
        val rekeyBody = ChallengeBody.decode(Frame.decode(rekeyChallenge).body)
        r.put("rekey_session_id", le64(Frame.decode(rekeyChallenge).header.sessionId))
        r.put("rekey_server_nonce", rekeyBody.serverNonce)
        r.put("rekey_begin", rekeyBegin)
        r.put("rekey_challenge", rekeyChallenge)
        val rekeyConfirm = (rekey.onFrame(rekeyChallenge) as HandshakeStep.Send).frame
        r.put("rekey_confirm", rekeyConfirm)
        val rekeyReady = gate.receive(rekeyConfirm)!!
        check(rekey.onFrame(rekeyReady) is HandshakeStep.Established)
        r.put("rekey_ready", rekeyReady)
        return r.lines
    }

    private fun negativeHandshake(): List<String> {
        val r = Recorder("Przypadki negatywne handshake (16.3): bez sesji; _expect 02 = odpowiedź _reply")
        val gate = gate().also { it.clients[clientId.toHex()] = clientKey }
        r.put("device_id", gate.deviceId)
        r.put("client_id", clientId)
        r.put("k_client", clientKey)
        fun case(name: String, frame: ByteArray, reply: ByteArray) {
            r.put("${name}_frame", frame)
            r.expect(name, EXPECT_REPLY)
            r.put("${name}_reply", reply)
            // Strona odniesienia (FakeGate) odpowiada tak samo.
            check(gate.receive(frame).contentEquals(reply)) { name }
        }
        val authFailed = rejectFrame(0, HandshakeRejectReason.AUTH_FAILED.code)
        val otherId = ByteArray(16) { (0x90 + it).toByte() }
        case("begin_wrong_device_id", SessionHandshake(clientKey, otherId, clientId, CountingNonceSource(1)).pendingRequest!!, authFailed)
        case("begin_unknown_client", SessionHandshake(clientKey, gate.deviceId, otherId, CountingNonceSource(1)).pendingRequest!!, authFailed)
        case("begin_wrong_key", SessionHandshake(ByteArray(32) { 0x66 }, gate.deviceId, clientId, CountingNonceSource(1)).pendingRequest!!, authFailed)

        // CONFIRM i CHALLENGE poprawnej wymiany (session.txt) wysłane bez BEGIN / przez RX.
        val ref = Recorder("")
        establish(ref, gate())
        val sid = sessionIdOf(ref, "s2c_1")
        fun line(key: String) = ref.lines.first { it.startsWith("$key=") }.substringAfter('=').hexToBytes()
        // FakeGate nie modeluje tych odmów (minimalna symulacja) — oczekiwanie wprost ze spec 12.4, 13.1.
        for ((name, frame) in listOf("confirm_without_begin" to line("c2s_2"), "reflection" to line("s2c_1"))) {
            r.put("${name}_frame", frame)
            r.expect(name, EXPECT_REPLY)
            r.put("${name}_reply", rejectFrame(sid, HandshakeRejectReason.AUTH_FAILED.code))
        }

        // PAKE: runda 1 klienta Kotlina (deterministyczne skalary), poprawna i ze zmienionym dowodem.
        val round1 = PinPakeHandshake(PairingSecret.Pin.parse(PIN)!!, scalars("BRAMA/test/ecjpake/client")).pendingRequest!!
        r.put("pin", PIN.toByteArray(Charsets.US_ASCII))
        case("pake_bad_zkp", round1.flipped(-1), authFailed)
        // Poza oknem (11.4): sterownik odrzuca poprawną rundę; FakeGate nie modeluje okna — tylko zapis bajtów.
        r.put("pake_outside_window_frame", round1)
        r.expect("pake_outside_window", EXPECT_REPLY)
        r.put("pake_outside_window_reply", authFailed)
        return r.lines
    }

    private fun negativeClient(): List<String> {
        val r = Recorder("Przypadki negatywne po stronie telefonu (16.3): ramki s2c, snapshoty i ACK; opis w README")
        val gate = gate()
        val (s, k) = establish(r, gate)
        val sid = sessionIdOf(r, "s2c_1")
        fun c2s(type: Int, seq: Long, payload: ByteArray) = GcmFrames.seal(k.c2sKey, k.c2sIv, type, sid, seq, payload).encode()
        fun s2c(type: Int, seq: Long, payload: ByteArray) = GcmFrames.seal(k.s2cKey, k.s2cIv, type, sid, seq, payload).encode()

        // RULE_STATUS przed ACK (13.3), potem kopia starego ACK po nowszym statusie (13.4).
        val monitoring = RuleSnapshot(2, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0)
        val open = c2s(MessageType.OPEN.code, 1, byteArrayOf(1))
        check(open.contentEquals(s.send(SessionRequest.Open(true))))
        r.put("status_before_ack_c2s", open)
        r.put("status_before_ack_status_plain", monitoring.encode())
        r.put("status_before_ack_status", s2c(MessageType.RULE_STATUS.code, 2, monitoring.encode()))
        val ack = CommandAck(MessageType.OPEN.code, AckResult.OUTPUT_TRIGGERED, 1, monitoring).encode()
        r.put("status_before_ack_ack_plain", ack)
        r.put("status_before_ack_ack", s2c(MessageType.COMMAND_ACK.code, 3, ack))
        val disarmed = RuleSnapshot(3, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        r.put("old_ack_after_status_status", s2c(MessageType.RULE_STATUS.code, 4, disarmed.encode()))

        // Na świeżej sesji: CLOSE, potem ACK ze złym tagiem albo złą referencją.
        val close = c2s(MessageType.CLOSE.code, 1, ByteArray(0))
        val closeAck = CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 1, gate.snapshot()).encode()
        r.put("ack_c2s", close)
        r.put("ack_bad_tag_frame", s2c(MessageType.COMMAND_ACK.code, 2, closeAck).flipped(-1))
        r.put("ack_wrong_ref_frame", s2c(MessageType.COMMAND_ACK.code, 2, CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 9, gate.snapshot()).encode()))

        // Odmowy handshake (12.4): nieznany kod → AUTH_FAILED; inny session_id → ignorowana.
        r.put("reject_unknown_code_frame", rejectFrame(0, 0x7F))
        r.put("reject_other_sid_frame", rejectFrame(0x0909090909090909L, HandshakeRejectReason.UNAVAILABLE.code))

        // Każda poprawna kombinacja RuleSnapshot (12.2) i niespójne.
        for (phase in AutoClosePhase.entries) for (owner in OwnerRelation.entries) for (reason in RuleReason.entries) {
            val remaining = if (phase == AutoClosePhase.WAITING || phase == AutoClosePhase.BOOT_WAIT) 12_345L else 0L
            val snap = RuleSnapshot(0x0102030405060708L, phase, owner, reason, remaining).encode()
            if (runCatching { RuleSnapshot.decode(snap) }.isSuccess) {
                r.put("snapshot_${phase.name.lowercase()}_${owner.name.lowercase()}_${reason.name.lowercase()}", snap)
            }
        }
        val invalid = mapOf(
            "disarmed_self" to RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.SELF, RuleReason.NONE, 0).encode(),
            "monitoring_none" to RuleSnapshot(1, AutoClosePhase.MONITORING, OwnerRelation.NONE, RuleReason.NONE, 0).encode(),
            "waiting_reboot" to RuleSnapshot(1, AutoClosePhase.WAITING, OwnerRelation.SELF, RuleReason.REBOOT, 1000).encode(),
            "monitoring_remaining" to RuleSnapshot(1, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 5).encode(),
            "phase" to RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0).encode().also { it[8] = 6 },
            "owner" to RuleSnapshot(1, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0).encode().also { it[9] = 3 },
            "reason" to RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0).encode().also { it[10] = 7 },
        )
        for ((name, bytes) in invalid) r.put("snapshot_invalid_$name", bytes)

        // Jawny COMMAND_ACK dla każdego wyniku (13.2).
        val ackSnapshot = RuleSnapshot(0x0102030405060708L, AutoClosePhase.FAULT, OwnerRelation.OTHER, RuleReason.IO_FAULT, 0)
        for (result in AckResult.entries) {
            r.put("ack_${result.name.lowercase()}", CommandAck(MessageType.OPEN.code, result, 0x1112131415161718L, ackSnapshot).encode())
        }
        // CLIENT_LIST (13.1): jawne payloady (s2c) dla jednego i ośmiu wpisów oraz niepoprawne warianty.
        val one = ClientList(7, listOf(ClientEntry(clientId, "", isSelf = true, isConnected = true, ownsRule = false)))
        r.put("client_list_1", one.encode())
        val eight = ClientList(
            0x0102030405060708L,
            List(8) { i ->
                ClientEntry(ByteArray(16) { (i * 16 + it).toByte() }, if (i == 0) "a".repeat(20) else if (i == 1) "Zażółć" else "",
                    isSelf = i == 0, isConnected = i % 2 == 0, ownsRule = i == 3)
            },
        )
        r.put("client_list_8", eight.encode())
        r.put("client_list_count_over_8", eight.encode().copyOf(9 + 9 * 38).also { it[8] = 9 })
        r.put("client_list_bad_len", one.encode().copyOf(one.encode().size - 1))
        r.put("client_list_bad_utf8", ClientList(7, listOf(ClientEntry(clientId, "x", isSelf = true, isConnected = true, ownsRule = false))).encode().also { it[27] = 0xC3.toByte() })
        r.put("client_list_name_len_21", one.encode().also { it[26] = 21 })
        return r.lines
    }

    private fun negativeFragments(): List<String> {
        val r = Recorder("Przypadki negatywne fragmentacji (10.2): ramka OPEN przy MTU 23 (3 fragmenty)")
        val frame = GcmFrames.seal(ByteArray(32) { 1 }, ByteArray(4) { 2 }, MessageType.OPEN.code, 0x0102030405060708L, 1, byteArrayOf(1)).encode()
        val budget = TimingBudget(23, 30, 0)
        val tid1 = Fragmenter.fragment(frame, 1, budget)
        val tid2 = Fragmenter.fragment(frame, 2, budget)
        r.put("frame", frame)
        // Fragment 0 z nowym transfer_id porzuca niedokończony transfer i zaczyna nową ramkę.
        (listOf(tid1[0]) + tid2).forEachIndexed { i, f -> r.put("restart_new_tid_in_$i", f) }
        // Fragment 0 z tym samym transfer_id w trakcie składania: błąd kolejności, brak ramki.
        listOf(tid1[0], tid1[0], tid1[1], tid1[2]).forEachIndexed { i, f -> r.put("restart_same_tid_in_$i", f) }
        return r.lines
    }

    /** Skalary k_i = SHA-256(etykieta || i) mod (n − 1) + 1 (jak [EcJpakeInteropTest.deterministicScalars]). */
    private fun scalars(label: String): ScalarSource {
        var i = 0
        return ScalarSource {
            val h = Crypto.sha256(label.toByteArray(Charsets.US_ASCII) + byteArrayOf((i++).toByte()))
            BigInteger(1, h).mod(EcJpake.ORDER - BigInteger.ONE) + BigInteger.ONE
        }
    }
}
