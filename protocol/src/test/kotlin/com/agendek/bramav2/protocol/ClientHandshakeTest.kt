package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientHandshakeTest {

    private val gate = FakeGate()
    private val clientId = ByteArray(16) { 0x44 }
    private val clientKey = ByteArray(32) { 0x55 }.also { gate.clients[clientId.toHex()] = it }

    private fun newHandshake(key: ByteArray = clientKey) =
        SessionHandshake(key, gate.deviceId, clientId, CountingNonceSource(1))

    private fun establish(): Pair<ClientSession, RuleSnapshot> {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val est = hs.onFrame(gate.receive(confirm)!!) as HandshakeStep.Established
        return est.session to est.session.latestSnapshot
    }

    @Test
    fun fullSessionHandshakeReachesReadyWithSnapshot() {
        val (session, snapshot) = establish()
        assertEquals(AutoClosePhase.DISARMED, snapshot.phase)
        assertEquals(0x0102030405060708L, session.sessionId)
        assertEquals(snapshot, session.latestSnapshot)
    }

    @Test
    fun callerZeroingItsBuffersDoesNotAffectHandshake() {
        val key = clientKey.copyOf()
        val device = gate.deviceId.copyOf()
        val id = clientId.copyOf()
        val hs = SessionHandshake(key, device, id, CountingNonceSource(1))
        key.fill(0)
        device.fill(0)
        id.fill(0)
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        assertTrue(hs.onFrame(gate.receive(confirm)!!) is HandshakeStep.Established)
    }

    /** Wszystkie pola obiektu (z klasami bazowymi), bez polegania na nazwach. */
    private fun fieldValues(obj: Any): List<Any?> =
        generateSequence<Class<*>>(obj.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { f -> f.isAccessible = true; f.get(obj) }
            .toList()

    private fun assertHoldsNoSecret(obj: Any, secret: ByteArray) {
        val values = fieldValues(obj)
        assertTrue(values.none { it is ByteArray && it.contentEquals(secret) })
        assertTrue(values.none { it is SessionKeys })
    }

    @Test
    fun keysAreWipedAfterRejectedAttempt() {
        val wrongKey = ByteArray(32) { 0x66 }
        val hs = SessionHandshake(wrongKey, gate.deviceId, clientId, CountingNonceSource(1))
        assertTrue(hs.onFrame(gate.receive(hs.pendingRequest!!)!!) is HandshakeStep.Rejected)
        assertHoldsNoSecret(hs, wrongKey)
    }

    @Test
    fun keysAreWipedAfterFailedAttempt() {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val ready = gate.receive(confirm)!!
        ready[ready.size - 1] = (ready.last().toInt() xor 1).toByte()
        assertTrue(hs.onFrame(ready) is HandshakeStep.Failed)
        assertHoldsNoSecret(hs, clientKey)
    }

    @Test
    fun setupKeyIsWipedAfterPairing() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = (pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send).frame
        pair.onFrame(gate.receive(confirm)!!)
        assertTrue(pair.onFrame(gate.receive(pair.commit()!!)!!) is HandshakeStep.Paired)
        assertHoldsNoSecret(pair, gate.setupKey)
    }

    @Test
    fun establishedSessionKeepsWorkingAfterHandshakeWipesItsKey() {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val session = (hs.onFrame(gate.receive(confirm)!!) as HandshakeStep.Established).session
        assertHoldsNoSecret(hs, clientKey)
        assertTrue(session.onFrame(gate.receive(session.send(SessionRequest.Ping))!!) is SessionEvent.Pong)
    }

    @Test
    fun abortAfterConfirmWipesSecretsAndEndsAttempt() {
        val hs = newHandshake()
        val challenge = gate.receive(hs.pendingRequest!!)!!
        hs.onFrame(challenge)
        hs.abort()
        assertHoldsNoSecret(hs, clientKey)
        assertNull(hs.pendingRequest)
        assertTrue(hs.onFrame(challenge) is HandshakeStep.Ignored)
    }

    @Test
    fun abortAfterGrantWithoutCommitWipesSetupKey() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = (pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send).frame
        assertTrue(pair.onFrame(gate.receive(confirm)!!) is HandshakeStep.GrantReceived)
        pair.abort() // np. nieudany zapis K_client w Keystore
        assertHoldsNoSecret(pair, gate.setupKey)
        assertNull(pair.commit()) // próba zakończona: brak COMMIT, bez wyjątku w aktorze
    }

    @Test
    fun commitAfterAttemptEndedByFrameReturnsNull() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = (pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val grant = gate.receive(confirm)!!
        pair.onFrame(grant)
        // ramka bieżącej próby, która nie jest kopią GRANT — kończy próbę w trakcie zapisu K_client
        val stray = gate.sealRaw(MessageType.PAIR_DONE, 2, ByteArray(0))
        val sid = Frame.decode(grant).header.sessionId
        assertEquals(sid, Frame.decode(stray).header.sessionId)
        assertTrue(pair.onFrame(stray) is HandshakeStep.Failed)
        assertNull(pair.commit())
    }

    @Test
    fun commitBeforeGrantIsProgrammingError() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        assertThrows(IllegalStateException::class.java) { pair.commit() }
    }

    @Test
    fun grantWithWrongKeyLengthFailsAttempt() {
        gate.issuedClientKey = ByteArray(31) { 0x33 }
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = (pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send).frame
        assertTrue(pair.onFrame(gate.receive(confirm)!!) is HandshakeStep.Failed)
        assertHoldsNoSecret(pair, gate.setupKey)
    }

    @Test
    fun longTermKeyMustBe32Bytes() {
        for (size in listOf(0, 16, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionHandshake(ByteArray(size), gate.deviceId, clientId, CountingNonceSource(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PairingHandshake(ByteArray(size), gate.deviceId, clientId, CountingNonceSource(1))
            }
        }
    }

    @Test
    fun beginIsRetransmittableIdenticalBytes() {
        val hs = newHandshake()
        val first = hs.pendingRequest!!.copyOf()
        assertArrayEquals(first, hs.pendingRequest)
        assertEquals(0L, Frame.decode(first).header.sessionId)
    }

    @Test
    fun challengeWithWrongKeyFailsAttempt() {
        val hs = newHandshake(ByteArray(32) { 0x66 }) // klient z innym kluczem niż zna sterownik
        val response = gate.receive(hs.pendingRequest!!)!!
        val step = hs.onFrame(response)
        assertTrue(step is HandshakeStep.Rejected) // sterownik odrzucił BEGIN
        assertEquals(HandshakeRejectReason.AUTH_FAILED, (step as HandshakeStep.Rejected).reason)
        assertNull(hs.pendingRequest)
    }

    @Test
    fun forgedChallengeFailsAttempt() {
        val hs = newHandshake()
        val challenge = Frame.decode(gate.receive(hs.pendingRequest!!)!!)
        val forged = Frame(challenge.header, challenge.body, ByteArray(32)).encode()
        assertTrue(hs.onFrame(forged) is HandshakeStep.Failed)
    }

    @Test
    fun challengeForOtherDeviceOrNonceFails() {
        val otherKey = clientKey
        val hs = newHandshake()
        // CHALLENGE poprawnie podpisany, ale z obcym device_id
        val bad = HandshakeFrames.challenge(
            HandshakeKind.SESSION, otherKey, 5, ByteArray(16) { 9 }, clientId, CountingNonceSource(1).next(32), ByteArray(32),
        ).encode()
        assertTrue(hs.onFrame(bad) is HandshakeStep.Failed)
    }

    @Test
    fun challengeEchoingDifferentClientNonceIsNotAccepted() {
        val hs = newHandshake()
        val begin = hs.pendingRequest!!
        val other = HandshakeFrames.challenge(
            HandshakeKind.SESSION, clientKey, 5, gate.deviceId, clientId, ByteArray(32) { 0x7f }, ByteArray(32),
        ).encode()
        assertTrue(hs.onFrame(other) is HandshakeStep.Ignored)
        assertArrayEquals(begin, hs.pendingRequest) // nadal czeka na CHALLENGE do własnego BEGIN
    }

    @Test
    fun lateChallengeFromPreviousAttemptIsIgnored() {
        val previous = newHandshake()
        val staleChallenge = gate.receive(previous.pendingRequest!!)!!

        val current = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(50))
        assertTrue(current.onFrame(staleChallenge) is HandshakeStep.Ignored)

        val confirm = (current.onFrame(gate.receive(current.pendingRequest!!)!!) as HandshakeStep.Send).frame
        assertTrue(current.onFrame(gate.receive(confirm)!!) is HandshakeStep.Established)
    }

    @Test
    fun wrongSizeNonceSourceIsRejectedUpFront() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionHandshake(clientKey, gate.deviceId, clientId) { ByteArray(16) }
        }
    }

    @Test
    fun challengeForDifferentClientIdFails() {
        val hs = newHandshake()
        val bad = HandshakeFrames.challenge(
            HandshakeKind.SESSION, clientKey, 5, gate.deviceId, ByteArray(16), CountingNonceSource(1).next(32), ByteArray(32),
        ).encode()
        assertTrue(hs.onFrame(bad) is HandshakeStep.Failed)
    }

    @Test
    fun rejectHonouredOnlyForMatchingSessionId() {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val sid = Frame.decode(confirm).header.sessionId
        fun reject(s: Long) = Frame(FrameHeader(0x0E, s, 0, 1), byteArrayOf(3), ByteArray(0)).encode()

        assertTrue(hs.onFrame(reject(0)) is HandshakeStep.Ignored)
        val step = hs.onFrame(reject(sid))
        assertEquals(HandshakeRejectReason.RATE_LIMITED, (step as HandshakeStep.Rejected).reason)
    }

    @Test
    fun reflectedClientFrameDuringHandshakeFails() {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val step = hs.onFrame(confirm) as HandshakeStep.Failed
        assertTrue(step.reason.contains("kierun"))
    }

    @Test
    fun duplicateChallengeAfterConfirmIsIgnored() {
        val hs = newHandshake()
        val challenge = gate.receive(hs.pendingRequest!!)!!
        hs.onFrame(challenge)
        assertTrue(hs.onFrame(challenge) is HandshakeStep.Duplicate)
    }

    @Test
    fun readyWithTamperedTagFails() {
        val hs = newHandshake()
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val ready = gate.receive(confirm)!!
        ready[ready.size - 1] = (ready.last().toInt() xor 1).toByte()
        assertTrue(hs.onFrame(ready) is HandshakeStep.Failed)
    }

    @Test
    fun pairingFlowGrantsKeyThenCommits() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = (pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send).frame
        val grant = pair.onFrame(gate.receive(confirm)!!) as HandshakeStep.GrantReceived
        assertArrayEquals(gate.issuedClientKey, grant.clientKey)
        assertNull(pair.pendingRequest) // COMMIT dopiero po bezpiecznym zapisie K_client

        val commit = pair.commit()!!
        assertEquals(1L, Frame.decode(commit).header.seq)
        assertTrue(pair.onFrame(gate.receive(commit)!!) is HandshakeStep.Paired)
        assertArrayEquals(gate.issuedClientKey, gate.clients[clientId.toHex()])
    }

    @Test
    fun pairingRejectsWrongSetupKey() {
        val pair = PairingHandshake(ByteArray(32) { 1 }, gate.deviceId, clientId, CountingNonceSource(5))
        val step = pair.onFrame(gate.receive(pair.pendingRequest!!)!!)
        assertTrue(step is HandshakeStep.Rejected)
    }

    @Test
    fun pairBeginUsesPairDomain() {
        val pair = PairingHandshake(gate.setupKey, gate.deviceId, clientId, CountingNonceSource(5))
        val f = Frame.decode(pair.pendingRequest!!)
        assertEquals(MessageType.PAIR_BEGIN, f.header.messageType)
        assertTrue(HandshakeFrames.verifyBegin(HandshakeKind.PAIR, gate.setupKey, gate.deviceId, f))
    }
}
