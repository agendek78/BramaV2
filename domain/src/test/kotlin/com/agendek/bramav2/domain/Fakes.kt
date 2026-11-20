package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.NonceSource
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll

class FakeGateLink : GateLink {
    private val flow = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 64)

    /** Identyfikator ostatnio zwróconego połączenia (connect/pair); `emit` podpisuje nim zdarzenia. */
    var currentId = 0L
        private set
    private var nextId = 1L

    /** Liczba kolejnych subskrypcji, które od razu rzucą wyjątek (awaria strumienia `:ble`). */
    var failingSubscriptions = 0

    /** Liczba kolejnych subskrypcji, które kończą się od razu bez błędu (np. restart usługi `:ble`). */
    var completingSubscriptions = 0
    override val events: Flow<LinkMessage> = kotlinx.coroutines.flow.flow {
        if (failingSubscriptions > 0) {
            failingSubscriptions--
            throw IllegalStateException("strumień zdarzeń BLE przerwany")
        }
        if (completingSubscriptions > 0) {
            completingSubscriptions--
            return@flow
        }
        emitAll(flow)
    }
    val subscribers: Int get() = flow.subscriptionCount.value
    val calls = mutableListOf<String>()

    /** Wysłane żądania (te same obiekty — zdarzenia wyniku OTA muszą je zwracać). */
    val sent = mutableListOf<SessionRequest>()
    var lastConnectKey: ByteArray? = null
    var lastConnectClientId: ByteArray? = null
    var lastPairClientId: ByteArray? = null
    var lastPairSecret: PairingSecret? = null
    var sendFails = false
    var connectFails = false
    var pairFails = false
    var commitFails = false
    var abortFails = false
    var disconnectFails = false

    /** Zdarzenie bieżącego połączenia. */
    suspend fun emit(event: LinkEvent) = flow.emit(LinkMessage(currentId, event))

    /** Zdarzenie wskazanego (np. starego) połączenia. */
    suspend fun emitFrom(connectionId: Long, event: LinkEvent) = flow.emit(LinkMessage(connectionId, event))

    override fun connect(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long {
        if (connectFails) throw IllegalStateException("adapter BLE niedostępny")
        calls += "connect"
        lastConnectKey = clientKey.copyOf()
        lastConnectClientId = clientId.copyOf()
        currentId = nextId++
        return currentId
    }

    override fun repairBond(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long {
        if (connectFails) throw IllegalStateException("adapter BLE niedostępny")
        calls += "repairBond"
        lastConnectKey = clientKey.copyOf()
        lastConnectClientId = clientId.copyOf()
        currentId = nextId++
        return currentId
    }

    override fun pair(secret: PairingSecret, clientId: ByteArray): Long {
        if (pairFails) throw IllegalStateException("adapter BLE niedostępny")
        calls += "pair"
        lastPairSecret = secret
        lastPairClientId = clientId.copyOf()
        currentId = nextId++
        return currentId
    }

    override fun commitPairing() {
        if (commitFails) throw IllegalStateException("zapis GATT odrzucony")
        calls += "commit"
    }

    override fun abortPairing() {
        calls += "abort"
        if (abortFails) throw IllegalStateException("adapter BLE niedostępny")
    }

    override fun send(request: SessionRequest) {
        if (sendFails) throw IllegalStateException("zapis GATT odrzucony")
        calls += when (request) {
            is SessionRequest.Open -> "send:OPEN(${request.autoCloseOnDisconnect})"
            SessionRequest.Close -> "send:CLOSE"
            SessionRequest.Ping -> "send:PING"
            SessionRequest.OpenPairingWindow -> "send:OPEN_PAIRING_WINDOW"
            SessionRequest.ListClients -> "send:LIST_CLIENTS"
            is SessionRequest.RemoveClient -> "send:REMOVE_CLIENT"
            is SessionRequest.SetClientName -> "send:SET_CLIENT_NAME(${request.name})"
            is SessionRequest.OtaBegin -> "send:OTA_BEGIN(${request.imageSize})"
            is SessionRequest.OtaData -> "send:OTA_DATA(${request.offset},${request.data.size})"
            SessionRequest.OtaEnd -> "send:OTA_END"
            SessionRequest.OtaAbort -> "send:OTA_ABORT"
        }
        sent += request
    }

    override fun disconnect() {
        calls += "disconnect"
        if (disconnectFails) throw IllegalStateException("adapter BLE niedostępny")
    }
}

class FakeCredentialStore(
    var current: CredentialLoad = CredentialLoad.None,
    val key: ByteArray = ByteArray(32) { 0x55 },
) : CredentialStore {
    var saveFails = false
    var keyFails = false
    var markActiveFails = false
    var deleteFails = false
    var loadThrows: Throwable? = null
    var saveThrows: Throwable? = null
    var deleted = false
    val saved = mutableListOf<String>()
    var markActiveCalls = 0

    val state: CredentialState?
        get() = (current as? CredentialLoad.Present)?.credential?.state

    override suspend fun load(): CredentialLoad {
        loadThrows?.let { throw it }
        return current
    }
    override suspend fun clientKey(): ByteArray {
        if (keyFails) throw IllegalStateException("Keystore zablokowany")
        return key.copyOf()
    }

    override suspend fun saveUnconfirmed(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray) {
        if (saveFails) throw IllegalStateException("zapis Keystore nieudany")
        saveThrows?.let { throw it }
        saved += clientId.toHex()
        current = CredentialLoad.Present(StoredCredential(CredentialState.UNCONFIRMED, deviceId.copyOf(), clientId.copyOf()))
    }

    override suspend fun markActive() {
        markActiveCalls++
        if (markActiveFails) throw IllegalStateException("zapis Keystore nieudany")
        val c = (current as CredentialLoad.Present).credential
        current = CredentialLoad.Present(StoredCredential(CredentialState.ACTIVE, c.deviceId, c.clientId))
    }

    override suspend fun delete() {
        if (deleteFails) throw IllegalStateException("Keystore: usunięcie nieudane")
        deleted = true
        current = CredentialLoad.None
    }

    companion object {
        val DEVICE_ID = ByteArray(16) { 0x11 }
        val CLIENT_ID = ByteArray(16) { 0x22 }

        fun present(state: CredentialState, deviceId: ByteArray = DEVICE_ID, clientId: ByteArray = CLIENT_ID) =
            CredentialLoad.Present(StoredCredential(state, deviceId, clientId))
    }
}

class FakeSettingsStore(autoClose: Boolean = false, name: String? = "Brama", var failingSubscriptions: Int = 0) : SettingsStore {
    val autoClose = MutableStateFlow(autoClose)
    override val autoCloseDefault: Flow<Boolean> = kotlinx.coroutines.flow.flow {
        if (failingSubscriptions > 0) {
            failingSubscriptions--
            throw java.io.IOException("DataStore: odczyt nieudany")
        }
        emitAll(this@FakeSettingsStore.autoClose)
    }
    val name = MutableStateFlow(name)
    override val gateName: Flow<String?> = this.name
    val presenceEnabled = MutableStateFlow(true)
    override val presenceNotificationEnabled: Flow<Boolean> = presenceEnabled

    override suspend fun setAutoCloseDefault(enabled: Boolean) {
        autoClose.value = enabled
    }

    override suspend fun setGateName(name: String?) {
        this.name.value = name
    }

    override suspend fun setPresenceNotificationEnabled(enabled: Boolean) {
        presenceEnabled.value = enabled
    }
}

class FakeHistoryStore : HistoryStore {
    val appended = mutableListOf<HistoryEntry>()
    val published = MutableStateFlow<List<HistoryEntry>>(emptyList())
    override val entries: Flow<List<HistoryEntry>> = published
    val trims = mutableListOf<Int>()
    var fail = false

    override suspend fun append(entry: HistoryEntry) {
        if (fail) throw IllegalStateException("dysk pełny")
        appended += entry
    }

    override suspend fun trim(keepLast: Int) {
        trims += keepLast
    }
}

class FakeRuleCacheStore(var stored: LastKnownRule? = null) : RuleCacheStore {
    val saved = mutableListOf<LastKnownRule>()
    var cleared = false
    var failNextSave = false

    override suspend fun load() = stored

    override suspend fun save(rule: LastKnownRule) {
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("dysk pełny")
        }
        saved += rule
        stored = rule
    }

    override suspend fun clear() {
        cleared = true
        stored = null
    }
}

