package com.agendek.bramav2.companion

interface PresenceSink {
    fun appeared()
    fun disappeared()
}

/**
 * Zdarzenia obecności z CompanionDeviceManager → domena tylko przy włączonym ustawieniu i zgodzie na powiadomienia
 * (app_design §5): bez widocznego powiadomienia nie ma cichych połączeń w tle. Utrata warunku przy trwającej
 * obecności → `disappeared()`, odzyskanie → `appeared()`. Wołane z wątku głównego.
 */
class PresenceGate(private val sink: PresenceSink) {
    private var present = false
    private var forwarded = false
    private var allowed = false

    fun onAppeared(settingEnabled: Boolean, notificationsAllowed: Boolean) {
        present = true
        update(settingEnabled, notificationsAllowed)
    }

    fun onDisappeared() {
        present = false
        if (forwarded) {
            forwarded = false
            sink.disappeared()
        }
    }

    fun onConditionsChanged(settingEnabled: Boolean, notificationsAllowed: Boolean) = update(settingEnabled, notificationsAllowed)

    private fun update(settingEnabled: Boolean, notificationsAllowed: Boolean) {
        allowed = settingEnabled && notificationsAllowed
        val shouldForward = present && allowed
        if (shouldForward && !forwarded) {
            forwarded = true
            sink.appeared()
        } else if (!shouldForward && forwarded) {
            forwarded = false
            sink.disappeared()
        }
    }
}

/**
 * Brama pojawiła się przy aplikacji na pierwszym planie: bez powiadomienia, więc od razu odrzucenie (spec 4.3 pkt 4).
 * Kolejność zachowana: najpierw `appeared()`, potem `dismiss()`.
 */
class DismissWhenUiVisibleSink(
    private val delegate: PresenceSink,
    private val uiVisible: () -> Boolean,
    private val dismiss: () -> Unit,
) : PresenceSink {
    override fun appeared() {
        delegate.appeared()
        if (uiVisible()) dismiss()
    }

    override fun disappeared() = delegate.disappeared()
}
