package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.domain.LinkInfo
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.OtaResult
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Device Information, `LinkInfo` w `Ready` i żądania OTA przez sesję (15.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkOtaTest {

    private val image = ByteArray(1_000) { (it * 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(image)

    private fun LinkHarness.ready(): Long = connect().also { settle() }

    private fun LinkHarness.otaResults(id: Long) = eventsOf(id).filterIsInstance<LinkEvent.OtaResult>()

    /** Wysyła żądanie i zwraca jego wynik (ten sam obiekt żądania). */
    private fun LinkHarness.roundTrip(id: Long, request: SessionRequest): LinkEvent.OtaResult {
        val before = otaResults(id).size
        link.send(request)
        settle(5_000)
        val results = otaResults(id)
        assertEquals(before + 1, results.size)
        return results.last().also { assertSame(request, it.request) }
    }

    private fun chunks(): List<SessionRequest.OtaData> =
        (image.indices step 470).map { SessionRequest.OtaData(it.toLong(), image.copyOfRange(it, minOf(it + 470, image.size))) }

    @Test
    fun readyCarriesFirmwareVersionOtaBitAndMtu() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        val ready = h.eventsOf(id).filterIsInstance<LinkEvent.Ready>().single()
        assertEquals(LinkInfo(firmwareVersion = "0.1.0", otaSupported = true, mtu = 247, pairingWindowSupported = true), ready.info)
        // Odczyt przed bondem i CCCD (8.2: bez szyfrowania).
        assertTrue(h.radio.calls.indexOf("readFirmwareRevision") < h.radio.calls.indexOf("enableIndications"))
    }

    @Test
    fun firmwareRevisionFailureDoesNotBreakConnection() = runTest {
        val h = LinkHarness(this)
        h.radio.firmwareRevisionFails = true
        val id = h.ready()
        val ready = h.eventsOf(id).filterIsInstance<LinkEvent.Ready>().single()
        assertNull(ready.info.firmwareVersion)
    }

    @Test
    fun infoWithoutBit6IsNotOtaCapable() = runTest {
        val h = LinkHarness(this)
        h.radio.devicesInScan = listOf(com.agendek.bramav2.ble.sim.SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId, capabilities = 0x3F))
        val id = h.ready()
        assertEquals(false, h.eventsOf(id).filterIsInstance<LinkEvent.Ready>().single().info.otaSupported)
    }

    @Test
    fun pairingConnectionSkipsFirmwareRevision() = runTest {
        val h = LinkHarness(this)
        h.link.pair(com.agendek.bramav2.protocol.PairingSecret.Qr(
            com.agendek.bramav2.protocol.ProvisioningQr(h.gate.deviceId, h.gate.setupKey, "482915".toByteArray()),
        ), ByteArray(16) { 0x66 })
        h.settle()
        assertTrue("readFirmwareRevision" !in h.radio.calls)
    }

    @Test
    fun fullTransferThenRestartWithNewVersion() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        assertEquals(OtaResult.OK, h.roundTrip(id, SessionRequest.OtaBegin(image.size.toLong(), sha)).ack.result)
        var expected = 0L
        for (chunk in chunks()) {
            val ack = h.roundTrip(id, chunk).ack
            expected += chunk.data.size
            assertEquals(OtaResult.OK, ack.result)
            assertEquals(expected, ack.nextOffset)
        }
        val end = h.roundTrip(id, SessionRequest.OtaEnd).ack
        assertEquals(MessageType.OTA_END.code, end.requestTypeCode)
        assertEquals(OtaResult.OK, end.result)
        h.settle(10_000) // restart sterownika → utrata łącza
        assertEquals("Disconnected", h.namesOf(id).last())

        val id2 = h.connect()
        h.settle()
        assertEquals("0.2.0", h.eventsOf(id2).filterIsInstance<LinkEvent.Ready>().single().info.firmwareVersion)
    }

    @Test
    fun commandBetweenChunksSharesSession() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.roundTrip(id, SessionRequest.OtaBegin(image.size.toLong(), sha))
        h.roundTrip(id, chunks()[0])
        h.link.send(SessionRequest.Close)
        h.settle(5_000)
        assertEquals(SessionRequest.Close, h.eventsOf(id).filterIsInstance<LinkEvent.CommandResult>().last().request)
        assertEquals(940L, h.roundTrip(id, chunks()[1]).ack.nextOffset)
    }

    @Test
    fun lostOtaAckIsRetransmittedAndAnsweredFromCache() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.roundTrip(id, SessionRequest.OtaBegin(image.size.toLong(), sha))
        h.radio.dropIndications = 1
        val chunk = chunks()[0]
        h.link.send(chunk)
        h.settle(20_000) // T_response, retransmisja identycznych bajtów, OTA_ACK z cache
        val result = h.otaResults(id).last()
        assertSame(chunk, result.request)
        assertEquals(470L, result.ack.nextOffset)
        assertEquals(470, h.gate.otaReceived) // duplikat nie zapisany ponownie
        assertEquals(2, h.gate.received.count { it == MessageType.OTA_DATA })
    }

    @Test
    fun thirdTimeoutIsUnknownForOtaRequest() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.dropIndications = 3
        val begin = SessionRequest.OtaBegin(image.size.toLong(), sha)
        h.link.send(begin)
        h.settle(60_000)
        assertTrue(h.eventsOf(id).any { it is LinkEvent.CommandUnknown && it.request === begin })
    }

    @Test
    fun smallMtuIsRejectedBySterownik() = runTest {
        val h = LinkHarness(this)
        h.radio.negotiatedMtu = 23
        val id = h.ready()
        assertEquals(23, h.eventsOf(id).filterIsInstance<LinkEvent.Ready>().single().info.mtu)
        assertEquals(OtaResult.LINK_TOO_SMALL, h.roundTrip(id, SessionRequest.OtaBegin(image.size.toLong(), sha)).ack.result)
    }

    @Test
    fun armedRuleRejectsBegin() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.pushRuleStatus(AutoClosePhase.MONITORING)
        h.settle(2_000)
        assertEquals(OtaResult.RULE_ACTIVE, h.roundTrip(id, SessionRequest.OtaBegin(image.size.toLong(), sha)).ack.result)
    }
}
