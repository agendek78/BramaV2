package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.OtaAck
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.flow.Flow

/**
 * Port BLE (implementuje `:ble`). `:domain` zleca, `:ble` wykonuje mechanikę (skan, GATT, MTU, fragmentacja,
 * retransmisje i timeouty 10.4, odtworzenie sesji 12.1) i raportuje [LinkEvent].
 *
 * Każde połączenie ([connect], [pair]) dostaje nowy, niepowtarzalny w procesie identyfikator; każde zdarzenie
 * niesie identyfikator połączenia, którego dotyczy. `:domain` odrzuca zdarzenia innych połączeń niż bieżące,
 * także te już zakolejkowane w chwili zakończenia starego połączenia.
 */
interface GateLink {
    val events: Flow<LinkMessage>

    /**
     * Sesja zwykła z `K_client`; zwraca identyfikator połączenia. Implementacja kopiuje klucz; wywołujący
     * zeruje swoją tablicę po powrocie.
     */
    fun connect(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long

    /**
     * Naprawa bondu przy zachowanym `K_client` (spec 9.5): jak [connect], ale przy braku bondu tworzy go parowaniem
     * systemowym (sterownik przyjmie je tylko w otwartym oknie rejestracji). Nieudane parowanie: [LinkEvent.BondFailed].
     */
    fun repairBond(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray): Long

    /**
     * Próba PAIR z QR (`K_setup`, 11.3) albo PIN-em (EC-JPAKE, 11.4); zwraca identyfikator połączenia. `:domain`
     * zeruje sekret po zakończeniu próby — implementacja kopiuje go, jeśli używa go asynchronicznie. Przy PIN-ie
     * `device_id` nie jest znany z góry: podaje go [LinkEvent.GrantReceived].
     */
    fun pair(secret: PairingSecret, clientId: ByteArray): Long

    fun commitPairing()

    /**
     * Przerywa próbę PAIR. Kontrakt jak [disconnect]: kończy połączenie parowania i po powrocie implementacja
     * nie emituje już zdarzeń tej próby (także spóźnionych `Disconnected`/`SessionLost`).
     */
    fun abortPairing()

    /** Najwyżej jedno oczekujące żądanie — pilnuje `:domain`. */
    fun send(request: SessionRequest)

    /**
     * Kończy bieżące połączenie. Kontrakt: po powrocie implementacja nie emituje już żadnych zdarzeń tego
     * połączenia (także spóźnionych `Disconnected`/`SessionLost`); następne zdarzenia dotyczą wyłącznie
     * kolejnego [connect]/[pair]. `:domain` nie zna identyfikatorów połączeń — filtrowanie należy do `:ble`.
     */
    fun disconnect()
}

/** Zdarzenie [event] połączenia o identyfikatorze [connectionId] (zwróconym przez `connect`/`pair`). */
data class LinkMessage(val connectionId: Long, val event: LinkEvent)

/**
 * Dane bieżącego łącza (spec 8.2, 15.5): wersja firmware z Device Information (`null`, gdy odczyt się nie udał),
 * bit 6 `capabilities` z INFO, wynegocjowane ATT_MTU i bit 7 (okno rejestracji z telefonu, phone_window_design).
 */
data class LinkInfo(
    val firmwareVersion: String?,
    val otaSupported: Boolean,
    val mtu: Int,
    val pairingWindowSupported: Boolean = false,
) {
    companion object {
        val UNKNOWN = LinkInfo(firmwareVersion = null, otaSupported = false, mtu = 23)
    }
}

sealed interface LinkEvent {
    data object Scanning : LinkEvent
    data object Connecting : LinkEvent
    data object SecuringLink : LinkEvent
    data object Authenticating : LinkEvent
    data object Rekeying : LinkEvent
    data class Ready(val snapshot: RuleSnapshot, val info: LinkInfo = LinkInfo.UNKNOWN) : LinkEvent
    data class RuleStatus(val snapshot: RuleSnapshot, val applied: Boolean) : LinkEvent
    data class Pong(val snapshot: RuleSnapshot, val applied: Boolean) : LinkEvent
    data class CommandResult(val request: SessionRequest, val ack: CommandAck, val snapshotApplied: Boolean) : LinkEvent

    /** CLIENT_LIST dla wysłanego `ListClients` (client_management_design.md); [request] to ten sam obiekt, który przekazano do `send`. */
    data class ClientList(val request: SessionRequest, val list: com.agendek.bramav2.protocol.ClientList) : LinkEvent

    /** OTA_ACK dla wysłanego żądania OTA (15.5); [request] to ten sam obiekt, który przekazano do `send`. */
    data class OtaResult(val request: SessionRequest, val ack: OtaAck) : LinkEvent

