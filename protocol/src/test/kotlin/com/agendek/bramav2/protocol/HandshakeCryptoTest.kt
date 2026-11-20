package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeCryptoTest {

    private val deviceId = ByteArray(16) { 0x10 }
    private val clientId = ByteArray(16) { 0x20 }
    private val clientNonce = ByteArray(32) { 0x30 }
    private val serverNonce = ByteArray(32) { 0x40 }
    private val sid = 0x0102030405060708L
    private val key = ByteArray(32) { 0x55 }

    private val transcript = Transcript(deviceId, clientId, clientNonce, serverNonce, sid)

    @Test
    fun transcriptIs104BytesEndingWithLe64SessionId() {
        val t = transcript.encode()
        assertEquals(104, t.size)
        assertEquals("0807060504030201", t.copyOfRange(96, 104).toHex())
        assertArrayEquals(deviceId + clientId + clientNonce + serverNonce, t.copyOf(96))
    }

    @Test
    fun keyScheduleFollowsHkdfWithSpecLabels() {
        val keys = KeySchedule.derive(HandshakeKind.SESSION, key, transcript)
        val prk = Crypto.hkdfExtract(Crypto.sha256(transcript.encode()), key)
        fun exp(label: String, n: Int) = Crypto.hkdfExpand(prk, label.toByteArray(Charsets.US_ASCII), n)
        assertArrayEquals(exp("BRAMA/2/SESSION/c2s/key", 32), keys.c2sKey)
        assertArrayEquals(exp("BRAMA/2/SESSION/s2c/key", 32), keys.s2cKey)
        assertArrayEquals(exp("BRAMA/2/SESSION/c2s/iv", 4), keys.c2sIv)
        assertArrayEquals(exp("BRAMA/2/SESSION/s2c/iv", 4), keys.s2cIv)
    }

    @Test
    fun sessionKeysWipeZeroesAllArrays() {
        val keys = KeySchedule.derive(HandshakeKind.SESSION, key, transcript)
        keys.wipe()
        for (a in listOf(keys.c2sKey, keys.s2cKey, keys.c2sIv, keys.s2cIv)) {
            assertTrue(a.all { it == 0.toByte() })
        }
    }

    @Test
    fun pairAndSessionKeysDiffer() {
        val p = KeySchedule.derive(HandshakeKind.PAIR, key, transcript)
        val s = KeySchedule.derive(HandshakeKind.SESSION, key, transcript)
        assertNotEquals(p.c2sKey.toHex(), s.c2sKey.toHex())
    }

    @Test
    fun beginTagCoversHeaderPayloadAndDeviceId() {
        val f = HandshakeFrames.begin(HandshakeKind.PAIR, key, deviceId, clientId, clientNonce)
        assertEquals(MessageType.PAIR_BEGIN.code, f.header.typeCode)
        assertEquals(0L, f.header.sessionId)
        assertArrayEquals(clientId + clientNonce, f.body)
        val expected = Crypto.mac(key, "BRAMA/2/PAIR_BEGIN", f.header.encode() + f.body + deviceId)
        assertArrayEquals(expected, f.tag)
        assertEquals(true, HandshakeFrames.verifyBegin(HandshakeKind.PAIR, key, deviceId, f))
        assertEquals(false, HandshakeFrames.verifyBegin(HandshakeKind.PAIR, key, ByteArray(16), f))
        assertEquals(false, HandshakeFrames.verifyBegin(HandshakeKind.SESSION, key, deviceId, f))
    }

    @Test
    fun challengeTagCoversHeaderAndPayload() {
        val f = HandshakeFrames.challenge(HandshakeKind.SESSION, key, sid, deviceId, clientId, clientNonce, serverNonce)
        assertEquals(MessageType.SESSION_CHALLENGE.code, f.header.typeCode)
        assertEquals(96, f.body.size)
        assertArrayEquals(Crypto.mac(key, "BRAMA/2/SESSION_CHALLENGE", f.header.encode() + f.body), f.tag)
        assertEquals(true, HandshakeFrames.verifyChallenge(HandshakeKind.SESSION, key, f))
    }

    @Test
    fun confirmTagCoversHeaderAndTranscript() {
        val f = HandshakeFrames.confirm(HandshakeKind.SESSION, key, transcript)
        assertEquals(sid, f.header.sessionId)
        assertEquals(0, f.body.size)
        assertArrayEquals(Crypto.mac(key, "BRAMA/2/SESSION_CONFIRM", f.header.encode() + transcript.encode()), f.tag)
        assertEquals(true, HandshakeFrames.verifyConfirm(HandshakeKind.SESSION, key, transcript, f))
    }

    @Test
    fun challengeBodyWithWrongLengthIsProtocolError() {
        assertThrows(ProtocolException::class.java) { ChallengeBody.decode(ByteArray(95)) }
        assertThrows(ProtocolException::class.java) { ChallengeBody.decode(ByteArray(97)) }
    }

    @Test
    fun gcmFrameUsesIvAndLe64SeqNonceWithHeaderAad() {
        val keys = KeySchedule.derive(HandshakeKind.SESSION, key, transcript)
        val f = GcmFrames.seal(keys.c2sKey, keys.c2sIv, MessageType.OPEN.code, sid, 1, byteArrayOf(1))
        val nonce = keys.c2sIv + le64(1)
        val sealed = Crypto.aesGcmSeal(keys.c2sKey, nonce, byteArrayOf(1), f.header.encode())
        assertArrayEquals(sealed, f.body + f.tag)
        assertEquals(39, f.encode().size)
        assertArrayEquals(byteArrayOf(1), GcmFrames.open(keys.c2sKey, keys.c2sIv, f))
    }

    @Test
    fun gcmOpenRejectsSingleBitFlipsAnywhere() {
        val keys = KeySchedule.derive(HandshakeKind.SESSION, key, transcript)
        val bytes = GcmFrames.seal(keys.s2cKey, keys.s2cIv, MessageType.PONG.code, sid, 2, ByteArray(15)).encode()
        for (i in bytes.indices) {
            val m = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val frame = try { Frame.decode(m) } catch (_: ProtocolException) { continue }
            assertNull("bit flip @ $i", GcmFrames.open(keys.s2cKey, keys.s2cIv, frame))
        }
    }

    @Test
    fun gcmSealRefusesHandshakeTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            GcmFrames.seal(key, ByteArray(4), MessageType.SESSION_BEGIN.code, sid, 1, ByteArray(0))
        }
    }
}
