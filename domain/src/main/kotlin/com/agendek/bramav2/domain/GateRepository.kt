package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.ClientName
import com.agendek.bramav2.protocol.FirmwareImage
import com.agendek.bramav2.protocol.FirmwareImageResult
import com.agendek.bramav2.protocol.FirmwareImages
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.OtaPayloads
import com.agendek.bramav2.protocol.OtaResult
import com.agendek.bramav2.protocol.isOta
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.ProvisioningQr
import com.agendek.bramav2.protocol.QrParseResult
import com.agendek.bramav2.protocol.NonceSource
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SecureRandomNonceSource
import com.agendek.bramav2.protocol.SessionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Jedyny punkt wejścia dla telefonu i Android Auto (domain_design §4.1). Wszystkie intencje UI i zdarzenia
 * [GateLink] trafiają do jednego aktora i są przetwarzane sekwencyjnie; stan publikuje [state].
 * Intencje są nieblokujące i bezpieczne do wołania z dowolnego wątku.
 */
class GateRepository(
    private val scope: CoroutineScope,
    private val link: GateLink,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
    private val historyStore: HistoryStore,
    private val ruleCache: RuleCacheStore,
    private val keeper: ConnectionKeeper,
    private val presenceStore: PresenceStore,
    private val clock: WallClock,
    private val nonces: NonceSource = SecureRandomNonceSource(),
    /** Nazwa telefonu wysyłana do sterownika po rejestracji (client_management_design.md §5.3); pusta = bez wysyłki. */
    private val phoneName: () -> String = { "" },
) {
    private sealed interface Msg {
        data class Attached(val source: UiSource) : Msg
        data class Detached(val source: UiSource) : Msg
        data object Refresh : Msg
        data class Problem(val problem: SystemProblem?) : Msg
        data class AutoCloseDefault(val enabled: Boolean) : Msg
        data class GateName(val name: String?) : Msg
        data class PresenceEnabled(val enabled: Boolean) : Msg
        data class QuietAbsenceOver(val generation: Int) : Msg
        data class SetGateName(val name: String?) : Msg
        data class SetPresenceEnabled(val enabled: Boolean) : Msg
        data class Link(val message: LinkMessage) : Msg
        data object Open : Msg
        data class RequestClose(val source: UiSource) : Msg
        data class CancelConfirmation(val source: UiSource) : Msg
        data class RequestDisconnect(val source: UiSource) : Msg
        data class ConfirmDisconnect(val source: UiSource) : Msg
        data object Connect : Msg
        data class SetAutoClose(val enabled: Boolean) : Msg
        data object ConfirmEnableAutoClose : Msg
        data object RequestRemove : Msg
        data object ConfirmRemove : Msg
        data class RetryTimer(val generation: Int) : Msg
        class StartRegistration(val qrText: String) : Msg
        class StartPinRegistration(val pin: String) : Msg
        data object CancelRegistration : Msg
        data object DismissNotification : Msg
        data object LinkStreamFailed : Msg
        data object PresenceAppeared : Msg
        data object PresenceDisappeared : Msg
        data object NotificationOpen : Msg
        data object NotificationClose : Msg
        data object NotificationDismissed : Msg
        data class OwnerArmedExpired(val generation: Int) : Msg
        class SelectFirmware(val bytes: ByteArray) : Msg
        data object ConfirmFirmware : Msg
        data object RetryFirmwareEnd : Msg
        data object CancelFirmware : Msg
        data object DismissFirmware : Msg
        data class RebootTimeout(val generation: Int) : Msg
        data object RepairBond : Msg
        data object OpenPairingWindow : Msg
        data object RefreshClients : Msg
        class RemoveClient(val clientId: ByteArray) : Msg
        data class RenameSelf(val name: String) : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val policy = ConnectionPolicy()
    private var stored: StoredCredential? = null
    private var credentialLocked = false

    /** Wywołano `connect` i nie było od tego czasu `disconnect`/utraty łącza. */
    private var linkActive = false

    /** Identyfikator bieżącego połączenia (sesji albo próby PAIR); zdarzenia innych połączeń są odrzucane. */
    private var connectionId: Long? = null

    /** Kolejne utraty sesji, które osiągnęły READY, bez wymiany wiadomości ani akcji użytkownika. */
    private var readyLossCycles = 0

    /** GRANT został zapisany w bieżącej próbie PAIR (nie w poprzedniej). */
    private var grantSaved = false

    /** Bieżące łącze osiągnęło READY; utrata przed READY to nieudana próba, a nie zerwana sesja. */
    private var sessionReady = false
    private var pingInFlight = false

    /**
     * Żądanie poza komendami ruchu wysłane i nierozstrzygnięte: OPEN_PAIRING_WINDOW albo zarządzanie telefonami
     * (lista, usunięcie, nazwa) — zajmuje wspólną kolejkę żądań jak PING (13.3).
     */
    private var auxInFlight: SessionRequest? = null

    /** Po pierwszej sesji nowo zarejestrowanego telefonu: wysłać jego nazwę do sterownika (wysłane raz, bez komunikatu). */
    private var ownNamePending = false
    private var autoNameRequest: SessionRequest? = null

    /** Ostatni świeży snapshot pokazywał własną uzbrojoną regułę (powiadomienie DISARMED/REBOOT, spec 3). */
    private var lastFreshOwnArmed = false
    private var lastCached: RuleSnapshot? = null

    /** Komenda użytkownika wysłana i nierozstrzygnięta (najwyżej jedna, spec 13.3). */
    private var inFlight: GateCommand? = null

    /** Komenda użytkownika czekająca na rozstrzygnięcie PING; [lastBeforeQueued] przywraca wynik, gdy nie zostanie wysłana. */
    private var queued: GateCommand? = null
    private var lastBeforeQueued: LastCommand? = null

    /** Próba PAIR w toku (do PairingDone). `K_setup` żyje tylko tutaj, nigdy nie jest utrwalany (domain_design §4.3). */
    /** Trwająca próba PAIR: sekret (QR albo PIN), `client_id` i `device_id` (przy PIN-ie znany od GRANT). */
    private class PairingAttempt(val secret: PairingSecret, val clientId: ByteArray, var deviceId: ByteArray?) {
        val isPin: Boolean get() = secret is PairingSecret.Pin
    }

    private var pairing: PairingAttempt? = null

    /** Bieżące połączenie to naprawa bondu (`repairBond`, spec 9.5) — do pierwszego `Ready` albo końca próby. */
    private var repairing = false

    /** [presenceGeneration] odrzuca spóźnione upływy karencji nieobecności po wyciszeniu. */
    private var presenceGeneration = 0

    /**
     * Po wyciszeniu (QUIET) „zniknięcie” zdejmuje wyciszenie dopiero po [PRESENCE_ABSENCE_GRACE_MS] bez ponownego pojawienia się.
     * CDM (Android 16) zgłasza zniknięcie także jako echo własnego rozłączenia (~1 s po `disconnect`) i przy chwilowej utracie
     * reklam przez własny skan (Lost/Found co kilkanaście sekund przy zgaszonym ekranie); bez karencji powiadomienie wracało
     * co kilka–kilkanaście minut mimo stałej obecności telefonu przy bramie (14.11.2026).
     */
    private var quietAbsenceJob: Job? = null
    private var ruleSuppressed = false

    /**
     * Odliczanie po utracie łącza, po którym nieaktualna własna reguła przestaje być powodem połączenia
     * ([ConnectionPolicy.setOwnerArmedExpired]); [ownerExpiryGeneration] odrzuca spóźnione upływy.
     */
    private var ownerExpiryJob: Job? = null
    private var ownerExpiryGeneration = 0
    private var ownerExpired = false

    /** Trwa odliczanie backoffu po odmowie UNAVAILABLE/RATE_LIMITED (spec 12.4). */
    private var retryJob: Job? = null

    /** Odrzuca spóźniony [Msg.RetryTimer] anulowanego lub zastąpionego odliczania. */
    private var retryGeneration = 0

    /** Bieżące odliczanie to krótki odstęp po nieudanej próbie (może je skrócić zmiana warunków), a nie backoff 12.4. */
    private var retryIsShort = false

    /** Następny krótki odstęp: 2 s, podwajany do 60 s; zerowany po READY i akcji użytkownika. */
    private var nextShortRetryMs = RECONNECT_DELAY_MS

    /** Transfer firmware w toku (ota_design §5.3); obraz żyje w pamięci tylko tutaj. */
    private class OtaTransfer(val image: FirmwareImage) {
        val size: Long get() = image.bytes.size.toLong()
        var begun = false
        var offset = 0L

        /** END wysłany i nierozstrzygnięty: sterownik mógł już zweryfikować obraz i się zrestartować. */
        var endSent = false

        /** Anulowany: po rozstrzygnięciu oczekującego żądania wysyłany jest OTA_ABORT. */
        var aborting = false
    }

    private var ota: OtaTransfer? = null

    /** Żądanie OTA wysłane i nierozstrzygnięte — jedyne oczekujące żądanie sesji obok komendy i PING (13.3). */
    private var otaPending: SessionRequest? = null

    /** Obraz sprawdzony i czekający na potwierdzenie „wersja X → Y”. */
    private var pendingImage: FirmwareImage? = null

    /** PING odświeżenia czeka na OTA_ACK bieżącego kawałka. */
    private var pingQueued = false

    /** Dane bieżącego łącza z `Ready` (8.2, 15.5). */
    private var linkOtaSupported = false
    private var linkPairingWindowSupported = false
    private var linkMtu = 0

    /** Oczekiwanie na sterownik po restarcie (90 s) i wersja, której oczekujemy. */
    private var rebootJob: Job? = null
    private var rebootGeneration = 0
    private var rebootTargetVersion: String? = null

    /** Problem zgłoszony przez `:app`; problemy ustalane przez domenę są osobno (blokada, brak startu w tle). */
    private var appProblem: SystemProblem? = null
    private var backgroundBlocked = false

    /** Stan budowany w trakcie obsługi wiadomości; publikowany raz, na jej końcu ([flush]). */
    private var draft = GateUiState.INITIAL
    private val _state = MutableStateFlow(publish(draft))
    val state: StateFlow<GateUiState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)

    /** Uruchamia aktora; kolejne wywołania są ignorowane (jeden aktor gwarantuje spójność stanu). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        collectResiliently(link.events, restartOnCompletion = true, onFailure = { inbox.send(Msg.LinkStreamFailed) }) { Msg.Link(it) }
        collectResiliently(settings.autoCloseDefault) { Msg.AutoCloseDefault(it) }
        collectResiliently(settings.gateName) { Msg.GateName(it) }
        collectResiliently(settings.presenceNotificationEnabled) { Msg.PresenceEnabled(it) }
        scope.launch {
            guarded { initialize() }
            for (msg in inbox) guarded { handle(msg) }
        }
    }

    /**
     * Ekran danego źródła jest widoczny / przestał być widoczny. Wywołania są idempotentne dla źródła (zbiór,
     * nie licznik): `:app` woła je z jednego właściciela cyklu życia na źródło (np. lifecycle procesu dla telefonu,
     * sesja Car App dla samochodu), a nie z każdej aktywności osobno.
     */
    fun uiAttached(source: UiSource) = post(Msg.Attached(source))
    fun uiDetached(source: UiSource) = post(Msg.Detached(source))
    fun refresh() = post(Msg.Refresh)
    fun reportSystemProblem(problem: SystemProblem?) = post(Msg.Problem(problem))

    /** OPEN z bieżącą preferencją `autoCloseOnDisconnect` (spec 4.2: zmiana działa od następnego OPEN). */
    fun open() = post(Msg.Open)
    fun requestClose(source: UiSource) = post(Msg.RequestClose(source))
    fun cancelConfirmation(source: UiSource) = post(Msg.CancelConfirmation(source))

    /** „Rozłącz”; przy własnej regule MONITORING najpierw potwierdzenie z ostrzeżeniem (spec 4.2). */
    fun requestDisconnect(source: UiSource) = post(Msg.RequestDisconnect(source))
    fun confirmDisconnect(source: UiSource) = post(Msg.ConfirmDisconnect(source))

    /** Świadome „Połącz”: zdejmuje wstrzymanie po „Rozłącz” i blokady odmów. */
    fun connect() = post(Msg.Connect)
    fun setAutoCloseDefault(enabled: Boolean) = post(Msg.SetAutoClose(enabled))

    /** Nazwa lokalna bramy; pusta lub z samych spacji usuwa nazwę. */
    fun setGateName(name: String?) = post(Msg.SetGateName(name))

    /** Przełącznik powiadomienia „brama w zasięgu”; wyłączenie działa jak zniknięcie sterownika z zasięgu. */
    fun setPresenceNotificationEnabled(enabled: Boolean) = post(Msg.SetPresenceEnabled(enabled))

    /** Historia komend, najnowsze pierwsze (spec 4.1). */
    val history: Flow<List<HistoryEntry>> get() = historyStore.entries
    fun confirmEnableAutoClose() = post(Msg.ConfirmEnableAutoClose)
    fun requestRemoveFromPhone() = post(Msg.RequestRemove)
    fun confirmRemoveFromPhone() = post(Msg.ConfirmRemove)

    /** Rejestracja z tekstu QR (spec 11.3); dostępna tylko bez poświadczenia ACTIVE (domain_design §6.4 pkt 0). */
    fun startRegistration(qrText: String) = post(Msg.StartRegistration(qrText))

    /** Sterownik pojawił się w zasięgu (CompanionDeviceManager, spec 4.3). */
    fun presenceAppeared() = post(Msg.PresenceAppeared)

    /** Sterownik zniknął z zasięgu: koniec powiadomienia i wyciszenia (spec 4.3 pkt 5–6). */
    fun presenceDisappeared() = post(Msg.PresenceDisappeared)

    /**
     * Powiadomienie odrzucone przez użytkownika (dotknięcie, przesunięcie) albo ekran telefonu wyszedł na pierwszy plan
     * (spec 4.3 pkt 4): SHOWN → QUIET. W innych stanach bez skutku.
     */
    fun notificationDismissed() = post(Msg.NotificationDismissed)

    /** Przycisk OTWÓRZ powiadomienia: OPEN z domyślnym `autoCloseOnDisconnect`. */
    fun notificationOpen() = post(Msg.NotificationOpen)

    /** Przycisk ZAMKNIJ powiadomienia: CLOSE bez potwierdzenia. */
    fun notificationClose() = post(Msg.NotificationClose)

    /** Rejestracja PIN-em (spec 11.4); niepoprawny format (nie 6 cyfr) jest ignorowany — pilnuje go UI. */
    fun startPinRegistration(pin: String) = post(Msg.StartPinRegistration(pin))
    fun cancelRegistration() = post(Msg.CancelRegistration)
    /** UI pokazało powiadomienie; kolejne takie samo zdarzenie musi dać nową emisję stanu. */
    fun dismissNotification() = post(Msg.DismissNotification)

    /** Plik firmware odczytany przez `:app` (15.5): wstępne sprawdzenie i potwierdzenie „wersja X → Y”. */
    fun selectFirmware(bytes: ByteArray) = post(Msg.SelectFirmware(bytes))

    /** Potwierdzenie aktualizacji: przy MTU < 247 błąd bez BEGIN, inaczej transfer. */
    fun confirmFirmwareUpdate() = post(Msg.ConfirmFirmware)

    /** Ponowny END po odrzuceniu `OTA_RULE_ACTIVE`/`OTA_BUSY` (15.5). */
    fun retryFirmwareEnd() = post(Msg.RetryFirmwareEnd)

    /** Rezygnacja przed transferem albo przerwanie transferu (OTA_ABORT); po wysłaniu END bez skutku. */
    fun cancelFirmwareUpdate() = post(Msg.CancelFirmware)

    /** Zamknięcie wyniku aktualizacji. */
    fun dismissFirmwareUpdate() = post(Msg.DismissFirmware)

    /** „Napraw połączenie” (spec 9.5): sesja z zachowanym `K_client` i nowym parowaniem w otwartym oknie sterownika. */
    fun repairBond() = post(Msg.RepairBond)

    /** „Otwórz okno rejestracji” (phone_window_design): to samo okno 60 s co przycisk sterownika 3 s. */
    fun openPairingWindow() = post(Msg.OpenPairingWindow)

    /** Odświeża listę telefonów zapisanych na sterowniku („Telefony z dostępem”). */
    fun refreshClients() = post(Msg.RefreshClients)

    /** Usuwa inny telefon ze sterownika; wynik jako komunikat, potem odświeżenie listy. */
    fun removeClient(clientId: ByteArray) = post(Msg.RemoveClient(clientId.copyOf()))

    /** Zmienia nazwę tego telefonu zapisaną na sterowniku (≤ 20 B UTF-8, bez znaków sterujących). */
    fun renameSelf(name: String) = post(Msg.RenameSelf(name))

    private fun post(msg: Msg) {
        inbox.trySend(msg)
    }

    /**
     * Odbiór strumienia portu, który przeżywa jego awarię (np. `IOException` z DataStore, błąd `:ble`):
     * po błędzie [onFailure] i ponowna subskrypcja po krótkim odstępie. Przy [restartOnCompletion] także
     * normalne zakończenie (np. restart usługi `:ble`) jest traktowane jak awaria.
     */
    private fun <T> collectResiliently(
        flow: Flow<T>,
        restartOnCompletion: Boolean = false,
        onFailure: suspend () -> Unit = {},
        toMsg: (T) -> Msg,
    ) {
        scope.launch {
            while (true) {
                try {
                    flow.collect { inbox.send(toMsg(it)) }
                    if (!restartOnCompletion) return@launch
                    onFailure()
                    delay(RECONNECT_DELAY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure()
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    /** Błąd portu nie może zatrzymać aktora; stan jest publikowany raz, także po błędzie. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kolejne intencje i zdarzenia muszą być obsłużone.
        } finally {
            flush()
        }
    }

    private fun flush() {
        _state.value = publish(draft)
    }

    /** Jak `runCatching`, ale anulowanie korutyny nie staje się zwykłym wynikiem. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun initialize() {
        // Przed poświadczeniem: pierwszy opublikowany stan z bramą nie może mieć domyślnego `true`, gdy funkcja
        // jest wyłączona — zimny start z obecności (CDM) pokazałby powiadomienie i połączył się (spec 4.3 pkt 7).
        attempt { settings.presenceNotificationEnabled.first() }.getOrNull()?.let { enabled ->
            update { it.copy(presenceNotificationEnabled = enabled) }
        }
        loadCredential()
        attempt { presenceStore.load() }.getOrNull()?.let { record ->
            ruleSuppressed = record.ruleSuppressed
            policy.setOwnerArmedSuppressed(record.ruleSuppressed)
            if (record.quiet) update { it.copy(presence = PresenceNotification.QUIET) }
        }
        attempt { ruleCache.load() }.getOrNull()?.let { cached ->
            lastCached = cached.snapshot
            lastFreshOwnArmed = cached.snapshot.isOwnArmed()
            policy.updateOwnerArmed(cached.snapshot)
            update { it.copy(rule = RuleView(cached.snapshot, fresh = false, refreshing = false)) }
            startOwnerExpiry() // nowy proces nie ma sesji: liczone jak od utraty łącza
        }
        evaluateConnection()
    }

    /** Odczyt poświadczenia; błąd odczytu traktowany jak niedostępność do odblokowania (spec 9.3). */
    private suspend fun loadCredential() {
        when (val load = attempt { credentials.load() }.getOrDefault(CredentialLoad.Locked)) {
            CredentialLoad.None -> {
                credentialLocked = false
                stored = null
                update { it.copy(credential = CredentialState.NONE) }
            }
            CredentialLoad.Locked -> credentialLocked = true
            is CredentialLoad.Present -> {
                credentialLocked = false
                stored = load.credential
                update { it.copy(credential = load.credential.state) }
            }
        }
        refreshProblem()
    }

    private fun markCredentialLocked() {
        credentialLocked = true
        refreshProblem()
    }

    /** Najważniejszy problem: blokada poświadczenia, potem zgłoszenie `:app`, potem brak startu w tle. */
    private fun refreshProblem() {
        val problem = when {
            credentialLocked -> SystemProblem.CREDENTIAL_LOCKED
            appProblem != null -> appProblem
            backgroundBlocked -> SystemProblem.BACKGROUND_START_BLOCKED
            else -> null
        }
        update { it.copy(systemProblem = problem) }
    }

    /** Po pierwszym odblokowaniu telefonu poświadczenie staje się dostępne — ponowny odczyt przy akcji UI. */
    private suspend fun reloadIfLocked() {
        if (credentialLocked) loadCredential()
    }

    private suspend fun handle(msg: Msg) {
        when (msg) {
            is Msg.Attached -> {
                reloadIfLocked()
                onAttached(msg.source)
            }
            is Msg.Detached -> {
                clearConfirmation(msg.source) // decyzja z zamkniętego ekranu nie może wrócić przy następnym
                policy.detach(msg.source)
                evaluateConnection()
            }
            Msg.Refresh -> onRefresh()
            is Msg.Problem -> {
                val previous = appProblem
                appProblem = msg.problem
                refreshProblem()
                val wasLocked = credentialLocked
                if (msg.problem == null) reloadIfLocked()
                // Tylko faktyczna zmiana warunków (lub odblokowanie) ocenia łącze; powtórzone zgłoszenie
                // tego samego stanu (np. przy każdym onResume) nie skraca krótkiego odstępu.
                if (previous != msg.problem || wasLocked) {
                    if (previous != null && retryIsShort) cancelRetry()
                    evaluateConnection()
                }
            }
            is Msg.AutoCloseDefault -> update { it.copy(nextOpenAutoClose = msg.enabled) }
            is Msg.GateName -> update { it.copy(gateName = msg.name) }
            is Msg.SetGateName -> attempt { settings.setGateName(msg.name?.trim()?.takeIf { it.isNotEmpty() }) }
            is Msg.SetPresenceEnabled -> attempt { settings.setPresenceNotificationEnabled(msg.enabled) }
            is Msg.PresenceEnabled -> {
                update { it.copy(presenceNotificationEnabled = msg.enabled) }
                if (!msg.enabled) onPresenceDisappeared()
            }
            is Msg.Link -> onLinkMessage(msg.message)
            Msg.Open -> onOpen()
            // CLOSE jednym dotknięciem, bez potwierdzenia (spec 1.4, 4.1, 5.2).
            is Msg.RequestClose -> if (canCommand()) dispatch(GateCommand.Close)
            is Msg.CancelConfirmation -> clearConfirmation(msg.source)
            is Msg.RequestDisconnect -> onRequestDisconnect(msg.source)
            is Msg.ConfirmDisconnect -> when (draft.confirmations[msg.source]) {
                Confirmation.DISCONNECT_ARMS_AUTOCLOSE -> {
                    clearConfirmation(msg.source)
                    userDisconnect()
                }
                Confirmation.DISCONNECT_ABORTS_UPDATE -> {
                    clearConfirmation(msg.source)
                    // Sterownik porzuca transfer z utratą sesji (15.5 pkt 4) — bez OTA_ABORT.
                    ota?.takeIf { !it.endSent }?.let { finishOta(UiMessage(MessageId.OTA_ABORTED)) }
                    userDisconnect()
                }
                else -> Unit
            }
            Msg.Connect -> {
                reloadIfLocked()
                userAction()
                confirmCredential()
                evaluateConnection()
            }
            is Msg.SetAutoClose ->
                if (msg.enabled) {
                    requestConfirmation(Confirmation.ENABLE_AUTO_CLOSE, listOf(UiMessage(MessageId.WARN_ENABLE_AUTO_CLOSE)))
                } else {
                    if (draft.confirmations[UiSource.PHONE] == Confirmation.ENABLE_AUTO_CLOSE) clearConfirmation(UiSource.PHONE)
                    attempt { settings.setAutoCloseDefault(false) }
                    backgroundBlocked = false // dotyczy wyłącznie OPEN(true)
                    refreshProblem()
                }
            Msg.ConfirmEnableAutoClose ->
                if (draft.confirmations[UiSource.PHONE] == Confirmation.ENABLE_AUTO_CLOSE) {
                    clearConfirmation(UiSource.PHONE)
                    attempt { settings.setAutoCloseDefault(true) }
                }
            Msg.RequestRemove -> onRequestRemove()
            Msg.ConfirmRemove ->
                if (draft.confirmations[UiSource.PHONE] == Confirmation.REMOVE_FROM_PHONE) removeFromPhone()
            is Msg.RetryTimer -> if (msg.generation == retryGeneration && retryJob != null) {
                retryJob = null
                evaluateConnection()
            }
            is Msg.StartRegistration -> onStartRegistration(msg.qrText)
            is Msg.StartPinRegistration -> PairingSecret.Pin.parse(msg.pin)?.let { startPairing(it, knownDeviceId = null) }
            Msg.PresenceAppeared -> onPresenceAppeared()
            Msg.PresenceDisappeared -> onPresenceDisappeared()
            Msg.NotificationOpen -> if (draft.presence == PresenceNotification.SHOWN && canCommand()) {
                onOpen()
            }
            Msg.NotificationClose -> if (draft.presence == PresenceNotification.SHOWN && canCommand()) {
                dispatch(GateCommand.Close)
            }
            is Msg.OwnerArmedExpired -> if (msg.generation == ownerExpiryGeneration && ownerExpiryJob != null) {
                ownerExpiryJob = null
                ownerExpired = true
                policy.setOwnerArmedExpired(true)
                evaluateConnection()
            }
            is Msg.QuietAbsenceOver -> if (msg.generation == presenceGeneration && draft.presence == PresenceNotification.QUIET) {
                quietAbsenceJob = null
                resetPresence()
                evaluateConnection()
            }
            Msg.NotificationDismissed -> if (draft.presence == PresenceNotification.SHOWN) onPresenceQuiet()
            Msg.CancelRegistration -> if (pairing != null) {
                abortPairingAttempt(registration = null)
            } else {
                // Zamknięcie wyniku (InvalidQr, Failed, Done) lub ekranu potwierdzania sesji; łącze otwarte
                // tylko dla potwierdzenia przestaje być potrzebne.
                update { it.copy(registration = null) }
                evaluateConnection()
            }
            Msg.DismissNotification -> update { it.copy(notification = null) }
            is Msg.SelectFirmware -> onSelectFirmware(msg.bytes)
            Msg.ConfirmFirmware -> onConfirmFirmware()
            Msg.RetryFirmwareEnd -> {
                val t = ota
                if (draft.firmwareUpdate is FirmwareUpdate.EndRejected && t != null && otaPending == null) {
                    setFirmwareUpdate(FirmwareUpdate.Transferring(100))
                    pumpOta()
                }
            }
            Msg.CancelFirmware -> onCancelFirmware()
            Msg.RepairBond -> onRepairBond()
            Msg.OpenPairingWindow -> onOpenPairingWindow()
            Msg.RefreshClients -> sendClientsRequest(SessionRequest.ListClients)
            is Msg.RemoveClient -> sendClientsRequest(SessionRequest.RemoveClient(msg.clientId))
            is Msg.RenameSelf -> if (ClientName.isValid(msg.name)) sendClientsRequest(SessionRequest.SetClientName(msg.name))
            Msg.DismissFirmware -> if (draft.firmwareUpdate is FirmwareUpdate.Finished) setFirmwareUpdate(null)
            is Msg.RebootTimeout -> if (msg.generation == rebootGeneration && draft.firmwareUpdate == FirmwareUpdate.Rebooting) {
                val message = UiMessage(MessageId.OTA_NO_RECONNECT)
                setFirmwareUpdate(FirmwareUpdate.Finished(message))
                update { it.copy(notification = message) }
                evaluateConnection()
            }
            // Bez strumienia zdarzeń stan łącza jest nieznany: traktować jak utratę.
            Msg.LinkStreamFailed -> if (pairing != null) {
                failRegistration(UiMessage(MessageId.REGISTRATION_INCOMPLETE))
            } else if (linkActive) {
                linkFailed()
            }
        }
    }

    private suspend fun onAttached(source: UiSource) {
        policy.attach(source)
        policy.resetBackoff()
        refreshOrConnect()
    }

    /** Spec 4.2: przy sesji READY odświeżyć stan reguły, w przeciwnym razie ocenić potrzebę połączenia. */
    private suspend fun refreshOrConnect() {
        if (draft.connection == ConnectionState.READY) requestFreshRule() else evaluateConnection()
    }

    /** PING na żądanie; `resumeByUser` zdejmuje blokadę odmów, ale nie wstrzymanie po „Rozłącz”. */
    private suspend fun onRefresh() {
        reloadIfLocked()
        if (policy.suspended) return
        userAction()
        confirmCredential()
        refreshOrConnect()
    }

    /**
     * Spec 4.2: oznacz poprzedni snapshot jako odświeżany i wykonaj PING. Przy komendzie w toku PING nie jest
     * wysyłany (najwyżej jedno oczekujące żądanie, 13.3) — świeży snapshot przyjdzie w ACK.
     */
    private suspend fun requestFreshRule() {
        update { it.copy(rule = it.rule.copy(fresh = false, refreshing = true)) }
        if (pingInFlight || auxInFlight != null || inFlight != null || queued != null) return
        // W trakcie transferu PING idzie między kawałkami, po OTA_ACK bieżącego (wspólna kolejka 13.3).
        if (otaPending != null) {
            pingQueued = true
            return
        }
        if (attempt { link.send(SessionRequest.Ping) }.isSuccess) {
            pingInFlight = true
        } else {
            linkFailed()
        }
    }

    /** Świadoma akcja użytkownika (spec 12.4): zeruje backoff i blokady ponowień. */
    private fun userAction() {
        policy.resumeByUser()
        cancelRetry()
        nextShortRetryMs = RECONNECT_DELAY_MS
        readyLossCycles = 0
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun requestConfirmation(confirmation: Confirmation, warnings: List<UiMessage>, source: UiSource = UiSource.PHONE) {
        update {
            it.copy(
                confirmations = it.confirmations + (source to confirmation),
                warnings = if (source == UiSource.PHONE) warnings else it.warnings,
            )
        }
    }

    private suspend fun onRequestDisconnect(source: UiSource) {
        // Po wysłanym END sterownik mógł już przyjąć obraz — ostrzeżenie „firmware pozostanie bez zmian” byłoby fałszywe.
        if (isTransferActive(draft.firmwareUpdate) && ota?.endSent != true) {
            requestConfirmation(Confirmation.DISCONNECT_ABORTS_UPDATE, listOf(UiMessage(MessageId.WARN_DISCONNECT_ABORTS_UPDATE)), source)
            return
        }
        val rule = draft.rule.snapshot
        if (rule != null && rule.isOwnArmed() && rule.phase == AutoClosePhase.MONITORING) {
            requestConfirmation(Confirmation.DISCONNECT_ARMS_AUTOCLOSE, listOf(UiMessage(MessageId.WARN_DISCONNECT_ARMS)), source)
        } else {
            userDisconnect()
        }
    }

    /** Wstrzymuje wszystkie powody połączenia do świadomego „Połącz” (domain_design §6.2). */
    private suspend fun userDisconnect() {
        policy.suspendByUser()
        cancelRetry()
        evaluateConnection()
        // Oczekiwanie na sterownik po restarcie kończy się bez komunikatu (inaczej fałszywy „Brak połączenia…”).
        if (draft.firmwareUpdate == FirmwareUpdate.Rebooting) setFirmwareUpdate(null)
    }

    private fun onRequestRemove() {
        val rule = draft.rule
        val s = rule.snapshot
        // Spec 4.1: bez ostrzeżenia w FAULT i BLOCKED (FAULT nie jest uzbrojony wg isOwnArmed).
        val mayClose = !rule.fresh || (s != null && s.isOwnArmed() && s.phase != AutoClosePhase.BLOCKED)
        val warnings = listOf(UiMessage(MessageId.WARN_REMOVE_NOT_REVOKED)) +
            if (mayClose) listOf(UiMessage(MessageId.WARN_REMOVE_MAY_CLOSE)) else emptyList()
        requestConfirmation(Confirmation.REMOVE_FROM_PHONE, warnings)
    }

    /** Usuwa lokalne poświadczenie i ostatni stan reguły; nie odwołuje dostępu w sterowniku (spec 9.4). Historia zostaje. */
    private suspend fun removeFromPhone() {
        ownNamePending = false
        // Najpierw poświadczenie: jeśli usunięcie się nie uda, nic innego się nie zmienia.
        if (attempt { credentials.delete() }.isFailure) {
            clearConfirmation(UiSource.PHONE)
            return
        }
        if (pairing != null) {
            attempt { link.abortPairing() }
            endPairing()
        }
        teardownLink()
        attempt { ruleCache.clear() }
        stored = null
        credentialLocked = false
        backgroundBlocked = false
        lastFreshOwnArmed = false
        lastCached = null
        userAction()
        policy.updateOwnerArmed(null)
        cancelOwnerExpiry()
        resetPresence() // powiadomienie i wyciszenie dotyczyły usuniętej konfiguracji
        resetFirmwareUpdate()
        // Ustawienia nie należą do konfiguracji bramy; DataStore nie wyśle ich ponownie (distinctUntilChanged).
        update {
            GateUiState.INITIAL.copy(
                gateName = it.gateName,
                nextOpenAutoClose = it.nextOpenAutoClose,
                presenceNotificationEnabled = it.presenceNotificationEnabled,
            )
        }
        refreshProblem()
    }

    private suspend fun onStartRegistration(qrText: String) {
        if (pairing != null) return // próba już trwa i jest widoczna jako Pairing
        when (val parsed = ProvisioningQr.parse(qrText)) {
            is QrParseResult.Invalid -> {
                // Przy zablokowanym poświadczeniu albo ACTIVE pierwszeństwo mają te przyczyny (jak dotąd).
                if (!registrationBlocked()) update { it.copy(registration = RegistrationState.InvalidQr(parsed.error)) }
            }
            is QrParseResult.Ok -> startPairing(PairingSecret.Qr(parsed.qr), knownDeviceId = parsed.qr.deviceId)
        }
    }

    /** Zablokowane poświadczenie albo ACTIVE: rejestracja niedostępna, przyczyna pokazana w `registration`. */
    private suspend fun registrationBlocked(): Boolean {
        // Przy zablokowanym poświadczeniu nie wiadomo, czy istnieje ACTIVE (domain_design §6.4 pkt 0).
        reloadIfLocked()
        if (credentialLocked) {
            update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.CREDENTIAL_LOCKED))) }
            return true
        }
        if (stored?.state == CredentialState.ACTIVE) {
            update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.REJECT_CLIENT_ALREADY_ACTIVE))) }
            return true
        }
        return false
    }

    /**
     * Start próby PAIR z QR ([knownDeviceId] z QR) albo PIN-em ([knownDeviceId] = `null`: `device_id` przyjdzie
     * z GRANT). Sekret jest zerowany przy każdym zakończeniu próby (9.2).
     */
    private suspend fun startPairing(secret: PairingSecret, knownDeviceId: ByteArray?) {
        if (pairing != null || registrationBlocked()) {
            secret.wipe()
            return
        }
        val current = stored
        val clientId: ByteArray
        try {
            // Recovery 11.3 krok 2: ten sam client_id dla UNCONFIRMED tego samego sterownika; przy PIN-ie
            // `device_id` jest nieznany, a aplikacja ma jedną bramę — każde UNCONFIRMED.
            val reuse = current?.state == CredentialState.UNCONFIRMED &&
                (knownDeviceId == null || current.deviceId.contentEquals(knownDeviceId))
            clientId = if (reuse) current!!.clientId.copyOf() else nonces.next(CLIENT_ID_SIZE)
            teardownLink()
            userAction()
        } catch (e: CancellationException) {
            secret.wipe()
            throw e
        } catch (e: Exception) {
            secret.wipe() // sekret nie może zostać w pamięci po nieudanym starcie próby (9.2)
            update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE))) }
            return
        }
        val pairId = attempt { link.pair(secret, clientId) }.getOrNull()
        if (pairId == null) {
            secret.wipe()
            update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE))) }
            evaluateConnection()
            return
        }
        pairing = PairingAttempt(secret, clientId, knownDeviceId?.copyOf())
        connectionId = pairId
        grantSaved = false
        update { it.copy(registration = RegistrationState.Pairing, connection = ConnectionState.CONNECTING) }
    }

    private suspend fun onRegistrationEvent(event: LinkEvent) {
        val p = pairing ?: return
        when (event) {
            is LinkEvent.GrantReceived -> {
                update { it.copy(registration = RegistrationState.SavingCredential) }
                // `device_id` potwierdzony przez PAIR_CHALLENGE (przy PIN-ie jedyne źródło, 11.4).
                val deviceId = event.deviceId.copyOf()
                p.deviceId = deviceId
                val saved = try {
                    attempt { credentials.saveUnconfirmed(deviceId, p.clientId, event.clientKey) }.isSuccess
                } finally {
                    event.clientKey.fill(0)
                }
                if (!saved) {
                    failRegistration(UiMessage(MessageId.REGISTRATION_INCOMPLETE))
                    return
                }
                stored = StoredCredential(CredentialState.UNCONFIRMED, deviceId.copyOf(), p.clientId.copyOf())
                grantSaved = true
                update { it.copy(credential = CredentialState.UNCONFIRMED, registration = RegistrationState.Committing) }
                // Bez COMMIT: koniec próby; zapisane UNCONFIRMED przechodzi do recovery 11.3 (zwykła sesja z K1).
                if (attempt { link.commitPairing() }.isFailure) failRegistration(UiMessage(MessageId.REGISTRATION_INCOMPLETE))
            }
            LinkEvent.PairingDone -> {
                connectionId = null // PairingDone kończy połączenie parowania (kontrakt)
                if (!grantSaved) {
                    // Protokół na to nie pozwala (DONE po COMMIT, który wymaga GRANT) — nie zostawiać „potwierdzania”.
                    endPairing()
                    update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE))) }
                    markDisconnected()
                    evaluateConnection()
                    return
                }
                endPairing()
                update { it.copy(registration = RegistrationState.ConfirmingSession) }
                // Zwykła ocena łącza: uwzględnia „Rozłącz”, backoff i stan Bluetooth; oczekujące potwierdzenie
                // rejestracji samo jest powodem połączenia (także bez otwartego ekranu).
                evaluateConnection()
                if (!linkActive) {
                    markDisconnected()
                    // Chwilowy błąd: ponowienie zaplanowane, rejestracja czeka na sesję. Blokada: podać przyczynę.
                    if (credentialLocked) {
                        update { it.copy(registration = RegistrationState.Failed(UiMessage(MessageId.CREDENTIAL_LOCKED))) }
                    }
                }
            }
            is LinkEvent.HandshakeRejected -> {
                if (event.reason.isRetryable()) scheduleRetry() // spec 12.4 także przed powrotem do zwykłej sesji
                // Przy PIN-ie AUTH_FAILED to najczęściej zły PIN albo zamknięte okno (spec 3, 11.4).
                val pinAuth = p.isPin && event.reason == HandshakeRejectReason.AUTH_FAILED
                failRegistration(if (pinAuth) UiMessage(MessageId.PIN_REGISTRATION_FAILED) else Messages.handshakeReject(event.reason))
            }
            LinkEvent.HandshakeFailed ->
                failRegistration(UiMessage(if (p.isPin) MessageId.PIN_REGISTRATION_FAILED else MessageId.REGISTRATION_INCOMPLETE))
            LinkEvent.SessionLost, LinkEvent.Disconnected -> {
                val current = stored
                val known = p.deviceId
                val sameDeviceUnconfirmed = known != null && current?.state == CredentialState.UNCONFIRMED &&
                    current.deviceId.contentEquals(known)
                endPairing()
                linkActive = false
                connectionId = null
                // Recovery 11.3 krok 1: UNCONFIRMED tego sterownika — zwykła sesja z K1 potwierdzi przyjęty COMMIT.
                val registration = if (sameDeviceUnconfirmed) {
                    RegistrationState.ConfirmingSession
                } else {
                    RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE))
                }
                update { it.copy(registration = registration, connection = ConnectionState.DISCONNECTED) }
                evaluateConnection()
            }
            else -> Unit
        }
    }

    /** Odmowa nie zmienia poświadczeń, K1/K2 ani bondu (spec 12.4); UNCONFIRMED wraca do zwykłej sesji. */
    private suspend fun failRegistration(message: UiMessage) = abortPairingAttempt(RegistrationState.Failed(message))

    /** Przerywa próbę PAIR (kontrakt `abortPairing` jak `disconnect`), zeruje `K_setup` i wraca do zwykłej polityki łącza. */
    private suspend fun abortPairingAttempt(registration: RegistrationState?) {
        attempt { link.abortPairing() }
        endPairing()
        linkActive = false
        connectionId = null
        update { it.copy(registration = registration, connection = ConnectionState.DISCONNECTED) }
        evaluateConnection()
    }

    private fun endPairing() {
        pairing?.secret?.wipe()
        pairing = null
    }

    private fun onHandshakeRejected(reason: HandshakeRejectReason) {
        val unconfirmed = stored?.state == CredentialState.UNCONFIRMED
        val message = if (unconfirmed && reason == HandshakeRejectReason.AUTH_FAILED) {
            UiMessage(MessageId.REGISTRATION_INCOMPLETE)
        } else {
            Messages.handshakeReject(reason)
        }
        update {
            it.copy(
                handshakeProblem = message,
                // Odmowa ponawialna (12.4) nie kończy potwierdzania rejestracji — backoff i kolejna próba.
                registration = if (it.registration == RegistrationState.ConfirmingSession && !reason.isRetryable()) {
                    RegistrationState.Failed(message)
                } else {
                    it.registration
                },
            )
        }
        if (reason.isRetryable()) {
            scheduleRetry()
        } else {
            policy.onTerminalReject()
        }
    }

    /** Krótki odstęp po nieudanej próbie; nie zastępuje trwającego backoffu 12.4. */
    private fun scheduleShortRetry() {
        if (retryJob != null) return
        // Pierwsze próby szybkie (okno właściciela 30 s, 14.2), dalsze rzadsze — bez ciągłego skanowania w tle.
        scheduleRetry(nextShortRetryMs)
        retryIsShort = true
        nextShortRetryMs = minOf(nextShortRetryMs * 2, MAX_SHORT_RETRY_MS)
    }

    private fun scheduleRetry(delayMs: Long = policy.onRetryableReject()) {
        cancelRetry()
        retryIsShort = false
        val generation = ++retryGeneration
        retryJob = scope.launch {
            delay(delayMs)
            inbox.send(Msg.RetryTimer(generation))
        }
    }

    private fun canCommand(): Boolean = commandsAllowed(draft)

    /** Wspólny warunek dla intencji sterowania i `controlsEnabled` w UI. */
    private fun commandsAllowed(s: GateUiState): Boolean =
        s.connection == ConnectionState.READY && s.credential == CredentialState.ACTIVE && inFlight == null && queued == null &&
            s.firmwareUpdate != FirmwareUpdate.Rebooting // sterownik po OTA_OK na END nie wykonuje komend

    /**
     * Brak sesji READY: rozłączono, a stan reguły jest nieaktualny (spec 3). Potwierdzenia CLOSE i „Rozłącz”
     * dotyczyły stanu sprzed utraty sesji, więc przepadają — po powrocie użytkownik decyduje od nowa.
     */
    private fun markDisconnected() {
        update {
            val kept = it.confirmations.filterValues { c -> c !in SESSION_CONFIRMATIONS }
            it.copy(
                connection = ConnectionState.DISCONNECTED,
                rule = it.rule.copy(fresh = false, refreshing = false),
                confirmations = kept,
                warnings = if (UiSource.PHONE in kept) it.warnings else emptyList(),
            )
        }
    }

    private suspend fun onOpen() {
        if (!canCommand()) return
        val autoClose = draft.nextOpenAutoClose
        // Spec 4.2/7: przed OPEN(true) usługa połączenia; bez niej polecenie nie jest wysyłane, boolean bez zmian.
        // Problem dotyczy tylko OPEN(true); udane OPEN(false) albo udany start usługi go zdejmuje.
        backgroundBlocked = autoClose && !attempt { keeper.ensureBackgroundConnection() }.getOrDefault(false)
        refreshProblem()
        if (backgroundBlocked) return
        dispatch(GateCommand.Open(autoClose))
    }

    private fun clearConfirmation(source: UiSource) {
        update {
            it.copy(
                confirmations = it.confirmations - source,
                warnings = if (source == UiSource.PHONE) emptyList() else it.warnings,
            )
        }
    }

    private suspend fun dispatch(command: GateCommand) {
        // Za PING, otwarciem okna albo kawałkiem firmware: komenda wychodzi zaraz po ich odpowiedzi (13.3, 15.5).
        if (pingInFlight || auxInFlight != null || otaPending != null) {
            lastBeforeQueued = draft.lastCommand
            queued = command
        } else if (!send(command)) {
            linkFailed()
            return
        }
        update { it.copy(lastCommand = LastCommand(command, CommandOutcome.IN_FLIGHT, null)) }
    }

    /**
     * Synchronicznie odrzucony zapis GATT oznacza problem z łączem: sesja jest kończona i odtwarzana po krótkim
     * odstępie. Polecenie nie zostało wysłane — nie jest w toku ani nieznane.
     */
    private suspend fun linkFailed() {
        teardownLink()
        scheduleShortRetry()
    }

    /** `false`, gdy `:ble` odrzucił zapis od razu — komenda nie została wysłana i nie jest w toku. */
    private fun send(command: GateCommand): Boolean {
        if (attempt { link.send(command.toRequest()) }.isFailure) return false
        inFlight = command
        return true
    }

    /** Po rozstrzygnięciu PING: wysłać komendę czekającą albo, gdy sesja przepadła, wycofać ją bez wyniku. */
    private suspend fun releaseQueued(sessionAlive: Boolean) {
        val command = queued ?: return
        queued = null
        val restore = lastBeforeQueued
        lastBeforeQueued = null
        if (sessionAlive && send(command)) return
        update { it.copy(lastCommand = restore) }
        if (sessionAlive) linkFailed()
    }

    private suspend fun onCommandResult(event: LinkEvent.CommandResult) {
        if (event.request == SessionRequest.OpenPairingWindow) return onWindowResult(event)
        if (event.request.isClientsRequest) return onClientsAck(event)
        if (auxInFlight != null) {
            // Wynik innego żądania niż wysłane OPEN_PAIRING_WINDOW/zarządzanie telefonami (błąd `:ble`): nowa sesja, jak przy komendzie.
            auxInFlight = null
            return linkFailed()
        }
        val command = inFlight ?: return
        if (command.toRequest() != event.request) return onMismatchedResult(command)
        inFlight = null
        val outcome =
            if (event.ack.result == AckResult.OUTPUT_TRIGGERED) CommandOutcome.OUTPUT_TRIGGERED else CommandOutcome.REJECTED
        // Świadome ponowne uzbrojenie w czasie wyciszenia znów utrzymuje łącze do wyjścia z zasięgu (spec 4.3 pkt 5).
        if (ruleSuppressed && outcome == CommandOutcome.OUTPUT_TRIGGERED && command == GateCommand.Open(true)) {
            setRuleSuppressed(false)
        }
        finishCommand(LastCommand(command, outcome, event.ack.result))
        if (event.snapshotApplied) applyFreshSnapshot(event.ack.snapshot) else markFresh()
        pumpOta()
        evaluateConnection()
    }

    private suspend fun onCommandUnknown(request: SessionRequest) {
        if (request.isOta) {
            if (request !== otaPending) return
            // Sesja unieważniona (13.4): sterownik porzuca transfer; po wysłanym END mógł się już zrestartować.
            otaSessionLost()
            releaseQueued(sessionAlive = false)
            markStale()
            evaluateConnection()
            return
        }
        if (request.isClientsRequest) {
            if (auxInFlight != request) return
            // Wynik nieznany (13.4): lista pokazuje błąd z prośbą o odświeżenie; snapshot z ACK nie nadejdzie.
            auxInFlight = null
            update { it.copy(clients = ClientsUi.Error(UiMessage(MessageId.CLIENTS_RESULT_UNKNOWN))) }
            markStale()
            releaseQueued(sessionAlive = false)
            evaluateConnection()
            return
        }
        if (request == SessionRequest.OpenPairingWindow) {
            if (auxInFlight != SessionRequest.OpenPairingWindow) return
            // Wynik nieznany (13.4): bez komunikatu — użytkownik może otworzyć okno ponownie; snapshot z ACK nie nadejdzie.
            auxInFlight = null
            markStale()
            releaseQueued(sessionAlive = false)
            evaluateConnection()
            return
        }
        if (request == SessionRequest.Ping) {
            pingInFlight = false
            markStale()
            releaseQueued(sessionAlive = false)
            evaluateConnection()
            return
        }
        val command = inFlight ?: return
        if (command.toRequest() != request) return onMismatchedResult(command)
        inFlight = null
        finishCommand(LastCommand(command, CommandOutcome.OUTCOME_UNKNOWN, null))
        // Snapshot z ACK nie nadejdzie: odświeżanie oczekujące na tę komendę się kończy, stan reguły nieaktualny.
        markStale()
        evaluateConnection()
    }

    /**
     * Wynik dla innego żądania niż wysłane (błąd `:ble`): stan komendy jest niewiarygodny — OUTCOME_UNKNOWN
     * bez ponowienia (spec 13.4) i nowa sesja.
     */
    private suspend fun onMismatchedResult(command: GateCommand) {
        inFlight = null
        finishCommand(LastCommand(command, CommandOutcome.OUTCOME_UNKNOWN, null))
        linkFailed()
    }

    /** Wynik komendy do stanu i historii (spec 4.1: najwyżej 200 wpisów). Bez automatycznego ponowienia. */
    private suspend fun finishCommand(result: LastCommand) {
        update { it.copy(lastCommand = result) }
        // Historia nie może blokować sterowania: błąd zapisu jest pomijany.
        attempt {
            historyStore.append(HistoryEntry(clock.nowMillis(), result.command, result.outcome, result.ackResult))
            historyStore.trim(HISTORY_LIMIT)
        }
    }

    private suspend fun onLinkMessage(message: LinkMessage) {
        val event = message.event
        if (message.connectionId != connectionId) {
            // Zdarzenie innego (starego) połączenia — także już zakolejkowane w chwili jego zakończenia.
            if (event is LinkEvent.GrantReceived) event.clientKey.fill(0) // sekret nie zostaje w pamięci (9.2)
            return
        }
        onLinkEvent(event)
    }

    private suspend fun onLinkEvent(event: LinkEvent) {
        if (pairing != null) return onRegistrationEvent(event)
        if (event is LinkEvent.GrantReceived) {
            event.clientKey.fill(0) // spóźniony GRANT poza próbą: sekret nie może zostać w pamięci (9.2)
            return
        }
        // Brak poświadczenia (np. po „Usuń z telefonu”): spóźnione zdarzenia nie mogą przywrócić stanu.
        if (stored == null) return
        // Łącze zakończone przez domenę albo już utracone: spóźnione zdarzenia starego połączenia są nieaktualne.
        if (!linkActive) return
        when (event) {
            LinkEvent.Scanning -> setConnection(ConnectionState.SCANNING)
            LinkEvent.Connecting -> setConnection(ConnectionState.CONNECTING)
            LinkEvent.SecuringLink -> setConnection(ConnectionState.SECURING_LINK)
            LinkEvent.Authenticating -> setConnection(ConnectionState.AUTHENTICATING)
            LinkEvent.Rekeying -> setConnection(ConnectionState.REKEYING)
            is LinkEvent.Ready -> onReady(event.snapshot, event.info)
            is LinkEvent.RuleStatus -> if (event.applied) {
                readyLossCycles = 0
                applyFreshSnapshot(event.snapshot)
                evaluateConnection()
            }
            is LinkEvent.Pong -> {
                readyLossCycles = 0
                pingInFlight = false
                if (event.applied) applyFreshSnapshot(event.snapshot) else markFresh()
                releaseQueued(sessionAlive = true)
                trySendOwnName()
                pumpOta()
                evaluateConnection()
            }
            is LinkEvent.OtaResult -> onOtaResult(event)
            is LinkEvent.ClientList -> onClientList(event)
            is LinkEvent.CommandResult -> {
                readyLossCycles = 0
                onCommandResult(event)
            }
            is LinkEvent.CommandUnknown -> onCommandUnknown(event.request)
            LinkEvent.BondMissing, LinkEvent.BondFailed -> bondProblem(MessageId.BOND_MISSING)
            LinkEvent.SessionLost, LinkEvent.Disconnected -> if (repairing && !sessionReady) {
                bondProblem(MessageId.REPAIR_BOND_FAILED)
            } else {
                onLinkLost()
            }
            // ble_design §4.3a: na łączu, które osiągnęło READY, sesję odtwarza `:ble` bez zrywania BLE właściciela
            // (spec 10.4, 12.4) — domena tylko pokazuje stan i czeka na `Ready`.
            is LinkEvent.HandshakeRejected -> if (sessionReady && event.reason.isRetryable()) {
                keptLinkRecovering(Messages.handshakeReject(event.reason))
            } else {
                val repair = repairing && !sessionReady
                teardownLink()
                onHandshakeRejected(event.reason)
                if (repair) {
                    val message = if (event.reason == HandshakeRejectReason.STORAGE_FAILURE) {
                        UiMessage(MessageId.REPAIR_STORAGE_FAILURE)
                    } else {
                        Messages.handshakeReject(event.reason)
                    }
                    update { it.copy(handshakeProblem = message, bondRepairAvailable = true) }
                    // Naprawa wymaga obecności przy sterowniku: bez automatycznych ponowień, także po UNAVAILABLE.
                    cancelRetry()
                    policy.onTerminalReject()
                }
                evaluateConnection()
            }
            LinkEvent.HandshakeFailed -> if (sessionReady) {
                keptLinkRecovering(problem = null)
            } else if (repairing) {
                bondProblem(MessageId.REPAIR_BOND_FAILED)
            } else {
                teardownLink()
                // Błąd transportu/protokołu, nie odmowa: krótki odstęp, bez zużywania backoffu 12.4. Potwierdzanie
                // rejestracji trwa dalej (ponowienie), tak jak przy utracie łącza.
                scheduleShortRetry()
            }
            else -> Unit
        }
    }

    /** Odtwarzanie sesji przez `:ble` na zachowanym łączu: przyciski wyłączone, stan reguły nieaktualny. */
    private suspend fun keptLinkRecovering(problem: UiMessage?) {
        abandonSession()
        markStale()
        update { it.copy(connection = ConnectionState.REKEYING, handshakeProblem = problem ?: it.handshakeProblem) }
    }

    /**
     * Sesja przestała istnieć: PING bez odpowiedzi, komenda czekająca wycofana (nie była wysłana),
     * komenda wysłana → OUTCOME_UNKNOWN bez ponowienia (spec 13.4).
     */
    private suspend fun abandonSession() {
        pingInFlight = false
        auxInFlight = null
        update { if (it.clients == ClientsUi.Idle) it else it.copy(clients = ClientsUi.Idle) }
        otaSessionLost()
        releaseQueued(sessionAlive = false)
        inFlight?.let { command ->
            inFlight = null
            finishCommand(LastCommand(command, CommandOutcome.OUTCOME_UNKNOWN, null))
        }
    }

    private suspend fun onReady(snapshot: RuleSnapshot, info: LinkInfo) {
        sessionReady = true
        if (repairing) {
            repairing = false
            update { it.copy(notification = UiMessage(MessageId.REPAIR_DONE)) }
        }
        update { it.copy(bondRepairAvailable = false) }
        linkOtaSupported = info.otaSupported
        linkPairingWindowSupported = info.pairingWindowSupported
        linkMtu = info.mtu
        update { it.copy(firmwareVersion = info.firmwareVersion) }
        if (readyLossCycles < READY_LOSS_CYCLES_BEFORE_THROTTLE) nextShortRetryMs = RECONNECT_DELAY_MS
        policy.onReady()
        update { it.copy(handshakeProblem = null) }
        // Nowa sesja (także odtworzona na zachowanym łączu, 12.1): nic ze starej sesji nie zostaje w toku.
        abandonSession()
        val notify = lastFreshOwnArmed && snapshot.isRebootDisarmed()
        setConnection(ConnectionState.READY)
        // Najpierw aktywacja poświadczenia — ocena łącza może zakończyć sesję, gdy nie ma powodu jej trzymać.
        confirmCredential()
        applyFreshSnapshot(snapshot)
        if (draft.firmwareUpdate == FirmwareUpdate.Rebooting) rebootFinished(info.firmwareVersion)
        // REBOOT_DISARMED ma pierwszeństwo przed wynikiem aktualizacji (bezpieczeństwo przed informacją).
        if (notify) update { it.copy(notification = UiMessage(MessageId.RULE_REBOOT_DISARMED)) }
        trySendOwnName() // nazwa po rejestracji, gdy nie wyszła od razu (np. łącze odtworzone przed jej wysłaniem)
        evaluateConnection()
    }

    /**
     * Spec 11.3 krok 8: dopiero SESSION_READY potwierdza aktywację. Nieudany zapis zostawia UNCONFIRMED
     * z komunikatem; zapis jest ponawiany przy świadomej akcji użytkownika na tej samej sesji.
     */
    private suspend fun confirmCredential() {
        val credential = stored ?: return
        if (credential.state != CredentialState.UNCONFIRMED || draft.connection != ConnectionState.READY) return
        if (attempt { credentials.markActive() }.isFailure) {
            update {
                it.copy(
                    handshakeProblem = UiMessage(MessageId.REGISTRATION_INCOMPLETE),
                    registration = if (it.registration != null) RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)) else null,
                )
            }
            return
        }
        stored = StoredCredential(CredentialState.ACTIVE, credential.deviceId, credential.clientId)
        ownNamePending = true
        update {
            it.copy(
                credential = CredentialState.ACTIVE,
                handshakeProblem = null,
                notification = UiMessage(MessageId.REGISTRATION_DONE),
                registration = if (it.registration != null) RegistrationState.Done else null,
            )
        }
        trySendOwnName()
    }

    private suspend fun onLinkLost() {
        val attemptFailed = !sessionReady
        if (!attemptFailed) readyLossCycles++
        // Jawne zakończenie: kontrakt `GateLink.disconnect` odcina kolejne zdarzenia tego połączenia
        // (np. `Disconnected` po `SessionLost`), aby nie trafiły do nowej próby. `abandonSession` w teardownLink
        // rozstrzyga żądania, gdyby `CommandUnknown` nie dotarło (spec 13.4).
        teardownLink()
        // Zerwana sesja: natychmiastowy powrót (właściciel ma 30 s, 14.2). Nieudana próba: krótki odstęp,
        // aby sterownik poza zasięgiem nie powodował pętli łączenia.
        // Po kilku kolejnych cyklach READY→utrata bez wymiany wiadomości — rosnący odstęp zamiast pętli.
        if (attemptFailed || readyLossCycles > READY_LOSS_CYCLES_BEFORE_THROTTLE) scheduleShortRetry()
        evaluateConnection()
    }

    private suspend fun applyFreshSnapshot(snapshot: RuleSnapshot) {
        lastFreshOwnArmed = snapshot.isOwnArmed()
        cancelOwnerExpiry()
        policy.updateOwnerArmed(snapshot)
        update { it.copy(rule = RuleView(snapshot, fresh = true, refreshing = false)) }
        val previous = lastCached
        if (previous == null || previous.phase != snapshot.phase ||
            previous.ownerRelation != snapshot.ownerRelation || previous.reason != snapshot.reason
        ) {
            if (attempt { ruleCache.save(LastKnownRule(snapshot, clock.nowMillis())) }.isSuccess) lastCached = snapshot
        }
    }

    private fun markStale() {
        update { it.copy(rule = it.rule.copy(fresh = false, refreshing = false)) }
    }

    private fun markFresh() {
        update { it.copy(rule = it.rule.copy(fresh = it.rule.snapshot != null, refreshing = false)) }
    }

    private fun setConnection(connection: ConnectionState) {
        update { it.copy(connection = connection) }
    }

    /** Łączy albo rozłącza zgodnie z [ConnectionPolicy] (domain_design §6.2). */
    private suspend fun evaluateConnection() {
        val credential = stored
        val usable = linkUsable()
        policy.setCommandPending(inFlight != null || queued != null)
        policy.setConfirming(
            draft.registration == RegistrationState.ConfirmingSession && credential?.state == CredentialState.UNCONFIRMED,
        )
        if (policy.shouldConnect && usable && !linkActive && retryJob == null && pairing == null) {
            connectNow()
        } else if (linkActive && (!usable || !policy.shouldKeepLink)) {
            // Bez uprawnień BT lub dostępu do poświadczenia sesja nie może działać — także z komendą w toku.
            teardownLink()
        }
    }

    /** Sesja może działać: poświadczenie dostępne, Bluetooth włączony i z uprawnieniami. */
    private fun linkUsable(): Boolean {
        val credential = stored
        return !credentialLocked && credential != null && credential.state != CredentialState.NONE &&
            appProblem !in BLE_UNAVAILABLE
    }

    /**
     * Usługa w tle ma sens: jak [linkUsable], ale wyłączony Bluetooth jej nie kończy — właściciel, który włączy go
     * przed auto-CLOSE, musi móc wrócić (14.2), a start usługi z tła mógłby się już nie udać. Czas ogranicza
     * wygaśnięcie reguły. Bez uprawnień Bluetooth usługa `connectedDevice` nie wystartuje.
     */
    private fun backgroundLinkPossible(): Boolean {
        val credential = stored
        return !credentialLocked && credential != null && credential.state != CredentialState.NONE &&
            appProblem != SystemProblem.BLUETOOTH_PERMISSION_MISSING
    }

    /**
     * Domena kończy sesję lub nieudaną próbę handshake: rozłączenie (kontrakt `GateLink.disconnect` odcina
     * spóźnione zdarzenia), rozstrzygnięcie żądań w toku, reguła nieaktualna.
     */
    private suspend fun teardownLink() {
        if (linkActive) {
            attempt { link.disconnect() }
            linkActive = false
        }
        connectionId = null
        sessionReady = false
        repairing = false
        abandonSession()
        linkOtaSupported = false
        linkPairingWindowSupported = false
        linkMtu = 0
        update { it.copy(firmwareVersion = null) }
        markDisconnected()
        startOwnerExpiry()
    }

    /**
     * Po utracie łącza sterownik wykonuje własną regułę najpóźniej po [OWNER_LOSS_MS] (albo po `remainingMs`
     * BOOT_WAIT/WAITING); potem, z marginesem, reguła nie utrzymuje już łączenia w tle. Odliczanie startuje raz na
     * utratę — kolejne nieudane próby go nie przedłużają.
     */
    private fun startOwnerExpiry() {
        val snapshot = draft.rule.snapshot
        if (ownerExpired || ownerExpiryJob != null || snapshot == null || !snapshot.isOwnArmed()) return
        val generation = ++ownerExpiryGeneration
        val delayMs = maxOf(OWNER_LOSS_MS, snapshot.remainingMs) + OWNER_EXPIRY_MARGIN_MS
        ownerExpiryJob = scope.launch {
            delay(delayMs)
            inbox.send(Msg.OwnerArmedExpired(generation))
        }
    }

    /** Świeży snapshot albo usunięcie konfiguracji: reguła znów (albo wcale) nie jest „wygasła”. */
    private fun cancelOwnerExpiry() {
        ownerExpiryJob?.cancel()
        ownerExpiryJob = null
        ownerExpiryGeneration++
        ownerExpired = false
        policy.setOwnerArmedExpired(false)
    }

    /**
     * Brak bondu (`connect`) albo nieudana naprawa: połączenie zakończone, tekst z instrukcją, akcja „Napraw
     * połączenie” i bez automatycznych ponowień do działania użytkownika (jak odmowy nieponawiane 12.4).
     */
    private suspend fun bondProblem(id: MessageId) {
        val message = if (repairing) MessageId.REPAIR_BOND_FAILED else id
        teardownLink()
        update { it.copy(handshakeProblem = UiMessage(message), bondRepairAvailable = true) }
        policy.onTerminalReject()
        evaluateConnection()
    }

    private suspend fun onRepairBond() {
        reloadIfLocked()
        val credential = stored
        // Rejestracja i aktualizacja firmware mają własne łącze; bez poświadczenia nie ma czego naprawiać.
        if (credential == null || pairing != null || ota != null || !linkUsable()) return
        teardownLink()
        userAction()
        val key = attempt { credentials.clientKey() }.getOrElse {
            markCredentialLocked()
            return
        }
        val id = try {
            attempt { link.repairBond(credential.deviceId, credential.clientId, key) }.getOrNull()
        } finally {
            key.fill(0)
        }
        if (id == null) {
            update { it.copy(handshakeProblem = UiMessage(MessageId.REPAIR_BOND_FAILED), bondRepairAvailable = true) }
            return
        }
        linkActive = true
        connectionId = id
        sessionReady = false
        repairing = true
        update { it.copy(connection = ConnectionState.CONNECTING, handshakeProblem = null) }
    }

    private suspend fun onOpenPairingWindow() {
        if (!pairingWindowPossible(draft)) return
        if (attempt { link.send(SessionRequest.OpenPairingWindow) }.isFailure) {
            linkFailed()
            return
        }
        auxInFlight = SessionRequest.OpenPairingWindow
    }

    private val SessionRequest.isClientsRequest: Boolean
        get() = this == SessionRequest.ListClients || this is SessionRequest.RemoveClient || this is SessionRequest.SetClientName

    private fun clientsPossible(s: GateUiState): Boolean =
        s.connection == ConnectionState.READY && s.credential == CredentialState.ACTIVE &&
            inFlight == null && queued == null && !pingInFlight && auxInFlight == null && otaPending == null && ota == null &&
            s.firmwareUpdate != FirmwareUpdate.Rebooting

    /** Wysyła żądanie zarządzania telefonami, gdy sesja nie ma innego żądania w toku; `false` = nie wysłano. */
    private suspend fun sendClientsRequest(request: SessionRequest, silentAuto: Boolean = false): Boolean {
        if (!clientsPossible(draft)) return false
        if (attempt { link.send(request) }.isFailure) {
            linkFailed()
            return false
        }
        auxInFlight = request
        if (silentAuto) autoNameRequest = request
        if (request == SessionRequest.ListClients) update { it.copy(clients = ClientsUi.Loading) }
        return true
    }

    /** Wynik REMOVE_CLIENT/SET_CLIENT_NAME (albo ACK z błędem dla LIST_CLIENTS): komunikat i odświeżenie listy. */
    private suspend fun onClientsAck(event: LinkEvent.CommandResult) {
        val request = auxInFlight
        if (request == null || request != event.request) {
            auxInFlight = null
            return linkFailed()
        }
        auxInFlight = null
        val result = event.ack.result
        val silent = request === autoNameRequest
        if (silent) autoNameRequest = null
        var refresh = false
        val message: MessageId? = when (request) {
            is SessionRequest.RemoveClient -> when (result) {
                AckResult.CLIENT_REMOVED -> MessageId.CLIENT_REMOVED.also { refresh = true }
                AckResult.CLIENT_NOT_FOUND -> MessageId.CLIENT_ALREADY_REMOVED.also { refresh = true }
                AckResult.CANNOT_REMOVE_SELF -> MessageId.CLIENT_CANNOT_REMOVE_SELF
                AckResult.CLIENT_OWNS_RULE -> MessageId.CLIENT_OWNS_RULE
                AckResult.STORAGE_FAILURE -> MessageId.CLIENTS_STORAGE_FAILURE
                AckResult.BUSY -> MessageId.RESULT_BUSY
                else -> MessageId.RESULT_COMPATIBILITY_ERROR
            }
            is SessionRequest.SetClientName -> when (result) {
                AckResult.NAME_SET -> if (silent) null else MessageId.CLIENT_NAME_SET.also { refresh = true }
                AckResult.STORAGE_FAILURE -> if (silent) null else MessageId.CLIENTS_STORAGE_FAILURE
                AckResult.BUSY -> if (silent) null else MessageId.RESULT_BUSY
                else -> if (silent) null else MessageId.RESULT_COMPATIBILITY_ERROR
            }
            else -> MessageId.RESULT_COMPATIBILITY_ERROR // ListClients: błąd żądania (np. INVALID_ARGUMENT)
        }
        if (request == SessionRequest.ListClients) {
            update { it.copy(clients = ClientsUi.Error(UiMessage(MessageId.RESULT_COMPATIBILITY_ERROR))) }
        } else if (message != null) {
            update { it.copy(notification = UiMessage(message)) }
        }
        if (event.snapshotApplied) applyFreshSnapshot(event.ack.snapshot) else markFresh()
        releaseQueued(sessionAlive = true)
        // Zmiana listy po stronie sterownika: odśwież od razu albo oznacz listę jako nieaktualną (UI odświeży ją, gdy się da).
        if (refresh && !sendClientsRequest(SessionRequest.ListClients)) update { it.copy(clients = ClientsUi.Idle) }
        trySendOwnName()
        pumpOta()
        evaluateConnection()
    }

    private suspend fun onClientList(event: LinkEvent.ClientList) {
        val request = auxInFlight
        if (request != SessionRequest.ListClients || event.request != request) {
            auxInFlight = null
            return linkFailed()
        }
        auxInFlight = null
        readyLossCycles = 0
        update { it.copy(clients = ClientsUi.Ready(event.list.entries)) }
        releaseQueued(sessionAlive = true)
        trySendOwnName()
        pumpOta()
        evaluateConnection()
    }

    /** Nazwa telefonu po pierwszej sesji nowej rejestracji (client_management_design.md §5.3); błąd nie jest pokazywany. */
    private suspend fun trySendOwnName() {
        if (!ownNamePending || !clientsPossible(draft)) return
        val name = ClientName.truncate(phoneName())
        ownNamePending = false
        if (name.isEmpty()) return
        sendClientsRequest(SessionRequest.SetClientName(name), silentAuto = true)
    }

    /** Wynik OPEN_PAIRING_WINDOW: komunikat jednorazowy, bez historii i bez „ostatniej komendy” (to nie ruch bramy). */
    private suspend fun onWindowResult(event: LinkEvent.CommandResult) {
        if (auxInFlight != SessionRequest.OpenPairingWindow) return
        auxInFlight = null
        val message = if (event.ack.result == AckResult.WINDOW_OPENED) MessageId.PAIRING_WINDOW_OPENED else MessageId.RESULT_COMPATIBILITY_ERROR
        update { it.copy(notification = UiMessage(message)) }
        if (event.snapshotApplied) applyFreshSnapshot(event.ack.snapshot) else markFresh()
        releaseQueued(sessionAlive = true)
        pumpOta()
        evaluateConnection()
    }

    private fun pairingWindowPossible(s: GateUiState): Boolean =
        s.connection == ConnectionState.READY && s.credential == CredentialState.ACTIVE && linkPairingWindowSupported &&
            inFlight == null && queued == null && !pingInFlight && auxInFlight == null && otaPending == null && ota == null &&
            s.firmwareUpdate != FirmwareUpdate.Rebooting

    private suspend fun connectNow() {
        val credential = stored ?: return
        val key = attempt { credentials.clientKey() }.getOrElse {
            markCredentialLocked()
            return
        }
        val id = try {
            attempt { link.connect(credential.deviceId, credential.clientId, key) }.getOrNull()
        } finally {
            key.fill(0)
        }
        if (id == null) {
            // Np. chwilowy błąd GATT: łącze właściciela nie może zniknąć po cichu (spec 4.2, 14.2).
            scheduleShortRetry()
            return
        }
        linkActive = true
        connectionId = id
        sessionReady = false
        update { it.copy(rule = it.rule.copy(refreshing = !it.rule.fresh)) }
    }

    private fun update(change: (GateUiState) -> GateUiState) {
        draft = change(draft)
    }

    // --- powiadomienie „brama w zasięgu” (spec 4.3) ---

    private suspend fun onPresenceAppeared() {
        if (!draft.presenceNotificationEnabled) return // funkcja wyłączona w ustawieniach (spec 4.3 pkt 7)
        if (draft.presence != PresenceNotification.HIDDEN) {
            // QUIET trwa do zniknięcia z zasięgu; powrót w czasie karencji jest chwilową utratą reklam przez CDM.
            quietAbsenceJob?.cancel()
            quietAbsenceJob = null
            return
        }
        policy.liftUserSuspension()
        policy.setPresence(true)
        update { it.copy(presence = PresenceNotification.SHOWN) }
        // „Od razu nawiązuje sesję” (spec 4.3 pkt 2): krótki odstęp po nieudanej próbie nie opóźnia połączenia;
        // backoff po odmowie 12.4 (UNAVAILABLE/RATE_LIMITED) zostaje.
        if (retryIsShort) cancelRetry()
        evaluateConnection()
    }

    private suspend fun onPresenceDisappeared() {
        if (draft.presence == PresenceNotification.HIDDEN) return
        if (draft.presence == PresenceNotification.QUIET) {
            if (quietAbsenceJob == null) {
                val generation = presenceGeneration
                quietAbsenceJob = scope.launch {
                    delay(PRESENCE_ABSENCE_GRACE_MS)
                    inbox.send(Msg.QuietAbsenceOver(generation))
                }
            }
            return
        }
        resetPresence()
        evaluateConnection()
    }

    /** HIDDEN bez wyciszenia i wstrzymania reguły; licznik zatrzymany, stan utrwalony. */
    private suspend fun resetPresence() {
        quietAbsenceJob?.cancel()
        quietAbsenceJob = null
        presenceGeneration++
        policy.setPresence(false)
        ruleSuppressed = false
        policy.setOwnerArmedSuppressed(false)
        update { it.copy(presence = PresenceNotification.HIDDEN) }
        savePresence()
    }

    /** Odrzucenie powiadomienia: wyciszenie i rozłączenie także przy własnej uzbrojonej regule (decyzja 1.4). */
    private suspend fun onPresenceQuiet() {
        policy.setPresence(false)
        ruleSuppressed = true
        policy.setOwnerArmedSuppressed(true)
        update { it.copy(presence = PresenceNotification.QUIET) }
        savePresence()
        evaluateConnection()
    }

    private suspend fun setRuleSuppressed(value: Boolean) {
        ruleSuppressed = value
        policy.setOwnerArmedSuppressed(value)
        savePresence()
    }

    private suspend fun savePresence() {
        attempt { presenceStore.save(PresenceRecord(quiet = draft.presence == PresenceNotification.QUIET, ruleSuppressed = ruleSuppressed)) }
    }

    // --- aktualizacja firmware (spec 15.5, ota_design §5) ---

    private fun onSelectFirmware(bytes: ByteArray) {
        if (isTransferActive(draft.firmwareUpdate) || draft.firmwareUpdate == FirmwareUpdate.Rebooting) return
        when (val result = FirmwareImages.parse(bytes)) {
            is FirmwareImageResult.Invalid -> {
                pendingImage = null
                setFirmwareUpdate(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_FILE_INVALID)))
            }
            is FirmwareImageResult.Ok -> {
                pendingImage = result.image
                setFirmwareUpdate(FirmwareUpdate.Confirm(draft.firmwareVersion, result.image.version))
            }
        }
    }

    private suspend fun onConfirmFirmware() {
        val image = pendingImage ?: return
        // Poprzedni, anulowany transfer musi się rozstrzygnąć (OTA_ABORT) — inaczej jego spóźniony OTA_ACK trafiłby do nowego.
        if (draft.firmwareUpdate !is FirmwareUpdate.Confirm || !otaPossible(draft) || ota != null || otaPending != null) return
        pendingImage = null
        // Spec 15.5: przy MTU < 247 błąd bez wysyłania BEGIN.
        if (linkMtu < MIN_OTA_MTU) {
            setFirmwareUpdate(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_LINK_TOO_SMALL)))
            return
        }
        ota = OtaTransfer(image)
        setFirmwareUpdate(FirmwareUpdate.Transferring(0))
        pumpOta()
    }

    private suspend fun onCancelFirmware() {
        when (draft.firmwareUpdate) {
            is FirmwareUpdate.Confirm -> {
                pendingImage = null
                setFirmwareUpdate(null)
            }
            is FirmwareUpdate.Transferring, is FirmwareUpdate.EndRejected -> {
                val t = ota ?: return
                if (t.endSent) return // obraz mógł już zostać przyjęty — rozstrzygnie OTA_ACK albo restart
                t.aborting = true
                finishOta(UiMessage(MessageId.OTA_ABORTED))
                pumpOta() // OTA_ABORT, gdy sesja nie ma innego oczekującego żądania (13.3)
                evaluateConnection()
            }
            else -> Unit
        }
    }

    /**
     * Następne żądanie transferu: BEGIN, kolejne DATA po 470 B, END, a po anulowaniu OTA_ABORT. Tylko gdy sesja nie
     * ma innego oczekującego żądania — komenda i PING wchodzą między kawałki (13.3, 15.5).
     */
    private suspend fun pumpOta() {
        val t = ota ?: return
        if (draft.connection != ConnectionState.READY) return
        if (otaPending != null || inFlight != null || queued != null || pingInFlight || auxInFlight != null) return
        if (t.aborting) {
            sendOta(SessionRequest.OtaAbort)
            return
        }
        if (draft.firmwareUpdate !is FirmwareUpdate.Transferring) return
        val request = when {
            !t.begun -> SessionRequest.OtaBegin(t.size, t.image.sha256)
            t.offset < t.size -> {
                val end = minOf(t.offset + OtaPayloads.MAX_DATA, t.size)
                SessionRequest.OtaData(t.offset, t.image.bytes.copyOfRange(t.offset.toInt(), end.toInt()))
            }
            !t.endSent -> SessionRequest.OtaEnd
            else -> return
        }
        if (!sendOta(request)) return
        when (request) {
            is SessionRequest.OtaBegin -> t.begun = true
            SessionRequest.OtaEnd -> t.endSent = true
            else -> Unit
        }
    }

    /** `false`, gdy `:ble` odrzucił zapis od razu: łącze jest odtwarzane, a transfer kończy się jak przy utracie sesji. */
    private suspend fun sendOta(request: SessionRequest): Boolean {
        if (attempt { link.send(request) }.isFailure) {
            linkFailed()
            return false
        }
        otaPending = request
        return true
    }

    private suspend fun onOtaResult(event: LinkEvent.OtaResult) {
        val pending = otaPending ?: return // spóźniony wynik (np. po utracie sesji)
        otaPending = null
        if (event.request !== pending) {
            // Wynik innego żądania niż wysłane (błąd `:ble`): stan transferu niewiarygodny.
            ota?.aborting = false
            ota?.let { finishOta(UiMessage(MessageId.OTA_ABORTED)) }
            linkFailed()
            return
        }
        readyLossCycles = 0
        val t = ota
        when {
            t == null -> Unit
            // OTA_ABORT wyśle pumpOta, gdy sesja będzie wolna.
            t.aborting -> if (pending == SessionRequest.OtaAbort) ota = null
            else -> onOtaAck(t, pending, event.ack.result, event.ack.nextOffset)
        }
        if (draft.firmwareUpdate == FirmwareUpdate.Rebooting) {
            // Po OTA_OK na END sterownik nie wykonuje już komend (control_enabled = false) i zaraz się restartuje:
            // komenda z kolejki nie była wysłana i jest wycofywana bez wyniku.
            pingQueued = false
            releaseQueued(sessionAlive = false)
        }
        // Wspólna kolejka (13.3): najpierw komenda, potem PING, potem następny kawałek.
        if (otaPending == null && draft.connection == ConnectionState.READY) {
            if (queued != null) {
                releaseQueued(sessionAlive = true)
            } else if (pingQueued) {
                pingQueued = false
                if (attempt { link.send(SessionRequest.Ping) }.isSuccess) pingInFlight = true else linkFailed()
            }
        }
        pumpOta()
        evaluateConnection()
    }

    private suspend fun onOtaAck(t: OtaTransfer, request: SessionRequest, result: OtaResult, nextOffset: Long) {
        when (request) {
            is SessionRequest.OtaBegin ->
                if (result == OtaResult.OK) publishProgress(t) else finishOta(Messages.otaResult(result))
            is SessionRequest.OtaData -> when {
                result != OtaResult.OK -> finishOta(Messages.otaResult(result))
                nextOffset != request.offset + request.data.size -> {
                    // Sterownik liczy inaczej niż telefon: transfer niespójny; OTA_ABORT wyśle pumpOta.
                    t.aborting = true
                    finishOta(UiMessage(MessageId.OTA_ABORTED))
                }
                else -> {
                    t.offset = nextOffset
                    publishProgress(t)
                }
            }
            SessionRequest.OtaEnd -> when (result) {
                OtaResult.OK -> enterRebooting(t)
                // Dane zostają u sterownika; END można ponowić po rozbrojeniu reguły (15.5, A77).
                OtaResult.RULE_ACTIVE, OtaResult.BUSY -> {
                    t.endSent = false
                    setFirmwareUpdate(FirmwareUpdate.EndRejected(Messages.otaResult(result)))
                }
                else -> finishOta(Messages.otaResult(result))
            }
            else -> Unit
        }
    }

    private fun publishProgress(t: OtaTransfer) {
        setFirmwareUpdate(FirmwareUpdate.Transferring((t.offset * 100 / t.size).toInt()))
    }

    /** Sesja przepadła: sterownik porzuca transfer (15.5 pkt 4); po wysłanym END mógł się już zrestartować. */
    private fun otaSessionLost() {
        otaPending = null
        pingQueued = false
        val t = ota ?: return
        when {
            t.aborting -> ota = null
            t.endSent -> enterRebooting(t)
            else -> finishOta(UiMessage(MessageId.OTA_ABORTED))
        }
    }

    private fun enterRebooting(t: OtaTransfer) {
        rebootTargetVersion = t.image.version
        ota = null
        setFirmwareUpdate(FirmwareUpdate.Rebooting)
    }

    /** Pierwsze READY po restarcie: wersja z Device Information rozstrzyga wynik (15.5). */
    private fun rebootFinished(version: String?) {
        val target = rebootTargetVersion
        rebootTargetVersion = null
        if (version == null) {
            setFirmwareUpdate(null) // nie da się rozstrzygnąć (ota_design §8 pkt 4)
            return
        }
        val message = if (version == target) {
            UiMessage(MessageId.OTA_DONE, MessageArgs.Version(version))
        } else {
            UiMessage(MessageId.OTA_ROLLED_BACK, MessageArgs.Version(version))
        }
        setFirmwareUpdate(FirmwareUpdate.Finished(message))
        update { it.copy(notification = message) }
    }

    /** Koniec transferu z komunikatem; anulowany transfer zostaje do wysłania OTA_ABORT. */
    private fun finishOta(message: UiMessage) {
        if (ota?.aborting != true) ota = null
        setFirmwareUpdate(FirmwareUpdate.Finished(message))
    }

    /** „Usuń z telefonu”: aktualizacja dotyczyła usuniętej konfiguracji. */
    private fun resetFirmwareUpdate() {
        ota = null
        otaPending = null
        pendingImage = null
        pingQueued = false
        rebootTargetVersion = null
        setFirmwareUpdate(null)
    }

    /**
     * Jedyne miejsce zmiany stanu aktualizacji: powód łącza w [ConnectionPolicy], licznik 90 s po restarcie,
     * potwierdzenie „Rozłącz” przerywające transfer.
     */
    private fun setFirmwareUpdate(value: FirmwareUpdate?) {
        val previous = draft.firmwareUpdate
        val wasActive = isTransferActive(previous)
        val active = isTransferActive(value)
        update { it.copy(firmwareUpdate = value) }
        policy.setUpdating(active || value == FirmwareUpdate.Rebooting)
        if (value == FirmwareUpdate.Rebooting) {
            if (previous != FirmwareUpdate.Rebooting) startRebootTimer()
        } else {
            rebootJob?.cancel()
            rebootJob = null
            rebootGeneration++
        }
        if (wasActive && !active) {
            update {
                val phoneConfirm = it.confirmations[UiSource.PHONE] == Confirmation.DISCONNECT_ABORTS_UPDATE
                it.copy(
                    confirmations = it.confirmations.filterValues { c -> c != Confirmation.DISCONNECT_ABORTS_UPDATE },
                    warnings = if (phoneConfirm) emptyList() else it.warnings,
                )
            }
        }
    }

    private fun startRebootTimer() {
        rebootJob?.cancel()
        val generation = ++rebootGeneration
        rebootJob = scope.launch {
            delay(REBOOT_WAIT_MS)
            inbox.send(Msg.RebootTimeout(generation))
        }
    }

    private fun otaPossible(s: GateUiState): Boolean =
        s.connection == ConnectionState.READY && s.credential == CredentialState.ACTIVE && linkOtaSupported

    private fun publish(s: GateUiState): GateUiState {
        val withControls = s.copy(
            controlsEnabled = commandsAllowed(s),
            connectSuspended = policy.suspended,
            notificationActionsAvailable = s.presence == PresenceNotification.SHOWN && commandsAllowed(s),
            backgroundLinkNeeded = policy.shouldConnectWithoutPhoneUi && backgroundLinkPossible(),
            firmwareUpdateAvailable = otaPossible(s) && ota == null && otaPending == null &&
                (s.firmwareUpdate == null || s.firmwareUpdate is FirmwareUpdate.Finished),
            pairingWindowAvailable = pairingWindowPossible(s),
            pairingWindowSupported = s.connection == ConnectionState.READY && s.credential == CredentialState.ACTIVE &&
                linkPairingWindowSupported,
            clientsAvailable = clientsPossible(s),
        )
        return withControls.copy(messages = UiMessages.phone(withControls), carMessages = UiMessages.car(withControls))
    }
}

