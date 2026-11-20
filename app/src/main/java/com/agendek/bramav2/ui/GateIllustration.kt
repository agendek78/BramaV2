package com.agendek.bramav2.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.ui.theme.BramaColors

/** Ton stanu połączenia dla kolorów ekranu głównego. */
enum class ConnectionTone { CONNECTED, CONNECTING, OFFLINE }

@Composable
fun ConnectionTone.color() = when (this) {
    ConnectionTone.CONNECTED -> BramaColors.connected
    ConnectionTone.CONNECTING -> BramaColors.connecting
    ConnectionTone.OFFLINE -> BramaColors.offline
}

/**
 * Grafika bramy rysowana w Compose (bez zasobów): dwa słupki z lampkami i skrzydło z poprzeczkami na okrągłym tle w kolorze stanu;
 * przy łączeniu tło pulsuje.
 */
@Composable
fun GateIllustration(tone: ConnectionTone, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 176.dp) {
    val status = tone.color()
    val post = MaterialTheme.colorScheme.secondary
    val leaf = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulseValue",
    )
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2, w / 2)
        drawCircle(status.copy(alpha = 0.14f), radius = w / 2, center = c)
        if (tone == ConnectionTone.CONNECTING) {
            drawCircle(
                status.copy(alpha = 0.45f * (1f - pulse)),
                radius = w / 2 * (0.72f + 0.28f * pulse),
                center = c,
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        val bottom = w * 0.74f
        val rail = w * 0.026f
        // Murki po bokach.
        val wall = post.copy(alpha = 0.45f)
        drawRoundRect(wall, Offset(w * 0.13f, w * 0.52f), Size(w * 0.15f, bottom - w * 0.52f), CornerRadius(w * 0.01f))
        drawRoundRect(wall, Offset(w * 0.72f, w * 0.52f), Size(w * 0.15f, bottom - w * 0.52f), CornerRadius(w * 0.01f))
        // Słupki z płaskimi daszkami.
        val postX = listOf(w * 0.27f, w * 0.67f)
        postX.forEach { x ->
            drawRoundRect(post, Offset(x, w * 0.30f), Size(w * 0.06f, bottom - w * 0.30f), CornerRadius(w * 0.008f))
            drawRoundRect(post, Offset(x - w * 0.012f, w * 0.275f), Size(w * 0.084f, w * 0.03f), CornerRadius(w * 0.008f))
            // Lampka stanu na daszku słupka (zamiast kuli: bez skojarzenia z łóżeczkiem).
            drawCircle(status, radius = w * 0.016f, center = Offset(x + w * 0.03f, w * 0.275f - w * 0.022f))
        }
        // Dwa skrzydła z ramą, szczeblami i ukośnym zastrzałem (zbiegającym się ku środkowi).
        val topLeaf = w * 0.42f
        val leaves = listOf(w * 0.335f to w * 0.495f, w * 0.505f to w * 0.665f)
        leaves.forEachIndexed { i, (l, r) ->
            drawRoundRect(leaf, Offset(l, topLeaf), Size(r - l, bottom - w * 0.04f - topLeaf), CornerRadius(w * 0.01f), style = Stroke(width = rail))
            val inner = (r - l) / 4
            for (k in 1..3) {
                drawLine(leaf.copy(alpha = 0.7f), Offset(l + inner * k, topLeaf), Offset(l + inner * k, bottom - w * 0.04f), strokeWidth = rail * 0.55f)
            }
            val from = if (i == 0) Offset(l, bottom - w * 0.04f) else Offset(r, bottom - w * 0.04f)
            val to = if (i == 0) Offset(r, topLeaf) else Offset(l, topLeaf)
            drawLine(leaf, from, to, strokeWidth = rail * 0.8f)
        }
        // Podłoże.
        drawRoundRect(post.copy(alpha = 0.35f), Offset(w * 0.1f, bottom), Size(w * 0.8f, w * 0.022f), CornerRadius(w * 0.011f))
    }
}
