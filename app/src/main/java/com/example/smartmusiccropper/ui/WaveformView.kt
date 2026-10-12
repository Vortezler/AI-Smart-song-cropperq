package com.example.smartmusiccropper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.smartmusiccropper.ml.ClassifiedWindow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

object Waveform {
    /** Dynamic range shown, so quiet fade-outs and hiss are still visible. */
    private const val FLOOR_DB = -54f

    /** Peak-per-bucket envelope of [samples], scaled to 0..1 on a dB axis. */
    fun envelope(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(buckets)
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val from = (samples.size.toLong() * b / buckets).toInt()
            val to = max(from + 1, (samples.size.toLong() * (b + 1) / buckets).toInt()).coerceAtMost(samples.size)
            var peak = 0f
            for (i in from until to) peak = max(peak, abs(samples[i]))
            val db = if (peak <= 0f) FLOOR_DB else 20f * log10(peak)
            out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        return out
    }
}

/**
 * Waveform of the track's tail with a draggable crop line. Touching anywhere moves the line there;
 * dragging follows the finger. The part after the line (what gets trimmed) is dimmed, and a thin
 * strip underneath shows which windows the model heard as music.
 */
@Composable
fun WaveformView(
    envelope: FloatArray,
    windows: List<ClassifiedWindow>,
    startMs: Long,
    endMs: Long,
    cropMs: Long,
    suggestedMs: Long,
    enabled: Boolean,
    onCropChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val currentOnCropChange by rememberUpdatedState(onCropChange)
    val span = (endMs - startMs).coerceAtLeast(1L)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = "Waveform of the track ending with crop line" }
            .pointerInput(enabled, startMs, endMs) {
                if (!enabled) return@pointerInput
                fun toMs(x: Float) = startMs + ((x / size.width).coerceIn(0f, 1f) * span).toLong()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnCropChange(toMs(down.position.x))
                    drag(down.id) { change ->
                        change.consume()
                        currentOnCropChange(toMs(change.position.x))
                    }
                }
            },
    ) {
        val stripHeight = 6.dp.toPx()
        val stripGap = 6.dp.toPx()
        val waveHeight = size.height - stripHeight - stripGap
        val mid = waveHeight / 2
        fun xOf(ms: Long) = (ms - startMs).toFloat() / span * size.width
        val cropX = xOf(cropMs)

        drawRoundRect(
            color = colors.surfaceVariant,
            size = Size(size.width, waveHeight),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )

        // Bars
        if (envelope.isNotEmpty()) {
            val barStep = size.width / envelope.size
            val barWidth = max(1f, barStep * 0.7f)
            envelope.forEachIndexed { i, v ->
                val x = i * barStep + barStep / 2
                val h = max(1.dp.toPx(), v * (waveHeight - 16.dp.toPx()) / 2)
                drawLine(
                    color = if (x <= cropX) colors.primary else colors.outline.copy(alpha = 0.6f),
                    start = Offset(x, mid - h),
                    end = Offset(x, mid + h),
                    strokeWidth = barWidth,
                )
            }
        }

        // Dim the region that will be trimmed
        if (cropX < size.width) {
            drawRect(
                color = colors.scrim.copy(alpha = 0.12f),
                topLeft = Offset(cropX, 0f),
                size = Size(size.width - cropX, waveHeight),
            )
        }

        // Model's music / non-music strip
        val stripTop = waveHeight + stripGap
        windows.forEach { w ->
            val x0 = xOf(w.startMs).coerceIn(0f, size.width)
            val x1 = xOf(w.endMs).coerceIn(0f, size.width)
            drawRect(
                color = if (w.isMusic) colors.tertiary else colors.outlineVariant,
                topLeft = Offset(x0, stripTop),
                size = Size(max(1f, x1 - x0), stripHeight),
            )
        }

        // Suggested point (dashed) when the user has moved away from it
        if (suggestedMs != cropMs) {
            val sx = xOf(suggestedMs)
            drawLine(
                color = colors.tertiary,
                start = Offset(sx, 0f),
                end = Offset(sx, waveHeight),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
        }

        // Crop line + handle
        val lineX = cropX.coerceIn(1.5.dp.toPx(), size.width - 1.5.dp.toPx())
        drawLine(
            color = colors.error,
            start = Offset(lineX, 0f),
            end = Offset(lineX, waveHeight),
            strokeWidth = 3.dp.toPx(),
        )
        drawCircle(color = colors.error, radius = 8.dp.toPx(), center = Offset(lineX, 10.dp.toPx()))
    }
}
