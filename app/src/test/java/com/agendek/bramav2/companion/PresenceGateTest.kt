package com.agendek.bramav2.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceGateTest {

    private class Sink : PresenceSink {
        val events = mutableListOf<String>()
        override fun appeared() { events += "appeared" }
        override fun disappeared() { events += "disappeared" }
    }

    private val sink = Sink()
    private val gate = PresenceGate(sink)

    @Test
    fun appearedForwardedWhenAllowed() {
        gate.onAppeared(settingEnabled = true, notificationsAllowed = true)
        gate.onDisappeared()
        assertEquals(listOf("appeared", "disappeared"), sink.events)
    }

    @Test
    fun appearedSuppressedWhenSettingOff() {
        gate.onAppeared(settingEnabled = false, notificationsAllowed = true)
        gate.onDisappeared()
        assertEquals(emptyList<String>(), sink.events)
    }

    @Test
    fun appearedSuppressedWithoutNotificationPermission() {
        gate.onAppeared(settingEnabled = true, notificationsAllowed = false)
        assertEquals(emptyList<String>(), sink.events)
    }

    @Test
    fun revokedNotificationPermissionHidesPresence() {
        gate.onAppeared(settingEnabled = true, notificationsAllowed = true)
        gate.onConditionsChanged(settingEnabled = true, notificationsAllowed = false)
        assertEquals(listOf("appeared", "disappeared"), sink.events)
        gate.onDisappeared() // już zgłoszone
        assertEquals(listOf("appeared", "disappeared"), sink.events)
    }

    @Test
    fun regainedPermissionWhilePresentShowsAgain() {
        gate.onAppeared(settingEnabled = true, notificationsAllowed = false)
        gate.onConditionsChanged(settingEnabled = true, notificationsAllowed = true)
        assertEquals(listOf("appeared"), sink.events)
    }

    @Test
    fun conditionsWithoutPresenceDoNothing() {
        gate.onConditionsChanged(settingEnabled = true, notificationsAllowed = true)
        assertEquals(emptyList<String>(), sink.events)
    }

    @Test
    fun appearedWhileUiVisibleIsDismissedAfterAppeared() {
        val events = mutableListOf<String>()
        val sink = DismissWhenUiVisibleSink(
            delegate = object : PresenceSink {
                override fun appeared() { events += "appeared" }
                override fun disappeared() { events += "disappeared" }
            },
            uiVisible = { true },
            dismiss = { events += "dismiss" },
        )
        sink.appeared()
        sink.disappeared()
        assertEquals(listOf("appeared", "dismiss", "disappeared"), events)
    }

    @Test
    fun appearedInBackgroundIsNotDismissed() {
        val events = mutableListOf<String>()
        DismissWhenUiVisibleSink(
            delegate = object : PresenceSink {
                override fun appeared() { events += "appeared" }
                override fun disappeared() {}
            },
            uiVisible = { false },
            dismiss = { events += "dismiss" },
        ).appeared()
        assertEquals(listOf("appeared"), events)
    }
}
