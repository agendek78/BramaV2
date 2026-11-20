package com.agendek.bramav2.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.UiSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Ekran samochodowy (spec 5.2): `PaneTemplate` z grafiką bramy, stanem połączenia i OTWÓRZ/ZAMKNIJ (CLOSE bez potwierdzenia, decyzja 1.4).
 * `onGetTemplate()` tylko czyta ostatni model; odświeżanie przez `invalidate()` po zmianie stanu.
 */
class GateScreen(carContext: CarContext, private val repository: GateRepository) : Screen(carContext) {

    private var pane: CarPane = carPane(repository.state.value)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = repository.uiAttached(UiSource.CAR)
            override fun onStop(owner: LifecycleOwner) = repository.uiDetached(UiSource.CAR)
        })
        lifecycleScope.launch {
            repository.state.map(::carPane).distinctUntilChanged().collect {
                pane = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val p = pane
        val builder = Pane.Builder()
        builder.setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.gate_illustration)).build())
        builder.addRow(Row.Builder().setTitle(statusText(p)).build())
        if (p.showActions) {
            // OTWÓRZ wyróżniony zielenią jak na telefonie; ZAMKNIJ zostaje w domyślnym kolorze hosta (kolor tła
            // dozwolony tylko dla akcji z FLAG_PRIMARY, a host dopuszcza jedną).
            builder.addAction(action(OPEN, p.canOpen, primary = true) { repository.open() })
            builder.addAction(action(CLOSE, p.canClose) { repository.requestClose(UiSource.CAR) })
        }
        // setTitle/setHeaderAction: Header wymaga Car App API 7; minCarApiLevel = 1.
        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(builder.build()).setTitle(p.title).setHeaderAction(Action.APP_ICON).build()
    }

    /**
     * Kropka w kolorze stanu i tekst stanu połączenia (jak „pigułka” na telefonie). Tytuł wiersza nie dopuszcza
     * `ForegroundCarColorSpan` (host: „CarSpan type is not allowed”), więc kolor niesie emoji.
     */
    private fun statusText(p: CarPane): CarText {
        if (!p.showActions) return CarText.create(p.status)
        val dot = when (p.tone) {
            CarTone.CONNECTED -> "\uD83D\uDFE2" // 🟢
            CarTone.CONNECTING -> "\uD83D\uDFE1" // 🟡
            CarTone.OFFLINE -> "\u26AA" // ⚪
        }
        return CarText.create("$dot ${p.status}")
    }

    private fun action(title: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit): Action {
        val b = Action.Builder().setTitle(title).setOnClickListener(onClick)
        if (primary) b.setBackgroundColor(CarColor.GREEN).setFlags(Action.FLAG_PRIMARY)
        // Wyłączanie akcji od Car App API 5; na starszych hostach domena i tak odrzuca komendę bez sesji READY.
        if (carContext.carAppApiLevel >= 5) b.setEnabled(enabled)
        return b.build()
    }

    private companion object {
        const val OPEN = "OTWÓRZ"
        const val CLOSE = "ZAMKNIJ"
    }
}
