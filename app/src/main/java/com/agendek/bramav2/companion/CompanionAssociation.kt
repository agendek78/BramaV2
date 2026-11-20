package com.agendek.bramav2.companion

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.ParcelUuid
import com.agendek.bramav2.ble.android.AndroidBleRadio
import com.agendek.bramav2.ble.core.BleGateLink
import com.agendek.bramav2.ble.core.GattProfile
import com.agendek.bramav2.domain.CredentialLoad
import com.agendek.bramav2.domain.CredentialStore

/**
 * Powiązanie sterownika jako urządzenia towarzyszącego (spec 4.3, 7; app_design §5): filtr po UUID usługi
 * **i dokładnym adresie** zapamiętanym przez `:ble` po uwierzytelnieniu (UUID jest wspólny dla wszystkich sterowników).
 * Po powiązaniu: zasilenie `:ble` adresem i `BluetoothDevice` (typ adresu) oraz obserwacja obecności.
 */
class CompanionAssociation(
    private val context: Context,
    private val credentials: CredentialStore,
    private val link: BleGateLink,
    private val radio: AndroidBleRadio,
) {
    private val cdm get() = context.getSystemService(CompanionDeviceManager::class.java)

    /** Powiązanie dotyczy bieżącej bramy: adres z powiązania = adres zweryfikowany przez `:ble`. */
    suspend fun isAssociatedWithGate(): Boolean {
        val deviceId = (credentials.load() as? CredentialLoad.Present)?.credential?.deviceId ?: return false
        val address = link.knownAddress(deviceId) ?: return false
        return associations().any { it.address() == address.uppercase() }
    }

    /** `false`: brak adresu (np. łącze jeszcze nie uwierzytelnione) — powiązanie niemożliwe. */
    suspend fun associate(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
        val deviceId = (credentials.load() as? CredentialLoad.Present)?.credential?.deviceId ?: return false
        val address = link.knownAddress(deviceId) ?: return false
        // Jedno powiązanie = bieżąca brama: `restore()` i obecność nie mogą trafić do starego sterownika.
        // Stare powiązania znikają dopiero po utworzeniu nowego (onAssociationCreated): anulowanie okna systemowego albo błąd
        // nie mogą zostawić użytkownika bez powiązania. Bond bieżącej bramy zostaje (wymiana powiązania CDM, nie usunięcie bramy).
        val previous = associations().map { it.id }
        // Adres zawsze z wyniku skanu po uwierzytelnieniu. Publiczne API nie pozwala podać typu adresu (wariant
        // z typem jest @SystemApi), a filtr zakłada adres publiczny — dopasowanie static random sprawdzić w etapie 0.
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattProfile.SERVICE)).setDeviceAddress(address).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        return runCatching { requestAssociation(activity, request, deviceId, previous, onResult) }.isSuccess
    }

    private fun requestAssociation(
        activity: Activity,
        request: AssociationRequest,
        deviceId: ByteArray,
        previous: List<Int>,
        onResult: (Boolean) -> Unit,
    ) {
        cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                runCatching { activity.startIntentSender(intentSender, null, 0, 0, 0) }.onFailure { onResult(false) }
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                previous.filter { it != associationInfo.id }.forEach { runCatching { cdm.disassociate(it) } }
                onAssociated(deviceId, associationInfo)
                onResult(true)
            }

            override fun onFailure(error: CharSequence?) = onResult(false)
        })
    }

    /**
     * Start aplikacji: istniejące powiązanie zasila `:ble` (łączenie bez skanu po restarcie procesu). Bez poświadczenia
     * (proces zginął przed `disassociate`, uszkodzony plik) powiązanie jest nieaktualne i zostaje usunięte.
     */
    suspend fun restore() {
        when (val load = credentials.load()) {
            CredentialLoad.None -> disassociateAll()
            is CredentialLoad.Present -> associations().firstOrNull()?.let { feed(load.credential.deviceId, it) }
            CredentialLoad.Locked -> Unit // poświadczenie istnieje, tylko jest jeszcze niedostępne
        }
    }

    /** „Usuń z telefonu”: powiązanie dotyczyło usuniętej konfiguracji. */
    fun disassociateAll() {
        // Bond systemowy z bramą też znika: zostawiony zepsułby kolejną rejestrację tej samej bramy (sterownik zna go po IRK
        // i wysyła Security Request, a nowa rejestracja nie ma już jego klucza).
        val addresses = (associations().mapNotNull { it.address() } + link.knownAddresses()).toSet()
        addresses.forEach { runCatching { radio.removeBond(it) } }
        associations().forEach { runCatching { cdm.disassociate(it.id) } }
    }

    private fun associations(): List<AssociationInfo> = runCatching { cdm.myAssociations }.getOrDefault(emptyList())

    private fun AssociationInfo.address(): String? = deviceMacAddress?.toString()?.uppercase()

    private fun onAssociated(deviceId: ByteArray, info: AssociationInfo) {
        feed(deviceId, info)
        val address = info.address() ?: return
        // Wariant z ObservingDevicePresenceRequest istnieje dopiero od API 36; minSdk = 33 wymaga tego.
        @Suppress("DEPRECATION")
        runCatching { cdm.startObservingDevicePresence(address) }
    }

    private fun feed(deviceId: ByteArray, info: AssociationInfo) {
        val address = info.address() ?: return
        // Typ adresu static random (spec 8.3); `getRemoteLeDevice` jest od API 33, w przeciwieństwie do
        // `AssociationInfo.associatedDevice` (API 34).
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.let { runCatching { it.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM) }.getOrNull() }
            ?.let(radio::registerDevice)
        link.rememberAddress(deviceId, address)
    }
}
