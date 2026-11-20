package com.agendek.bramav2.store

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.CredentialLoad
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.HistoryEntry
import com.agendek.bramav2.domain.LastKnownRule
import com.agendek.bramav2.domain.PresenceRecord
import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val deviceId = ByteArray(16) { 0x11 }
    private val clientId = ByteArray(16) { 0x22 }
    private val key = ByteArray(32) { (0x40 + it).toByte() }

    /** Odwracalne „szyfrowanie” do testów formatu; `locked` symuluje Keystore przed pierwszym odblokowaniem. */
    private class FakeKeyWrapper : KeyWrapper {
        var locked = false
        var corrupt = false
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (locked) throw KeyUnavailableException("zablokowany")
            if (corrupt) throw CorruptKeyException("zły tag")
            return wrapped.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private val wrapper = FakeKeyWrapper()
    private fun credentials() = FileCredentialStore(tmp.root, wrapper)

    @Test
    fun credentialRoundTripsAllStates() = runBlocking {
        val store = credentials()
        assertEquals(CredentialLoad.None, store.load())
        store.saveUnconfirmed(deviceId, clientId, key)
        val loaded = credentials().load() as CredentialLoad.Present
        assertEquals(CredentialState.UNCONFIRMED, loaded.credential.state)
        assertArrayEquals(deviceId, loaded.credential.deviceId)
        assertArrayEquals(clientId, loaded.credential.clientId)
        assertArrayEquals(key, credentials().clientKey())
        store.markActive()
        assertEquals(CredentialState.ACTIVE, (credentials().load() as CredentialLoad.Present).credential.state)
    }

    @Test
    fun saveReplacesUnconfirmedAtomically() = runBlocking {
        val store = credentials()
        store.saveUnconfirmed(deviceId, clientId, key)
        val key2 = ByteArray(32) { 7 }
        store.saveUnconfirmed(deviceId, clientId, key2)
        assertArrayEquals(key2, credentials().clientKey())
        assertEquals(listOf(FileCredentialStore.FILE_NAME), tmp.root.list()!!.toList()) // bez plików tymczasowych
    }

    @Test
    fun markActiveKeepsKey() = runBlocking {
        val store = credentials()
        store.saveUnconfirmed(deviceId, clientId, key)
        store.markActive()
        assertArrayEquals(key, store.clientKey())
    }

    @Test
    fun deleteRemovesFile() = runBlocking {
        val store = credentials()
        store.saveUnconfirmed(deviceId, clientId, key)
        store.delete()
        assertEquals(CredentialLoad.None, credentials().load())
        assertFalse(tmp.root.resolve(FileCredentialStore.FILE_NAME).exists())
    }

    @Test
    fun keystoreUnavailableLoadsLocked() = runBlocking {
        credentials().saveUnconfirmed(deviceId, clientId, key)
        wrapper.locked = true
        assertEquals(CredentialLoad.Locked, credentials().load())
        assertTrue(tmp.root.resolve(FileCredentialStore.FILE_NAME).exists())
    }

    @Test
    fun corruptWrappedKeyLoadsNoneWithoutDeleting() = runBlocking {
        credentials().saveUnconfirmed(deviceId, clientId, key)
        wrapper.corrupt = true
        assertEquals(CredentialLoad.None, credentials().load())
        assertTrue(tmp.root.resolve(FileCredentialStore.FILE_NAME).exists())
    }

    @Test
    fun corruptCredentialFileLoadsNoneWithoutDeleting() = runBlocking {
        val f = tmp.root.resolve(FileCredentialStore.FILE_NAME)
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(CredentialLoad.None, credentials().load())
        assertTrue(f.exists())
    }

    @Test
    fun clientKeyIsNeverWrittenInPlain() = runBlocking {
        credentials().saveUnconfirmed(deviceId, clientId, key)
        val bytes = tmp.root.resolve(FileCredentialStore.FILE_NAME).readBytes()
        assertFalse(bytes.toList().windowed(key.size).any { it == key.toList() })
    }

    private fun entry(t: Long, open: Boolean? = true) = HistoryEntry(
        t, if (open == null) GateCommand.Close else GateCommand.Open(open), CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED,
    )

    @Test
    fun historyAppendsAndTrimsTo200() = runBlocking {
        val store = FileHistoryStore(tmp.root.resolve("history.jsonl"))
        repeat(205) { store.append(entry(it.toLong())) }
        store.trim(200)
        val all = FileHistoryStore(tmp.root.resolve("history.jsonl")).entries.first()
        assertEquals(200, all.size)
        assertEquals(204L, all.first().timeMillis)
        assertEquals(5L, all.last().timeMillis)
    }

    @Test
    fun historyNewestFirst() = runBlocking {
        val store = FileHistoryStore(tmp.root.resolve("history.jsonl"))
        store.append(entry(1, open = false))
        store.append(HistoryEntry(2, GateCommand.Close, CommandOutcome.OUTCOME_UNKNOWN, null))
        val all = store.entries.first()
        assertEquals(listOf(2L, 1L), all.map { it.timeMillis })
        assertEquals(GateCommand.Close, all[0].command)
        assertEquals(null, all[0].ackResult)
        assertEquals(GateCommand.Open(false), all[1].command)
    }

    @Test
    fun corruptHistoryLinesAreSkipped() = runBlocking {
        val f = tmp.root.resolve("history.jsonl")
        val store = FileHistoryStore(f)
        store.append(entry(1))
        f.appendText("{zepsute\n")
        store.append(entry(3))
        assertEquals(listOf(3L, 1L), FileHistoryStore(f).entries.first().map { it.timeMillis })
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun closeScope() = scope.cancel()

    private fun settings() = DataStoreSettings(DataStoreSettings.create(tmp.root.resolve("settings.preferences_pb"), scope))

    @Test
    fun corruptSettingsFileFallsBackToDefaults() = runBlocking {
        tmp.root.resolve("settings.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42))
        val s = settings()
        assertEquals(false, s.autoCloseDefault.first())
        assertEquals(true, s.presenceNotificationEnabled.first())
        assertEquals(null, s.load())
        s.setGateName("Wjazd") // plik zastąpiony — zapis działa
        assertEquals("Wjazd", s.gateName.first())
    }

    @Test
    fun settingsDefaults() = runBlocking {
        val s = settings()
        assertEquals(false, s.autoCloseDefault.first())
        assertEquals(true, s.presenceNotificationEnabled.first())
        assertEquals(null, s.gateName.first())
        s.setGateName("Wjazd")
        s.setAutoCloseDefault(true)
        assertEquals("Wjazd", s.gateName.first())
        assertEquals(true, s.autoCloseDefault.first())
    }

    @Test
    fun ruleCacheRoundTrip() = runBlocking {
        val s = settings()
        assertEquals(null, s.load())
        val snap = RuleSnapshot(7, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0)
        s.save(LastKnownRule(snap, 123L))
        val loaded = s.load()!!
        assertEquals(snap, loaded.snapshot)
        assertEquals(123L, loaded.receivedAtMillis)
        s.clear()
        assertEquals(null, s.load())
    }

    @Test
    fun presenceRecordRoundTrip() = runBlocking {
        val s = settings()
        assertEquals(PresenceRecord(quiet = false, ruleSuppressed = false), s.presence.load())
        s.presence.save(PresenceRecord(quiet = true, ruleSuppressed = true))
        assertEquals(PresenceRecord(quiet = true, ruleSuppressed = true), s.presence.load())
    }
}
