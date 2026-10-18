package ye.mishwari.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Draws the four navigation symbols without adding an icon-library dependency. */
@Composable
internal fun NavigationIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.8f) {
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = width * sx, cap = StrokeCap.Round)
        }
        fun circle(x: Float, y: Float, radius: Float, width: Float = 1.8f) {
            drawCircle(color, radius * sx, p(x, y), style = Stroke(width * sx))
        }

        when (index) {
            // A tidy checklist: the primary activity is managing requests and errands.
            0 -> {
                val board = Path().apply {
                    moveTo(7f * sx, 4f * sy)
                    lineTo(17f * sx, 4f * sy)
                    quadraticTo(19f * sx, 4f * sy, 19f * sx, 6f * sy)
                    lineTo(19f * sx, 19f * sy)
                    quadraticTo(19f * sx, 21f * sy, 17f * sx, 21f * sy)
                    lineTo(7f * sx, 21f * sy)
                    quadraticTo(5f * sx, 21f * sy, 5f * sx, 19f * sy)
                    lineTo(5f * sx, 6f * sy)
                    quadraticTo(5f * sx, 4f * sy, 7f * sx, 4f * sy)
                    close()
                }
                drawPath(board, color, style = Stroke(width = 1.8f * sx))
                line(9f, 10f, 10f, 11f)
                line(10f, 11f, 12f, 8.8f)
                line(14f, 10f, 17f, 10f)
                line(9f, 15f, 10f, 16f)
                line(10f, 16f, 12f, 13.8f)
                line(14f, 15f, 17f, 15f)
                line(9f, 4f, 9f, 3f)
                line(15f, 4f, 15f, 3f)
            }
            // A map pin with a clear destination marker.
            1 -> {
                val pin = Path().apply {
                    moveTo(12f * sx, 22f * sy)
                    cubicTo(10f * sx, 19f * sy, 5f * sx, 13f * sy, 5f * sx, 9.5f * sy)
                    cubicTo(5f * sx, 5.6f * sy, 8.1f * sx, 3f * sy, 12f * sx, 3f * sy)
                    cubicTo(15.9f * sx, 3f * sy, 19f * sx, 5.6f * sy, 19f * sx, 9.5f * sy)
                    cubicTo(19f * sx, 13f * sy, 14f * sx, 19f * sy, 12f * sx, 22f * sy)
                    close()
                }
                drawPath(pin, color, style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round))
                circle(12f, 9.5f, 2.5f, 1.7f)
            }
            // A small aircraft silhouette for travel mode.
            2 -> {
                val plane = Path().apply {
                    moveTo(21f * sx, 4f * sy)
                    cubicTo(20.4f * sx, 3.4f * sy, 19.4f * sx, 3.5f * sy, 18.6f * sx, 4.1f * sy)
                    lineTo(13f * sx, 9f * sy)
                    lineTo(6.2f * sx, 6.8f * sy)
                    lineTo(4f * sx, 8.8f * sy)
                    lineTo(10.2f * sx, 11.8f * sy)
                    lineTo(7.8f * sx, 16.2f * sy)
                    lineTo(4.8f * sx, 15.7f * sy)
                    lineTo(3f * sx, 17.5f * sy)
                    lineTo(8f * sx, 20f * sy)
                    lineTo(12.3f * sx, 13.5f * sy)
                    lineTo(17.8f * sx, 9f * sy)
                    cubicTo(20.5f * sx, 6.7f * sy, 22f * sx, 5f * sy, 21f * sx, 4f * sy)
                    close()
                }
                drawPath(plane, color, style = Stroke(width = 1.6f * sx, cap = StrokeCap.Round))
            }
            // Sliders clearly identify preferences rather than a second travel icon.
            3 -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
                drawCircle(color, 3f * sx, p(9f, 6f))
                drawCircle(color, 3f * sx, p(15f, 12f))
                drawCircle(color, 3f * sx, p(9f, 18f))
            }
            else -> {
                // Unknown indices have a neutral, recognizable circular fallback.
                circle(12f, 12f, 8f)
                line(12f, 8f, 12f, 16f)
                circle(12f, 12f, 0.6f)
            }
        }
    }
}
