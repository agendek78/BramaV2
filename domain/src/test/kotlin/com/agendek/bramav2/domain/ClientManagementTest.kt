package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.ClientEntry
import com.agendek.bramav2.protocol.ClientList
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Lista telefonów na sterowniku, usuwanie i zmiana nazwy (client_management_design.md §5.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientManagementTest {

    private val me = ByteArray(16) { 0x44 }
    private val other = ByteArray(16) { 0x45 }
    private val list = ClientList(
        1,
        listOf(
            ClientEntry(me, "Pixel 8", isSelf = true, isConnected = true, ownsRule = false),
            ClientEntry(other, "Ania", isSelf = false, isConnected = false, ownsRule = false),
        ),
    )

    private suspend fun TestScope.ready(phoneName: String = ""): Harness {
        val h = Harness(backgroundScope, phoneName = phoneName)
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        h.link.sent.clear()
        return h
    }

    private suspend fun TestScope.ack(h: Harness, request: SessionRequest, type: Int, result: AckResult) {
        h.link.emit(LinkEvent.CommandResult(request, CommandAck(type, result, 1, snapshot(DISARMED)), true))
        runCurrent()
    }

    private suspend fun TestScope.listed(h: Harness) {
        h.repo.refreshClients()
        runCurrent()
        h.link.emit(LinkEvent.ClientList(h.link.sent.last(), list))
        runCurrent()
    }

    @Test
    fun availableOnlyOnReadyActiveSession() = runTest {
        val h = ready()
        assertTrue(h.state.clientsAvailable)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertFalse(h.state.clientsAvailable)
    }

    @Test
    fun refreshSendsListAndPublishesIt() = runTest {
        val h = ready()
        assertEquals(ClientsUi.Idle, h.state.clients)
        h.repo.refreshClients()
        runCurrent()
        assertEquals(listOf<SessionRequest>(SessionRequest.ListClients), h.link.sent)
        assertEquals(ClientsUi.Loading, h.state.clients)
        assertFalse(h.state.clientsAvailable) // jedno żądanie naraz (13.3)
        h.link.emit(LinkEvent.ClientList(h.link.sent.last(), list))
        runCurrent()
        assertEquals(ClientsUi.Ready(list.entries), h.state.clients)
        assertTrue(h.state.clientsAvailable)
        assertNull(h.state.lastCommand)
        assertTrue(h.history.appended.isEmpty())
    }

    @Test
    fun removeSendsRequestThenRefreshesList() = runTest {
        val h = ready()
        listed(h)
        h.link.sent.clear()
        h.repo.removeClient(other)
        runCurrent()
        val request = h.link.sent.single() as SessionRequest.RemoveClient
        assertArrayEquals(other, request.clientId)
        ack(h, request, 0x05, AckResult.CLIENT_REMOVED)
        assertEquals(UiMessage(MessageId.CLIENT_REMOVED), h.state.notification)
        assertEquals(SessionRequest.ListClients, h.link.sent.last()) // lista odświeżona
        assertNull(h.state.lastCommand)
    }

    @Test
    fun removalResultsMapToMessages() = runTest {
        val cases = listOf(
            AckResult.CLIENT_NOT_FOUND to MessageId.CLIENT_ALREADY_REMOVED,
            AckResult.CANNOT_REMOVE_SELF to MessageId.CLIENT_CANNOT_REMOVE_SELF,
            AckResult.CLIENT_OWNS_RULE to MessageId.CLIENT_OWNS_RULE,
            AckResult.STORAGE_FAILURE to MessageId.CLIENTS_STORAGE_FAILURE,
            AckResult.BUSY to MessageId.RESULT_BUSY,
            AckResult.INVALID_ARGUMENT to MessageId.RESULT_COMPATIBILITY_ERROR,
            AckResult.OUTPUT_TRIGGERED to MessageId.RESULT_COMPATIBILITY_ERROR,
        )
        for ((result, message) in cases) {
            val h = ready()
            h.repo.removeClient(other)
            runCurrent()
            ack(h, h.link.sent.single(), 0x05, result)
            assertEquals("$result", UiMessage(message), h.state.notification)
        }
    }

    @Test
    fun notFoundRefreshesListButOtherRefusalsDoNot() = runTest {
        val h = ready()
        h.repo.removeClient(other)
        runCurrent()
        ack(h, h.link.sent.single(), 0x05, AckResult.CLIENT_NOT_FOUND)
        assertEquals(SessionRequest.ListClients, h.link.sent.last()) // telefon znikł w międzyczasie
        val g = ready()
        g.repo.removeClient(other)
        runCurrent()
        ack(g, g.link.sent.single(), 0x05, AckResult.CLIENT_OWNS_RULE)
        assertEquals(1, g.link.sent.size)
    }

    @Test
    fun renameSendsValidNameAndIgnoresInvalid() = runTest {
        val h = ready()
        h.repo.renameSelf("a".repeat(21))
        h.repo.renameSelf("zły\u0001")
        runCurrent()
        assertTrue(h.link.sent.isEmpty())
        h.repo.renameSelf("Pixel 9")
        runCurrent()
        val request = h.link.sent.single() as SessionRequest.SetClientName
        assertEquals("Pixel 9", request.name)
        ack(h, request, 0x06, AckResult.NAME_SET)
        assertEquals(UiMessage(MessageId.CLIENT_NAME_SET), h.state.notification)
        assertEquals(SessionRequest.ListClients, h.link.sent.last())
    }

    @Test
    fun unknownOutcomeShowsErrorAndClearsRequest() = runTest {
        val h = ready()
        h.repo.removeClient(other)
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(h.link.sent.single()))
        runCurrent()
        assertEquals(ClientsUi.Error(UiMessage(MessageId.CLIENTS_RESULT_UNKNOWN)), h.state.clients)
        assertNull(h.state.lastCommand)
        assertFalse(h.state.rule.refreshing)
    }

    @Test
    fun gateCommandWaitsForClientsResult() = runTest {
        val h = ready()
        h.repo.removeClient(other)
        runCurrent()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals(1, h.link.sent.size) // CLOSE czeka (13.3)
        ack(h, h.link.sent.single(), 0x05, AckResult.CLIENT_REMOVED)
        assertTrue(h.link.sent.contains(SessionRequest.Close))
    }

    @Test
    fun ignoredWhileCommandInFlightOrDisconnected() = runTest {
        val h = ready()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        h.repo.refreshClients()
        h.repo.removeClient(other)
        h.repo.renameSelf("X")
        runCurrent()
        assertEquals(listOf<SessionRequest>(SessionRequest.Close), h.link.sent)
    }

    @Test
    fun mismatchedResultRestartsSession() = runTest {
        val h = ready()
        h.repo.removeClient(other)
        runCurrent()
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, CommandAck(0x02, AckResult.OUTPUT_TRIGGERED, 1, snapshot(DISARMED)), true))
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun listForForeignRequestRestartsSession() = runTest {
        val h = ready()
        h.repo.refreshClients()
        runCurrent()
        h.link.emit(LinkEvent.ClientList(SessionRequest.ListClients, list)) // ten sam obiekt: poprawna odpowiedź
        runCurrent()
        assertFalse("disconnect" in h.link.calls)
        val g = ready()
        g.link.emit(LinkEvent.ClientList(SessionRequest.ListClients, list)) // bez żądania w toku
        runCurrent()
        assertTrue("disconnect" in g.link.calls)
    }

    @Test
    fun sessionLossDropsPendingClientsRequestAndLoadingState() = runTest {
        val h = ready()
        h.repo.refreshClients()
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(ClientsUi.Idle, h.state.clients)
    }

    @Test
    fun ownNameIsSentOnceAfterRegistration() = runTest {
        val deviceId = FakeCredentialStore.DEVICE_ID
        val qr = "brama://provision?v=2&id=${deviceId.toHex()}&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 })}&p=482915"
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None), phoneName = "Google Pixel 8 Pro Max XL")
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(qr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, deviceId.copyOf()))
        runCurrent()
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.sent.clear()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        val request = h.link.sent.filterIsInstance<SessionRequest.SetClientName>().single()
        assertEquals("Google Pixel 8 Pro M", request.name) // przycięte do 20 B
        h.link.emit(LinkEvent.CommandResult(request, CommandAck(0x06, AckResult.NAME_SET, 1, snapshot(DISARMED)), true))
        runCurrent()
        assertEquals(UiMessage(MessageId.REGISTRATION_DONE), h.state.notification) // bez dodatkowego komunikatu
    }

    @Test
    fun ownNameNotSentWithoutPhoneNameOrOnPlainSession() = runTest {
        val h = ready(phoneName = "Pixel")
        assertTrue(h.link.sent.none { it is SessionRequest.SetClientName })
    }
}
