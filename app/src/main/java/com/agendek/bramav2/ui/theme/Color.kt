package com.agendek.bramav2.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta marki: turkus i granat, zieleń dla OTWÓRZ, bursztyn dla stanów przejściowych (propozycja wyglądu 17.11.2026).
val Teal600 = Color(0xFF0E7C86)
val Teal200 = Color(0xFF6FD3DB)
val Navy900 = Color(0xFF12232E)
val Navy700 = Color(0xFF27475C)
val Navy200 = Color(0xFFB4CAD8)
val Emerald600 = Color(0xFF1F9D55)
val Emerald400 = Color(0xFF48C27B)
val Amber500 = Color(0xFFF2A413)
val Slate500 = Color(0xFF8493A0)
val Coral600 = Color(0xFFD64545)

val SurfaceLight = Color(0xFFF3F7F9)
val SurfaceDark = Color(0xFF0D1A22)

/** Kolory semantyczne spoza schematu Material 3 (stan połączenia, przyciski sterowania). */
object BramaColors {
    val open = Emerald600
    val openDark = Emerald400
    val close = Navy700
    val closeDark = Color(0xFF3E7CA3)
    val connected = Emerald600
    val connecting = Amber500
    val offline = Slate500
}