class FakeConnectionKeeper(var allowed: Boolean = true) : ConnectionKeeper {
    var calls = 0
    var throws = false

    override fun ensureBackgroundConnection(): Boolean {
        calls++
        if (throws) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        return allowed
    }
}

/** Kolejne bajty 0x01, 0x02, … */
class FakeNonceSource : NonceSource {
    private var next = 1
    var fails = false

    override fun next(size: Int): ByteArray {
        if (fails) throw IllegalStateException("SecureRandom niedostępny")
        return ByteArray(size) { (next++).toByte() }
    }
}

/** Komplet atrap i repozytorium; repozytorium działa w przekazanym zakresie (np. `backgroundScope`). */
class Harness(
    scope: kotlinx.coroutines.CoroutineScope,
    val link: FakeGateLink = FakeGateLink(),
    val credentials: FakeCredentialStore = FakeCredentialStore(FakeCredentialStore.present(CredentialState.ACTIVE)),
    val settings: FakeSettingsStore = FakeSettingsStore(),
    val history: FakeHistoryStore = FakeHistoryStore(),
    val ruleCache: FakeRuleCacheStore = FakeRuleCacheStore(),
    val keeper: FakeConnectionKeeper = FakeConnectionKeeper(),
    val nonces: FakeNonceSource = FakeNonceSource(),
    val presence: FakePresenceStore = FakePresenceStore(),
    phoneName: String = "",
) {
    val repo = GateRepository(scope, link, credentials, settings, history, ruleCache, keeper, presence, { 1_000L }, nonces, { phoneName })
    val state: GateUiState get() = repo.state.value
}

class FakePresenceStore(var stored: PresenceRecord = PresenceRecord(quiet = false, ruleSuppressed = false)) : PresenceStore {
    val saved = mutableListOf<PresenceRecord>()

    override suspend fun load() = stored

    override suspend fun save(record: PresenceRecord) {
        saved += record
        stored = record
    }
}
