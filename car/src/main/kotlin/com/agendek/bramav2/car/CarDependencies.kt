package com.agendek.bramav2.car

import com.agendek.bramav2.domain.GateRepository

/** Zależności `:car` dostarczane przez aplikację (`Application` implementuje ten interfejs). */
interface CarDependencies {
    val repository: GateRepository
    val carNotifier: CarPresenceNotifier
}