private const val HISTORY_LIMIT = 200
private const val PRESENCE_ABSENCE_GRACE_MS = 30_000L
/** Utrata właściciela → WAITING 30 s → CLOSE (spec 14.2). */
private const val OWNER_LOSS_MS = 30_000L
private const val OWNER_EXPIRY_MARGIN_MS = 30_000L
private const val CLIENT_ID_SIZE = 16
private const val RECONNECT_DELAY_MS = 2_000L
private const val MAX_SHORT_RETRY_MS = 60_000L
private const val READY_LOSS_CYCLES_BEFORE_THROTTLE = 3
/** Problemy zgłaszane przez `:app`, przy których próby łączenia nie mają sensu (zniknięcie problemu łączy). */
private val BLE_UNAVAILABLE = setOf(SystemProblem.BLUETOOTH_OFF, SystemProblem.BLUETOOTH_PERMISSION_MISSING)
private val SESSION_CONFIRMATIONS = setOf(Confirmation.DISCONNECT_ARMS_AUTOCLOSE, Confirmation.DISCONNECT_ABORTS_UPDATE)

/** Spec 15.5: OTA wymaga ATT_MTU ≥ 247. */
private const val MIN_OTA_MTU = 247

/** Spec 15.5: brak READY przez 90 s po restarcie → komunikat. */
private const val REBOOT_WAIT_MS = 90_000L

/** Transfer w toku: przesyłanie albo END do ponowienia. */
private fun isTransferActive(update: FirmwareUpdate?): Boolean =
    update is FirmwareUpdate.Transferring || update is FirmwareUpdate.EndRejected

/** Spec 12.4: tylko UNAVAILABLE i RATE_LIMITED są ponawiane automatycznie z backoffem. */
private fun HandshakeRejectReason.isRetryable() =
    this == HandshakeRejectReason.UNAVAILABLE || this == HandshakeRejectReason.RATE_LIMITED

private fun GateCommand.toRequest(): SessionRequest = when (this) {
    is GateCommand.Open -> SessionRequest.Open(autoCloseOnDisconnect)
    GateCommand.Close -> SessionRequest.Close
}

