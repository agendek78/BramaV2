package com.agendek.bramav2.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import com.agendek.bramav2.graph

/** Obecność sterownika (CompanionDeviceManager) → [PresenceGate] → domena (spec 4.3 pkt 1, 5, 6). Callbacki na wątku głównym. */
class GateCompanionService : CompanionDeviceService() {

    // Zastępujące onDevicePresenceEvent istnieje dopiero od API 36; minSdk = 33 wymaga tych callbacków.

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        applicationContext.graph.onPresenceAppeared()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        applicationContext.graph.presenceGate.onDisappeared()
    }
}
