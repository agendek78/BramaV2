package com.agendek.bramav2

import com.agendek.bramav2.ui.defaultPhoneName
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.agendek.bramav2.ble.android.AndroidBleRadio
import com.agendek.bramav2.ble.core.BleGateLink
import com.agendek.bramav2.car.CarDependencies
import com.agendek.bramav2.car.CarPresenceNotifier
import com.agendek.bramav2.ble.android.AndroidGateWatcher
import com.agendek.bramav2.companion.CompanionAssociation
import com.agendek.bramav2.companion.DismissWhenUiVisibleSink
import com.agendek.bramav2.companion.PresenceGate
import com.agendek.bramav2.companion.PresenceSink
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.UiSource
import com.agendek.bramav2.service.Channels
import com.agendek.bramav2.service.ServiceController
import com.agendek.bramav2.store.DataStoreSettings
import com.agendek.bramav2.store.FileCredentialStore
import com.agendek.bramav2.store.FileHistoryStore
import com.agendek.bramav2.store.KeystoreCipher
import com.agendek.bramav2.system.SystemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ręczne składanie zależności (app_design §8): jeden zakres, radio, łącze, magazyny i repozytorium. */
class BramaApp : Application(), CarDependencies {

    lateinit var graph: AppGraph
        private set

    override val repository: GateRepository get() = graph.repository
    override val carNotifier: CarPresenceNotifier get() = graph.carNotifier

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        graph = AppGraph(this)
        graph.start()
        // Wątek główny: CarConnection (LiveData) i polityka powiadomienia w samochodzie (spec 5.4).
        graph.carNotifier.start(CoroutineScope(SupervisorJob() + Dispatchers.Main))
        // Proces na pierwszym planie = UI telefonu (app_design §3); obrót ekranu ani skaner QR nie odpinają UI.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.onUiVisible(true)
            override fun onStop(owner: LifecycleOwner) = graph.onUiVisible(false)
        })
    }
}

class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val radio = AndroidBleRadio(app)
    val gateWatcher = AndroidGateWatcher(app)
    val link = BleGateLink(scope, radio, { SystemClock.elapsedRealtime() })
    val settings = DataStoreSettings(
        DataStoreSettings.create(File(app.noBackupFilesDir, "settings.preferences_pb"), CoroutineScope(SupervisorJob() + Dispatchers.IO)),
    )
    val credentials = FileCredentialStore(app.noBackupFilesDir, KeystoreCipher())
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible
    val serviceController = ServiceController(app, uiVisible)
    val repository = GateRepository(
        scope = scope,
        link = link,
        credentials = credentials,
        settings = settings,
        historyStore = FileHistoryStore(File(app.noBackupFilesDir, "history.jsonl")),
        ruleCache = settings,
        keeper = serviceController,
        presenceStore = settings.presence,
        clock = { System.currentTimeMillis() },
        phoneName = { defaultPhoneName(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.MODEL.orEmpty()) },
    )
    val systemStatus = SystemStatus(app, repository)
    val carNotifier = CarPresenceNotifier(app, repository)
    val companion = CompanionAssociation(app, credentials, link, radio)
    val presenceGate = PresenceGate(
        DismissWhenUiVisibleSink(
            delegate = object : PresenceSink {
                override fun appeared() = repository.presenceAppeared()
                override fun disappeared() = repository.presenceDisappeared()
            },
            uiVisible = { uiVisible.value },
            dismiss = { repository.notificationDismissed() },
        ),
    )

    fun start() {
        link.start()
        scope.launch { companion.restore() }
        repository.start()
        serviceController.observe(repository, scope)
        systemStatus.register()
        // Przełącznik w ustawieniach i stan poświadczenia od razu zmieniają warunki bramki obecności
        // (np. ponowne włączenie przy bramie, wczytanie poświadczenia po zimnym starcie z obecności).
        scope.launch {
            repository.state.map(::presenceEnabled).distinctUntilChanged().collect {
                withContext(Dispatchers.Main) { refreshPresenceConditions() }
            }
        }
        // „Usuń z telefonu”: powiązanie dotyczyło usuniętej konfiguracji (app_design §5).
        scope.launch {
            repository.state.map { it.credential }.distinctUntilChanged().drop(1).collect {
                if (it == CredentialState.NONE) companion.disassociateAll()
            }
        }
    }

    /** Wołane na wątku głównym przy przejściu procesu na pierwszy plan i z niego. */
    fun onUiVisible(visible: Boolean) {
        _uiVisible.value = visible
        if (visible) {
            systemStatus.refresh()
            refreshPresenceConditions()
            repository.uiAttached(UiSource.PHONE)
            // Powiadomienie „brama w zasięgu” tylko przy aplikacji w tle; wyjście na pierwszy plan (tap albo ręcznie) je odrzuca.
            repository.notificationDismissed()
        } else {
            repository.uiDetached(UiSource.PHONE)
        }
    }

    /** Zmiana ustawienia lub zgody na powiadomienia (np. przy powrocie do aplikacji) → bramka obecności. */
    fun refreshPresenceConditions() {
        presenceGate.onConditionsChanged(presenceEnabled(repository.state.value), SystemStatus.notificationsAllowed(app))
    }

    /** Sterownik w zasięgu (CompanionDeviceService); wołane na wątku głównym. */
    fun onPresenceAppeared() {
        presenceGate.onAppeared(presenceEnabled(repository.state.value), SystemStatus.notificationsAllowed(app))
    }

    /** Obecność ma znaczenie tylko dla skonfigurowanej bramy przy włączonym ustawieniu (spec 4.3 pkt 7). */
    private fun presenceEnabled(s: GateUiState): Boolean =
        s.presenceNotificationEnabled && s.credential == CredentialState.ACTIVE
}

val android.content.Context.graph: AppGraph get() = (applicationContext as BramaApp).graph