    /** Sesja unieważniona z nierozstrzygniętym żądaniem (spec 13.4): wynik nieznany, bez ponawiania. */
    data class CommandUnknown(val request: SessionRequest) : LinkEvent
    data object SessionLost : LinkEvent
    data class HandshakeRejected(val reason: HandshakeRejectReason) : LinkEvent
    data object HandshakeFailed : LinkEvent
    /** `K_client` do zapisu jako UNCONFIRMED i `device_id` sterownika potwierdzony przez PAIR_CHALLENGE. */
    class GrantReceived(val clientKey: ByteArray, val deviceId: ByteArray) : LinkEvent
    /** Ostatnie zdarzenie próby PAIR: `:ble` nie emituje już zdarzeń połączenia parowania (jak po `abortPairing`). */
    data object PairingDone : LinkEvent
    data object Disconnected : LinkEvent

    /** `connect` bez bondu (np. po „Zapomnij urządzenie”): połączenie zakończone, potrzebna naprawa (9.5). */
    data object BondMissing : LinkEvent

    /** Parowanie systemowe w `repairBond` nie powiodło się: połączenie zakończone. */
    data object BondFailed : LinkEvent
}

class StoredCredential(val state: CredentialState, val deviceId: ByteArray, val clientId: ByteArray)

sealed interface CredentialLoad {
    data object None : CredentialLoad

    /** Poświadczenie niedostępne do pierwszego odblokowania telefonu (spec 9.3). */
    data object Locked : CredentialLoad
    data class Present(val credential: StoredCredential) : CredentialLoad
}

/** Poświadczenie w Keystore (implementuje `:app`). */
interface CredentialStore {
    suspend fun load(): CredentialLoad

    /** Kopia `K_client`; wywołujący zeruje ją po użyciu. */
    suspend fun clientKey(): ByteArray

    /** Atomowo zastępuje wcześniejsze UNCONFIRMED. Rzuca wyjątek przy nieudanym zapisie. */
    suspend fun saveUnconfirmed(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray)

    suspend fun markActive()

    suspend fun delete()
}

interface SettingsStore {
    val autoCloseDefault: Flow<Boolean>
    val gateName: Flow<String?>

    /** Powiadomienie „brama w zasięgu” włączone w ustawieniach (spec 4.3 pkt 7; domyślnie `true`). */
    val presenceNotificationEnabled: Flow<Boolean>

    suspend fun setAutoCloseDefault(enabled: Boolean)

    suspend fun setGateName(name: String?)

    suspend fun setPresenceNotificationEnabled(enabled: Boolean)
}

class HistoryEntry(val timeMillis: Long, val command: GateCommand, val outcome: CommandOutcome, val ackResult: AckResult?)

interface HistoryStore {
    /** Wpisy historii, najnowsze pierwsze (ekran historii, spec 4.1). */
    val entries: Flow<List<HistoryEntry>>

    suspend fun append(entry: HistoryEntry)

    suspend fun trim(keepLast: Int)
}

/**
 * Ostatni świeży snapshot — przetrwa śmierć procesu (domain_design §4.3). Zapisywany przy zmianie fazy,
 * właściciela lub przyczyny: po restarcie znaczenie mają tylko te pola (własna uzbrojona reguła, powiadomienie
 * o restarcie sterownika). `revision`, `remaining_ms` i [receivedAtMillis] nie służą do oceny wieku stanu.
 */
class LastKnownRule(val snapshot: RuleSnapshot, val receivedAtMillis: Long)

/**
 * Stan powiadomienia „brama w zasięgu” do przetrwania śmierci procesu (spec 4.3 pkt 5): wyciszenie
 * ([quiet]) i wstrzymanie własnej uzbrojonej reguły jako powodu połączenia ([ruleSuppressed]).
 */
data class PresenceRecord(val quiet: Boolean, val ruleSuppressed: Boolean)

/** Magazyn [PresenceRecord] (implementuje `:app`). */
interface PresenceStore {
    suspend fun load(): PresenceRecord

    suspend fun save(record: PresenceRecord)
}

interface RuleCacheStore {
    suspend fun load(): LastKnownRule?

    suspend fun save(rule: LastKnownRule)

    suspend fun clear()
}

/** Uruchamia usługę połączenia w tle (`:app`); `false`, gdy system na to nie pozwala (spec 7). */
fun interface ConnectionKeeper {
    fun ensureBackgroundConnection(): Boolean
}

fun interface WallClock {
    fun nowMillis(): Long
}
