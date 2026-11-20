package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.GateLink
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.domain.LinkInfo
import com.agendek.bramav2.domain.LinkMessage
import com.agendek.bramav2.protocol.ClientHandshake
import com.agendek.bramav2.protocol.ClientSession
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.SessionEvent
import com.agendek.bramav2.protocol.HandshakeStep
import com.agendek.bramav2.protocol.NonceSource
import com.agendek.bramav2.protocol.PairingHandshake
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.PakeStep
import com.agendek.bramav2.protocol.PinPakeHandshake
import com.agendek.bramav2.protocol.ProvisioningQr
import com.agendek.bramav2.protocol.SecureRandomNonceSource
import com.agendek.bramav2.protocol.SessionHandshake
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Implementacja portu [GateLink] nad [BleRadio] (ble_design §4). Jeden aktor: polecenia `:domain`, zdarzenia
 * radia i upływy timerów są przetwarzane sekwencyjnie z kanału; obiekty `:protocol` (handshake, sesja) żyją
 * wyłącznie w aktorze. Jedno połączenie naraz (spec decyzja 8: jedna brama).
 */
class BleGateLink(
    private val scope: CoroutineScope,
    private val radio: BleRadio,
    private val clock: () -> Long,
    private val nonces: NonceSource = SecureRandomNonceSource(),
) : GateLink {

    private val out = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 1024)
    override val events: Flow<LinkMessage> = out

    private sealed interface Input {
        class Connect(val id: Long, val deviceId: ByteArray, val clientId: ByteArray, val key: ByteArray, val repair: Boolean) : Input
        class Pair(val id: Long, val secret: PairingSecret, val clientId: ByteArray) : Input
        data object Commit : Input
        data object Abort : Input
        data class Send(val request: SessionRequest) : Input
        data object Disconnect : Input
        class Radio(val event: RadioEvent) : Input
        data class Timer(val connectionId: Long, val kind: TimerKind, val generation: Int) : Input
    }

    private data class AddressFailures(val attempts: Int, val lastAt: Long, val foreign: Boolean)

    private enum class TimerKind { SCAN, RADIO_CONNECT, GATT, RESPONSE, ATTEMPT, RECOVERY }

    private enum class Stage { SCANNING, CONNECTING, SETUP, HANDSHAKE, READY }

    /** Stan jednego połączenia; zmieniany wyłącznie w aktorze. */
    private inner class Conn(
        val id: Long,
        /** Przy rejestracji PIN-em: z INFO (podpowiedź), potwierdzany dopiero przez PAIR_CHALLENGE (11.4). */
        var deviceId: ByteArray,
        val clientId: ByteArray,
        /** `K_client` (sesja), `K_setup` albo `K_pin` (rejestracja) — kopia, zerowana przy zamknięciu. */
        var key: ByteArray,
        val pairing: Boolean = false,
        /** Rejestracja PIN-em: kopia PIN-u, zerowana przy zamknięciu. */
        val pin: PairingSecret.Pin? = null,
        /** Naprawa bondu (9.5): sesja z `K_client`, parowanie systemowe dozwolone. */
        val repair: Boolean = false,
    ) {
        /** Klucz w [knownAddresses]. */
        val deviceKey: String get() = deviceId.toHex()
        var pake: PinPakeHandshake? = null
        var grantReceived = false

        /** Wysłano PAIR_COMMIT: sterownik mógł zatwierdzić klienta, więc bond systemowy może być jeszcze potrzebny. */
        var commitSent = false

        /** Rejestracja zakończona (PAIR_DONE): bond zostaje. */
        var pairingDone = false

        /** Z Device Information i INFO bieżącego połączenia (spec 8.2, 15.5) — do [LinkInfo] w `Ready`. */
        var firmwareVersion: String? = null
        var otaSupported = false
        var pairingWindowSupported = false

        /** INFO zweryfikowane — od tej chwili każda porażka kończy próbę (bez ponownego skanu). */
        var verified = false

        /** Próba zaczęła się od zapamiętanego adresu; jednorazowy powrót do skanu, gdy zawiedzie przed weryfikacją. */
        var fromMemory = false
        var rescanned = false

        /** Okno skanu minęło w trakcie weryfikacji kandydata: po jego porażce koniec próby. */
        var scanExpired = false

        /** Porażki adresów w tej próbie (limit podejść, przerwa przed ponowieniem, obce urządzenia). */
        val failures = mutableMapOf<String, AddressFailures>()

        fun isRejected(address: String): Boolean =
            failures[address]?.let { it.foreign || it.attempts >= MAX_ATTEMPTS_PER_ADDRESS } == true

        /** Kolejka i generacja, dla których biegnie bieżący timer GATT (timeout na operację, nie na serię). */
        var gattTimerKey: Pair<GattQueue, Int>? = null
        var stage = Stage.SCANNING
        var scanning = false
        val candidates = ArrayDeque<String>()
        var address: String? = null
        var handle: RadioHandle? = null
        var queue: GattQueue? = null
        var channel: FrameChannel? = null
        var handshake: ClientHandshake? = null
        var session: ClientSession? = null
        var retransmissions = 0

        /** Łącze osiągnęło READY: błędy handshake nie zrywają go (ble_design §4.3a). */
        var everReady = false
        var nextFailureRetryMs = FAILURE_RETRY_MS
        var nextRejectRetryMs = REJECT_RETRY_MS
        val timers = mutableMapOf<TimerKind, Pair<Int, Job>>()
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val nextId = AtomicLong(0)
    private val started = AtomicBoolean(false)
    private var timerGeneration = 0
    private var conn: Conn? = null

    /** `device_id` (hex) → ostatni adres, pod którym znaleziono sterownik (ble_design §4.2). */
    /** `device_id` (hex) → adres sterownika; czytany także spoza aktora ([knownAddress]). */
    private val knownAddresses = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { radio.events.collect { inbox.send(Input.Radio(it)) } }
        scope.launch {
            for (input in inbox) {
                try {
                    handle(input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Wyjątek nie może wyciszyć łącza na zawsze: bieżąca próba kończy się `Disconnected`.
                    conn?.let { attemptFailed(it) }
                }
                conn?.let(::syncGattTimer)
            }
        }
    }

    override fun connect(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long {
        val id = nextId.incrementAndGet()
        inbox.trySend(Input.Connect(id, deviceId.copyOf(), clientId.copyOf(), clientKey.copyOf(), repair = false))
        return id
    }

    override fun repairBond(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long {
        val id = nextId.incrementAndGet()
        inbox.trySend(Input.Connect(id, deviceId.copyOf(), clientId.copyOf(), clientKey.copyOf(), repair = true))
        return id
    }

    override fun pair(secret: PairingSecret, clientId: ByteArray): Long {
        val id = nextId.incrementAndGet()
        val copy = when (secret) {
            is PairingSecret.Qr -> PairingSecret.Qr(ProvisioningQr(secret.qr.deviceId.copyOf(), secret.qr.setupKey.copyOf(), secret.qr.pin.copyOf()))
            is PairingSecret.Pin -> PairingSecret.Pin(secret.pin)
        }
        inbox.trySend(Input.Pair(id, copy, clientId.copyOf()))
        return id
    }

    override fun commitPairing() {
        inbox.trySend(Input.Commit)
    }

    override fun abortPairing() {
        inbox.trySend(Input.Abort)
    }

    override fun send(request: SessionRequest) {
        inbox.trySend(Input.Send(request))
    }

    /** Adres sterownika zapamiętany po weryfikacji (do powiązania CDM, app_design §5); `null`, gdy nieznany. */
    fun knownAddress(deviceId: ByteArray): String? = knownAddresses[deviceId.toHex()]

    /** Wszystkie zapamiętane adresy sterowników (do usunięcia bondów przy „Usuń z telefonu”). */
    fun knownAddresses(): Collection<String> = knownAddresses.values.toList()

    /** Adres z powiązania CDM: kolejne `connect` łączą się od razu, bez skanu. */
    fun rememberAddress(deviceId: ByteArray, address: String) {
        knownAddresses[deviceId.toHex()] = address
    }

    override fun disconnect() {
        inbox.trySend(Input.Disconnect)
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Connect -> {
                close(conn)
                val c = Conn(input.id, input.deviceId, input.clientId, input.key, repair = input.repair)
                conn = c
                begin(c)
            }
            is Input.Pair -> {
                close(conn)
                val c = when (val secret = input.secret) {
                    is PairingSecret.Qr -> {
                        secret.qr.pin.fill(0) // ścieżka QR używa wyłącznie K_setup
                        Conn(input.id, secret.qr.deviceId, input.clientId, secret.qr.setupKey, pairing = true)
                    }
                    is PairingSecret.Pin -> Conn(input.id, ByteArray(16), input.clientId, ByteArray(0), pairing = true, pin = secret)
                }
                conn = c
                begin(c)
            }
            Input.Commit -> conn?.let { c ->
                val hs = c.handshake as? PairingHandshake ?: return@let
                // Powtórzone commitPairing() (po wysłaniu COMMIT) jest ignorowane.
                runCatching { hs.commit() }.getOrNull()?.let {
                    c.commitSent = true
                    sendRequest(c, it)
                }
            }
            Input.Abort -> conn?.takeIf { it.pairing }?.let(::close) // bez zdarzeń (kontrakt abortPairing)
            is Input.Send -> onSend(input.request)
            Input.Disconnect -> close(conn)
            is Input.Radio -> conn?.let { onRadio(it, input.event) }
            is Input.Timer -> conn?.takeIf { it.id == input.connectionId }?.let { onTimer(it, input) }
        }
    }

    // --- nawiązanie połączenia (spec 8.1) ---

    private suspend fun begin(c: Conn) {
        // Rejestracja PIN-em nie zna `device_id`: zawsze skan (11.4).
        val known = if (c.pin == null) knownAddresses[c.deviceKey] else null
        if (known != null) {
            c.fromMemory = true
            connectRadio(c, known)
        } else {
            startScan(c)
        }
    }

    private suspend fun startScan(c: Conn) {
        c.stage = Stage.SCANNING
        c.scanning = true
        emit(c, LinkEvent.Scanning)
        radio.startScan(GattProfile.SERVICE)
        arm(c, TimerKind.SCAN, SCAN_TIMEOUT_MS)
    }

    private suspend fun connectRadio(c: Conn, address: String) {
        c.stage = Stage.CONNECTING
        c.address = address
        val h = radio.connect(address)
        c.handle = h
        val queue = GattQueue(radio, h, clock)
        c.queue = queue
        c.channel = FrameChannel(queue, clock)
        emit(c, LinkEvent.Connecting)
        // Kandydat ze skanu, który nie zestawia łącza (np. sterownik sąsiada z filtrem połączeń, spec 8.3), nie może
        // zjeść całego okna skanu.
        val remembered = address == knownAddresses[c.deviceKey]
        arm(c, TimerKind.RADIO_CONNECT, if (remembered) RADIO_CONNECT_TIMEOUT_MS else CANDIDATE_CONNECT_TIMEOUT_MS)
    }

    private suspend fun onRadio(c: Conn, e: RadioEvent) {
        if (e is RadioEvent.ScanResult) {
            val address = e.address
            // Android raportuje reklamującego wielokrotnie: bez duplikatów i bez ponownych prób odrzuconych adresów.
            if (!c.scanning || c.isRejected(address) || address == c.address || address in c.candidates) return
            // Świeżo po close() Android często od razu zwraca status 133 — ponowienie dopiero po przerwie.
            val failedAt = c.failures[address]?.lastAt
            if (failedAt != null && clock() - failedAt < RETRY_PAUSE_MS) return
            if (c.handle == null) connectRadio(c, address) else c.candidates.addLast(address)
            return
        }
        e as RadioEvent.OfHandle
        if (e.handle != c.handle) return // zdarzenie starego/obcego uchwytu
        when (e) {
            is RadioEvent.Connected -> {
                // Niektóre stosy zgłaszają CONNECTED ponownie (np. po bondzie) — tylko pierwsze zestawia łącze.
                if (c.stage != Stage.CONNECTING) return
                cancel(c, TimerKind.RADIO_CONNECT)
                c.stage = Stage.SETUP
                c.queue?.enqueue(GattOp.Discover)
            }
            is RadioEvent.Disconnected -> onRadioLost(c)
            is RadioEvent.TxIndication -> c.channel?.onIndication(e.value)?.let { onFrame(c, it) }
            else -> c.queue?.onEvent(e)?.let { onGattCompletion(c, it) }
        }
    }

    private suspend fun onGattCompletion(c: Conn, done: GattCompletion) {
        val ok = done.result is GattResult.Ok
        when (done.op) {
            GattOp.Discover -> if (ok) c.queue?.enqueue(GattOp.ReadInfo) else candidateFailed(c)
            GattOp.ReadInfo -> onInfo(c, (done.result as? GattResult.Ok)?.value as? ByteArray)
            GattOp.ReadFirmwareRevision -> {
                // Brak wersji nie przerywa połączenia: służy tylko do pokazania wersji i rozpoznania powrotu (8.2).
                c.firmwareVersion = GattProfile.firmwareRevisionOf((done.result as? GattResult.Ok)?.value as? ByteArray)
                secureLink(c)
            }
            GattOp.CreateBond -> when {
                ok -> c.queue?.enqueue(GattOp.EnableIndications)
                c.repair -> endWith(c, LinkEvent.BondFailed)
                else -> attemptFailed(c)
            }
            GattOp.EnableIndications -> if (ok) c.queue?.enqueue(GattOp.RequestMtu(GattProfile.REQUESTED_MTU)) else attemptFailed(c)
            is GattOp.RequestMtu -> {
                // Porażka negocjacji: praca przy minimalnym MTU (spec 8.1 krok 6).
                c.channel?.mtu = ((done.result as? GattResult.Ok)?.value as? Int) ?: GattProfile.MIN_MTU
                startHandshake(c)
            }
            is GattOp.WriteRx -> when (c.channel?.onWriteCompleted(ok, done.startedAt)) {
                // Odpowiedź mogła przyjść przed callbackiem zapisu (Android nie gwarantuje kolejności):
                // T_response tylko, gdy żądanie nadal czeka.
                SendResult.Sent -> if (hasPendingRequest(c)) arm(c, TimerKind.RESPONSE, c.channel!!.responseBudgetMs())
                SendResult.Failed -> if (c.everReady) writeLost(c) else attemptFailed(c)
                null -> Unit
            }
        }
    }

    private suspend fun onInfo(c: Conn, value: ByteArray?) {
        if (c.pin != null) return onPinCandidateInfo(c, value)
        val info = value?.let(GateInfo::parse)
        if (info == null || !info.deviceId.contentEquals(c.deviceId)) {
            // Obce jest urządzenie z innym `device_id` (A10) — także w INFO innej wersji (np. sąsiad z nowszym
            // firmware). Brak odczytu albo nieznany format z naszym `device_id` to porażka chwilowa.
            val foreign = value?.let(GateInfo::deviceIdOf)?.let { !it.contentEquals(c.deviceId) } == true
            candidateFailed(c, foreign)
            return
        }
        stopScanning(c)
        c.verified = true
        c.otaSupported = info.supportsOta
        c.pairingWindowSupported = info.supportsPhoneWindow
        knownAddresses[c.deviceKey] = c.address!!
        // Nowy bond tworzy wyłącznie pair() albo repairBond() w oknie rejestracji (spec 9.5); connect() bez bondu
        // nie paruje (zapis CCCD wywołałby systemowe parowanie, które sterownik poza oknem i tak odrzuci).
        if (!radio.isBonded(c.address!!) && !c.pairing && !c.repair) {
            endWith(c, LinkEvent.BondMissing)
            return
        }
        // Wersja firmware tylko dla sesji (Ready); odczyt bez szyfrowania, przed bondem (8.2).
        if (c.pairing) secureLink(c) else c.queue?.enqueue(GattOp.ReadFirmwareRevision)
    }

    private suspend fun secureLink(c: Conn) {
        emit(c, LinkEvent.SecuringLink)
        c.queue?.enqueue(if (radio.isBonded(c.address!!)) GattOp.EnableIndications else GattOp.CreateBond)
    }

    /**
     * Rejestracja PIN-em (11.4): kandydat musi obsługiwać PIN (bit 5); `device_id` z INFO jest tylko podpowiedzią,
     * więc adres trafia do [knownAddresses] dopiero po poprawnym PAIR_CHALLENGE.
     */
    private suspend fun onPinCandidateInfo(c: Conn, value: ByteArray?) {
        val info = value?.let(GateInfo::parse)
        if (info == null || !info.supportsPin) {
            candidateFailed(c, foreign = info != null)
            return
        }
        stopScanning(c)
        c.verified = true
        c.deviceId = info.deviceId.copyOf()
        emit(c, LinkEvent.SecuringLink)
        c.queue?.enqueue(if (radio.isBonded(c.address!!)) GattOp.EnableIndications else GattOp.CreateBond)
    }

    /**
     * Kandydat nie jest naszym sterownikiem albo zawiódł przed weryfikacją INFO: następny kandydat, dalszy skan albo
     * — dla zapamiętanego adresu — jednorazowy skan. Po weryfikacji INFO każda porażka kończy próbę (bez pętli
     * ponownych skanów, np. przy nieaktualnym bondzie).
     */
    private suspend fun candidateFailed(c: Conn, foreign: Boolean = false) {
        if (c.verified) return attemptFailed(c)
        c.address?.let { address ->
            // Obce urządzenie: odrzucone do końca próby, a jako zapamiętany adres tej bramy — zapomniane (spec 8.3:
            // adres sterownika jest statyczny, więc chwilowa porażka go nie unieważnia). Chwilowa porażka (status 133,
            // timeout): ponowienie przy kolejnym wyniku skanu, najwyżej MAX_ATTEMPTS_PER_ADDRESS podejść w próbie.
            val attempts = (c.failures[address]?.attempts ?: 0) + 1
            c.failures[address] = AddressFailures(attempts, clock(), foreign)
            if (foreign && knownAddresses[c.deviceKey] == address) knownAddresses.remove(c.deviceKey)
        }
        dropRadio(c)
        val next = c.candidates.removeFirstOrNull()
        when {
            next != null -> connectRadio(c, next)
            c.scanning -> { // czekamy na kolejne wyniki skanu
                c.address = null
                c.stage = Stage.SCANNING
                emit(c, LinkEvent.Scanning)
            }
            c.fromMemory && !c.rescanned && !c.scanExpired -> {
                c.rescanned = true
                c.address = null
                startScan(c)
            }
            else -> attemptFailed(c)
        }
    }

    private fun stopScanning(c: Conn) {
        if (!c.scanning) return
        radio.stopScan()
        c.scanning = false
        cancel(c, TimerKind.SCAN)
    }

    // --- handshake (spec 12.1) ---

    private suspend fun startHandshake(c: Conn, announce: Boolean = true) {
        c.stage = Stage.HANDSHAKE
        if (announce) emit(c, LinkEvent.Authenticating)
        val pin = c.pin
        if (pin != null && c.key.isEmpty()) {
            // Najpierw EC-JPAKE; T_attempt próby PAKE od zakończenia wysłania PAKE_ROUND1 (10.4, 11.4).
            val pake = PinPakeHandshake(pin)
            c.pake = pake
            arm(c, TimerKind.ATTEMPT, c.channel!!.attemptBudgetMs())
            sendRequest(c, pake.pendingRequest!!)
            return
        }
        val hs = if (c.pairing) {
            PairingHandshake(c.key, c.deviceId, c.clientId, nonces)
        } else {
            SessionHandshake(c.key, c.deviceId, c.clientId, nonces)
        }
        c.handshake = hs
        arm(c, TimerKind.ATTEMPT, c.channel!!.attemptBudgetMs())
        sendRequest(c, hs.pendingRequest!!)
    }

    private fun hasPendingRequest(c: Conn): Boolean =
        c.pake?.pendingRequest != null || c.handshake?.pendingRequest != null || c.session?.pendingRequest != null

    private fun sendRequest(c: Conn, bytes: ByteArray) {
        cancel(c, TimerKind.RESPONSE)
        c.retransmissions = 0
        c.channel!!.sendFrame(bytes)
    }

    private suspend fun onFrame(c: Conn, frame: ByteArray) {
        c.pake?.let { return onPakeFrame(c, it, frame) }
        val hs = c.handshake ?: return onSessionFrame(c, frame)
        when (val step = hs.onFrame(frame)) {
            is HandshakeStep.Send -> {
                cancel(c, TimerKind.RESPONSE)
                // Rejestracja PIN-em: poprawny PAIR_CHALLENGE potwierdził `device_id` — dopiero teraz adres jest znany.
                if (c.pin != null) knownAddresses[c.deviceKey] = c.address!!
                sendRequest(c, step.frame)
            }
            is HandshakeStep.Established -> {
                cancel(c, TimerKind.RESPONSE)
                cancel(c, TimerKind.ATTEMPT)
                c.handshake = null
                c.session = step.session
                c.stage = Stage.READY
                c.everReady = true
                c.nextFailureRetryMs = FAILURE_RETRY_MS
                c.nextRejectRetryMs = REJECT_RETRY_MS
                emit(c, LinkEvent.Ready(step.session.latestSnapshot, LinkInfo(c.firmwareVersion, c.otaSupported, c.channel!!.mtu, c.pairingWindowSupported)))
            }
            is HandshakeStep.GrantReceived -> {
                cancel(c, TimerKind.RESPONSE)
                c.grantReceived = true
                emit(c, LinkEvent.GrantReceived(step.clientKey.copyOf(), c.deviceId.copyOf()))
                step.clientKey.fill(0)
            }
            HandshakeStep.Paired -> {
                // PairingDone to ostatnie zdarzenie próby (kontrakt); połączenie parowania kończone.
                c.pairingDone = true
                emit(c, LinkEvent.PairingDone)
                close(c)
            }
            is HandshakeStep.Rejected -> handshakeEnded(c, LinkEvent.HandshakeRejected(step.reason))
            is HandshakeStep.Failed -> handshakeEnded(c, LinkEvent.HandshakeFailed)
            else -> Unit // Duplicate, Ignored
        }
    }

    private suspend fun onPakeFrame(c: Conn, pake: PinPakeHandshake, frame: ByteArray) {
        when (val step = pake.onFrame(frame)) {
            is PakeStep.Send -> {
                cancel(c, TimerKind.RESPONSE)
                sendRequest(c, step.frame)
            }
            is PakeStep.KeyReady -> {
                cancel(c, TimerKind.RESPONSE)
                cancel(c, TimerKind.ATTEMPT)
                c.pake = null
                c.key = step.pinKey // K_pin zastępuje K_setup na tym połączeniu (11.4); zerowany przy zamknięciu
                startHandshake(c, announce = false)
            }
            is PakeStep.Rejected -> handshakeEnded(c, LinkEvent.HandshakeRejected(step.reason))
            is PakeStep.Failed -> handshakeEnded(c, LinkEvent.HandshakeFailed)
            else -> Unit // Duplicate, Ignored
        }
    }

    /**
     * Nieudany lub przeterminowany zapis na łączu, które osiągnęło READY: to opóźniony callback, nie powód do
     * zerwania łącza właściciela (spec 10.4). Transmisja przepada: sesja (komenda → `CommandUnknown`) albo
     * próba handshake jest odtwarzana na tym samym łączu.
     */
    private suspend fun writeLost(c: Conn) {
        // Obie ścieżki (rekey, keptLinkRetry) same porzucają transport.
        if (c.session != null) invalidateAndRekey(c) else handshakeEnded(c, LinkEvent.HandshakeFailed)
    }

    /** Porzuca niewysłane fragmenty przerwanego żądania, aby nie mieszały się z nowym (bez zrywania łącza). */
    private fun resetTransport(c: Conn) {
        c.queue?.clear()
        c.channel?.reset()
        cancel(c, TimerKind.RESPONSE)
    }

    /** Spec 13.4: żądanie w toku ma wynik nieznany (bez ponawiania), nowa sesja na tym samym łączu. */
    private suspend fun invalidateAndRekey(c: Conn) {
        c.session?.invalidate()?.let { emit(c, LinkEvent.CommandUnknown(it)) }
        rekey(c)
    }

    // --- sesja READY (spec 13) ---

    /**
     * `send()` poza READY lub przy rozpoczętym rekeyu: nic nie jest wysyłane, a wynik jest konserwatywnie
     * nieznany (ble_design §4.3) — `:domain` nie może wiedzieć, czy zapis zdążył wyjść.
     */
    private suspend fun onSend(request: SessionRequest) {
        val c = conn ?: return
        val session = c.session
        if (c.stage != Stage.READY || session == null || !session.isValid || session.pendingRequest != null) {
            emit(c, LinkEvent.CommandUnknown(request))
            return
        }
        if (session.needsRekey) {
            emit(c, LinkEvent.CommandUnknown(request))
            rekey(c)
            return
        }
        sendRequest(c, session.send(request))
    }

    private suspend fun onSessionFrame(c: Conn, frame: ByteArray) {
        val session = c.session ?: return
        when (val event = session.onFrame(frame)) {
            is SessionEvent.CommandAcknowledged -> {
                cancel(c, TimerKind.RESPONSE)
                emit(c, LinkEvent.CommandResult(event.request, event.ack, event.snapshotApplied))
            }
            is SessionEvent.Pong -> {
                cancel(c, TimerKind.RESPONSE)
                emit(c, LinkEvent.Pong(event.snapshot, event.snapshotApplied))
            }
            is SessionEvent.ClientListReceived -> {
                cancel(c, TimerKind.RESPONSE)
                emit(c, LinkEvent.ClientList(event.request, event.list))
            }
            is SessionEvent.OtaAcknowledged -> {
                cancel(c, TimerKind.RESPONSE)
                emit(c, LinkEvent.OtaResult(event.request, event.ack))
            }
            is SessionEvent.RuleStatus -> emit(c, LinkEvent.RuleStatus(event.snapshot, event.applied))
            is SessionEvent.Failed -> {
                event.unresolved?.let { emit(c, LinkEvent.CommandUnknown(it)) }
                rekey(c)
            }
            SessionEvent.Duplicate, is SessionEvent.Ignored -> Unit
        }
    }

    /** Nowa sesja świeżym handshake na zachowanym łączu (spec 12.1, 12.3), bez zrywania radia. */
    private suspend fun rekey(c: Conn) {
        c.session?.invalidate()
        c.session = null
        resetTransport(c)
        emit(c, LinkEvent.Rekeying)
        startHandshake(c, announce = false)
    }

    private suspend fun handshakeEnded(c: Conn, failure: LinkEvent) {
        // Po GRANT bez DONE (ble_design §4.4): `Disconnected` — `:domain` przechodzi do recovery 11.3 krok 1.
        val event = if (c.grantReceived && failure == LinkEvent.HandshakeFailed) LinkEvent.Disconnected else failure
        emit(c, event)
        if (c.everReady) {
            keptLinkRetry(c, event)
            return
        }
        close(c)
    }

    /**
     * Łącze właściciela zostaje (spec 10.4, 12.4): kolejna próba handshake po odstępie. Błędy: 2 s → 60 s;
     * odmowy UNAVAILABLE/RATE_LIMITED: 30 s → 5 min; pozostałe odmowy: bez ponowień, decyzja należy do `:domain`.
     */
    private fun keptLinkRetry(c: Conn, event: LinkEvent) {
        c.handshake?.abort()
        c.handshake = null
        resetTransport(c)
        cancel(c, TimerKind.ATTEMPT)
        val delayMs = when {
            event is LinkEvent.HandshakeRejected && event.reason.isRetryable() ->
                c.nextRejectRetryMs.also { c.nextRejectRetryMs = minOf(it * 2, MAX_REJECT_RETRY_MS) }
            event is LinkEvent.HandshakeRejected -> return
            else -> c.nextFailureRetryMs.also { c.nextFailureRetryMs = minOf(it * 2, MAX_FAILURE_RETRY_MS) }
        }
        arm(c, TimerKind.RECOVERY, delayMs)
    }

    // --- timery ---

    private suspend fun onTimer(c: Conn, t: Input.Timer) {
        if (c.timers[t.kind]?.first != t.generation) return
        c.timers.remove(t.kind)
        when (t.kind) {
            TimerKind.SCAN -> if (c.handle != null) {
                // Okno skanu minęło w trakcie weryfikacji kandydata: dokończyć ją, potem bez dalszych kandydatów.
                stopScanning(c)
                c.scanExpired = true
                c.candidates.clear()
            } else {
                attemptFailed(c)
            }
            TimerKind.RADIO_CONNECT -> candidateFailed(c)
            TimerKind.GATT -> {
                // `null`: koniec karencji — następną operację (i jej timer) obsłuży syncGattTimer.
                c.queue?.onTimeout()?.let { onGattCompletion(c, it) }
            }
            TimerKind.RESPONSE -> onResponseTimeout(c)
            TimerKind.ATTEMPT -> handshakeEnded(c, LinkEvent.HandshakeFailed)
            TimerKind.RECOVERY -> rekey(c)
        }
    }

    /**
     * Spec 10.4, 13.4: dwie retransmisje identycznych bajtów (nowy `transfer_id`), trzeci upływ kończy próbę
     * handshake albo unieważnia sesję: `CommandUnknown` bez ponawiania i rekey na tym samym łączu.
     */
    private suspend fun onResponseTimeout(c: Conn) {
        val pending = c.pake?.pendingRequest ?: c.handshake?.pendingRequest ?: c.session?.pendingRequest ?: return
        if (c.retransmissions < MAX_RETRANSMISSIONS) {
            c.retransmissions++
            c.channel!!.sendFrame(pending)
            return
        }
        if (c.session != null) invalidateAndRekey(c) else handshakeEnded(c, LinkEvent.HandshakeFailed)
    }

    private fun arm(c: Conn, kind: TimerKind, ms: Long) {
        cancel(c, kind)
        val generation = ++timerGeneration
        val job = scope.launch {
            delay(ms)
            inbox.send(Input.Timer(c.id, kind, generation))
        }
        c.timers[kind] = generation to job
    }

    private fun cancel(c: Conn, kind: TimerKind) {
        c.timers.remove(kind)?.second?.cancel()
    }

    /**
     * Timer operacji GATT (timeouty i karencja w [GattQueue]) — uzgadniany po każdym wejściu aktora: przezbrajany
     * przy każdej zmianie generacji kolejki, czyli na każdą operację osobno; nowa kolejka (nowy kandydat) zawsze
     * dostaje własny timer. Jedyne miejsce zarządzające timerem GATT — ścieżki zmieniające kolejkę nie muszą o nim pamiętać.
     */
    private fun syncGattTimer(c: Conn) {
        val queue = c.queue
        val ms = queue?.currentTimeoutMs
        if (queue == null || ms == null) {
            cancel(c, TimerKind.GATT)
            c.gattTimerKey = null
            return
        }
        val key = queue to queue.generation
        if (key != c.gattTimerKey) {
            arm(c, TimerKind.GATT, ms)
            c.gattTimerKey = key
        }
    }

    // --- zakończenie ---

    /** Nieudana próba przed handshake (spec 8.1): `Disconnected`, połączenie zakończone. */
    private suspend fun attemptFailed(c: Conn) = endWith(c, LinkEvent.Disconnected)

    private suspend fun endWith(c: Conn, event: LinkEvent) {
        emit(c, event)
        close(c)
    }

    private suspend fun onRadioLost(c: Conn) {
        if (c.stage == Stage.CONNECTING || c.stage == Stage.SETUP) {
            candidateFailed(c)
            return
        }
        // Utrata radia (spec 13.4): żądanie w toku ma wynik nieznany.
        c.session?.invalidate()?.let { emit(c, LinkEvent.CommandUnknown(it)) }
        emit(c, LinkEvent.Disconnected)
        close(c)
    }

    private fun dropRadio(c: Conn) {
        c.handle?.let(radio::disconnect)
        c.handle = null
        c.queue?.clear()
        c.queue = null
        c.channel = null
        cancel(c, TimerKind.RADIO_CONNECT) // timer GATT uzgadnia syncGattTimer (brak kolejki → wyłączony)
        cancel(c, TimerKind.RESPONSE)
    }

    /** Kończy połączenie bez emitowania zdarzeń (kontrakt `disconnect()`); zeruje klucze. */
    private fun close(c: Conn?) {
        c ?: return
        // Rejestracja przerwana albo nieudana przed PAIR_COMMIT (zły PIN, odmowa, timeout, anulowanie): bond systemowy
        // nie ma wpisu w sterowniku i zepsułby kolejne parowanie, więc go usuwamy. Po wysłaniu COMMIT wynik jest nieznany
        // (sterownik mógł zatwierdzić), dlatego bond zostaje, a kolejną próbę prowadzi recovery z `:domain`.
        if (c.pairing && !c.pairingDone && !c.commitSent) c.address?.let(radio::removeBond)
        if (c.scanning) {
            radio.stopScan()
            c.scanning = false
        }
        dropRadio(c)
        c.pake?.abort()
        c.pake = null
        c.pin?.wipe()
        c.handshake?.abort()
        c.handshake = null
        c.session?.invalidate()
        c.session = null
        c.timers.values.forEach { it.second.cancel() }
        c.timers.clear()
        c.key.fill(0)
        if (conn === c) conn = null
    }

    private suspend fun emit(c: Conn, event: LinkEvent) {
        if (conn === c) out.emit(LinkMessage(c.id, event))
    }

    private companion object {
        const val FAILURE_RETRY_MS = 2_000L
        const val MAX_FAILURE_RETRY_MS = 60_000L
        const val REJECT_RETRY_MS = 30_000L
        const val MAX_REJECT_RETRY_MS = 300_000L
        const val SCAN_TIMEOUT_MS = 10_000L
        const val RADIO_CONNECT_TIMEOUT_MS = 10_000L
        const val CANDIDATE_CONNECT_TIMEOUT_MS = 5_000L
        const val MAX_ATTEMPTS_PER_ADDRESS = 2
        const val RETRY_PAUSE_MS = 1_000L
        const val MAX_RETRANSMISSIONS = 2
    }
}

/** Spec 12.4: tylko UNAVAILABLE i RATE_LIMITED są ponawiane automatycznie. */
private fun HandshakeRejectReason.isRetryable() =
    this == HandshakeRejectReason.UNAVAILABLE || this == HandshakeRejectReason.RATE_LIMITED
