package ye.mishwari.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small Material-aligned navigation symbols, drawn locally without an icon dependency. */
@Composable
internal fun NavigationIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.8f) {
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = width * sx, cap = StrokeCap.Round)
        }
        fun circle(x: Float, y: Float, radius: Float, width: Float = 1.8f) {
            drawCircle(color, radius * sx, p(x, y), style = Stroke(width * sx))
        }
        when (index) {
            0 -> {
                val home = Path().apply {
                    moveTo(3f * sx, 10f * sy)
                    lineTo(12f * sx, 3f * sy)
                    lineTo(21f * sx, 10f * sy)
                    lineTo(19f * sx, 10f * sy)
                    lineTo(19f * sx, 20f * sy)
                    quadraticTo(19f * sx, 21f * sy, 18f * sx, 21f * sy)
                    lineTo(6f * sx, 21f * sy)
                    quadraticTo(5f * sx, 21f * sy, 5f * sx, 20f * sy)
                    lineTo(5f * sx, 10f * sy)
                    close()
                }
                drawPath(home, color, style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round))
                drawRoundRect(color, topLeft = p(10f, 14f), size = androidx.compose.ui.geometry.Size(4f * sx, 7f * sy), cornerRadius = CornerRadius(1f * sx, 1f * sy), style = Stroke(width = 1.5f * sx))
            }
            1 -> {
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
                line(8.5f, 10f, 9.5f, 11f)
                line(9.5f, 11f, 11.5f, 8.8f)
                line(14f, 10f, 17f, 10f)
                line(8.5f, 15f, 9.5f, 16f)
                line(9.5f, 16f, 11.5f, 13.8f)
                line(14f, 15f, 17f, 15f)
                line(9f, 4f, 9f, 3f)
                line(15f, 4f, 15f, 3f)
            }
            2 -> {
                val map = Path().apply {
                    moveTo(3f * sx, 5f * sy); lineTo(9f * sx, 3f * sy)
                    lineTo(15f * sx, 5f * sy); lineTo(21f * sx, 3f * sy)
                    lineTo(21f * sx, 19f * sy); lineTo(15f * sx, 21f * sy)
                    lineTo(9f * sx, 19f * sy); lineTo(3f * sx, 21f * sy)
                    close()
                }
                drawPath(map, color, style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round))
                line(9f, 3f, 9f, 19f); line(15f, 5f, 15f, 21f)
            }
            3 -> {
                drawRoundRect(color, topLeft = p(3f, 4f), size = androidx.compose.ui.geometry.Size(18f * sx, 14f * sy), cornerRadius = CornerRadius(4f * sx, 4f * sy), style = Stroke(width = 1.8f * sx))
                val tail = Path().apply { moveTo(8f * sx, 18f * sy); lineTo(6f * sx, 21f * sy); lineTo(12f * sx, 18f * sy) }
                drawPath(tail, color, style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round))
                circle(8f, 11f, 0.7f)
                circle(12f, 11f, 0.7f)
                circle(16f, 11f, 0.7f)
            }
            4 -> {
                circle(12f, 12f, 6.8f); circle(12f, 12f, 2.6f)
                repeat(8) { n ->
                    val a = n * Math.PI / 4.0
                    line(12f + 7f * kotlin.math.cos(a).toFloat(), 12f + 7f * kotlin.math.sin(a).toFloat(), 12f + 9.2f * kotlin.math.cos(a).toFloat(), 12f + 9.2f * kotlin.math.sin(a).toFloat(), 2.8f)
                }
            }
            5 -> {
                val cloud = Path().apply {
                    moveTo(6f * sx, 18f * sy)
                    cubicTo(0f * sx, 18f * sy, 1f * sx, 9f * sy, 7f * sx, 10f * sy)
                    cubicTo(7f * sx, 2f * sy, 19f * sx, 2f * sy, 19f * sx, 11f * sy)
                    cubicTo(25f * sx, 11f * sy, 24f * sx, 18f * sy, 19f * sx, 18f * sy)
                    close()
                }
                drawPath(cloud, color, style = Stroke(1.8f * sx))
                line(12f, 11f, 12f, 21f); line(9f, 18f, 12f, 21f); line(15f, 18f, 12f, 21f)
            }
            6 -> {
                val pin = Path().apply {
                    moveTo(12f * sx, 22f * sy)
                    cubicTo(9f * sx, 18f * sy, 4f * sx, 13f * sy, 4f * sx, 9f * sy)
                    cubicTo(4f * sx, -1f * sy, 20f * sx, -1f * sy, 20f * sx, 9f * sy)
                    cubicTo(20f * sx, 13f * sy, 15f * sx, 18f * sy, 12f * sx, 22f * sy)
                    close()
                }
                drawPath(pin, color, style = Stroke(1.8f * sx)); circle(12f, 9f, 2.5f)
            }
            8 -> {
                val moon = Path().apply {
                    moveTo(16f * sx, 3f * sy)
                    cubicTo(1f * sx, -1f * sy, -2f * sx, 22f * sy, 13f * sx, 22f * sy)
                    cubicTo(18f * sx, 22f * sy, 21f * sx, 19f * sy, 22f * sx, 15f * sy)
                    cubicTo(11f * sx, 19f * sy, 8f * sx, 7f * sy, 16f * sx, 3f * sy)
                    close()
                }
                drawPath(moon, color, style = Stroke(1.8f * sx))
            }
            9 -> {
                circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f)
                drawCircle(color, 1.1f * sx, p(12f, 7f))
            }
        }
    }
}

@Composable
internal fun FeatureBadge(index: Int, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.12f)) {
        CompositionLocalProvider(LocalContentColor provides accent) {
            Box(Modifier.padding(10.dp)) { NavigationIcon(index) }
        }
    }
}

@Composable
internal fun MishwariBrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        drawOval(Color(0xFFFFC42B), Offset(w * .22f, h * .84f), Size(w * .56f, h * .13f))
        val pin = Path().apply {
            moveTo(w * .5f, h * .9f)
            cubicTo(w * .38f, h * .73f, w * .09f, h * .49f, w * .09f, h * .33f)
            cubicTo(w * .09f, -h * .08f, w * .91f, -h * .08f, w * .91f, h * .33f)
            cubicTo(w * .91f, h * .51f, w * .62f, h * .75f, w * .5f, h * .9f)
            close()
        }
        drawPath(pin, Brush.linearGradient(listOf(Color(0xFFFF9C13), Color(0xFF008F51)), Offset(w, 0f), Offset(0f, h)))
        drawCircle(Color.White, w * .28f, Offset(w * .5f, h * .33f))
        val cart = Path().apply { moveTo(w * .29f, h * .23f); lineTo(w * .36f, h * .23f); lineTo(w * .41f, h * .43f); lineTo(w * .65f, h * .43f); lineTo(w * .7f, h * .28f); lineTo(w * .39f, h * .28f) }
        drawPath(cart, Color(0xFF075B40), style = Stroke(w * .035f, cap = StrokeCap.Round))
        drawCircle(Color(0xFF075B40), w * .035f, Offset(w * .44f, h * .5f))
        drawCircle(Color(0xFF075B40), w * .035f, Offset(w * .62f, h * .5f))
    }
}
