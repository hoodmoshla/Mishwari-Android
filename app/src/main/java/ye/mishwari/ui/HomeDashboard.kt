package ye.mishwari.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

@Composable
internal fun HomeDashboard(
    errands: List<Errand>,
    places: List<Place>,
    activeTripId: String?,
    tripReadiness: String,
    onOpenRequests: () -> Unit,
    onOpenTrip: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenAssistant: () -> Unit,
    onAddRequest: () -> Unit,
    onAddPlace: () -> Unit,
    onBulkInput: () -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val activeErrands = remember(errands) { errands.filter(::active).sortedWith(errandDisplayOrder) }
    val urgentCount = remember(activeErrands) { activeErrands.count { it.priority == 2 } }
    val completedCount = remember(errands) { errands.count { it.status == "مكتمل" } }
    val trip = places.firstOrNull { it.id == activeTripId }
    val tripCount = remember(errands, places, activeTripId) {
        if (activeTripId.isNullOrBlank()) 0 else errands.count { it.placeId in subtree(activeTripId, places) && active(it) }
    }
    val recentPlaces = remember(places, errands) {
        val byId = places.associateBy { it.id }
        errands.sortedByDescending { it.createdAt }.mapNotNull { it.placeId }.distinct().take(4)
            .mapNotNull(byId::get).ifEmpty { places.filter { it.kind == "custom" }.take(4) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("أهلاً بك في مشواري", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("طلباتك وأماكنك ومشاويرك\nبسهولة، في مكان واحد.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                WelcomeSkyline(Modifier.width(88.dp).height(94.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SummaryCard("معلّقة", activeErrands.size.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            SummaryCard("عاجلة", urgentCount.toString(), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            SummaryCard("مكتملة", completedCount.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }

        Card(
            onClick = onOpenTrip,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("↗", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (trip == null) "جهّز مشوارك القادم" else "مشوار نشط", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(trip?.let(::placeLabel) ?: "اختر وجهة واربط بها طلباتك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (trip == null) "قائمة منظمة، دون تسجيل مسارك." else "$tripCount طلب غير منجز · $tripReadiness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("‹", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Button(
            onClick = onAddRequest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MishwariBrightGreen, contentColor = Color.White),
        ) { Text("＋  إضافة طلب سريع", fontWeight = FontWeight.Bold) }

        Text("إجراءات سريعة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionTile("＋", "إضافة طلب", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, onAddRequest, Modifier.weight(1f))
            QuickActionTile("⌖", "إضافة مكان", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, onAddPlace, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionTile("☷", "قائمة من نص", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary, onBulkInput, Modifier.weight(1f))
            QuickActionTile("↗", "مشوار جديد", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, onOpenTrip, Modifier.weight(1f))
        }

        if (activeErrands.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("أولوية اليوم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenRequests) { Text("عرض الكل") }
            }
            activeErrands.take(2).forEach { errand ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { if (errand.placeId.isNullOrBlank()) onOpenRequests() else onOpenPlace(errand.placeId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", color = if (errand.priority == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f)) {
                            Text(errand.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(places.firstOrNull { it.id == errand.placeId }?.let(::placeLabel) ?: "دون وجهة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = onOpenAssistant) { Text("مساعدة في تنظيم الطلبات") }
        }
        if (recentPlaces.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("أماكنك الأخيرة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenPlaces) { Text("إدارة") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentPlaces.forEach { place -> OutlinedButton(onClick = { onOpenPlace(place.id) }) { Text(place.name) } }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier.heightIn(min = 80.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.11f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun QuickActionTile(glyph: String, label: String, background: Color, foreground: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(glyph, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = foreground)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

/** A small decorative Yemeni skyline drawn with Compose, not the reference screenshot. */
@Composable
private fun WelcomeSkyline(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(Color(0xFFFFD470), w * .17f, Offset(w * .75f, h * .18f))
        val hills = Path().apply { moveTo(0f, h); lineTo(0f, h * .75f); quadraticBezierTo(w * .22f, h * .5f, w * .45f, h * .78f); quadraticBezierTo(w * .7f, h * .55f, w, h * .8f); lineTo(w, h); close() }
        drawPath(hills, Color(0xFFAECF86))
        fun building(x: Float, y: Float, bw: Float, bh: Float, color: Color) {
            drawRoundRect(color, Offset(x, y), Size(bw, bh), CornerRadius(3f, 3f))
            for (row in 0..3) {
                val yy = y + bh * (.14f + row * .2f)
                drawLine(Color(0xFFFFE3B4), Offset(x + bw * .12f, yy), Offset(x + bw * .88f, yy), 2f)
                for (col in 0..1) drawRoundRect(Color(0xFF774D36), Offset(x + bw * (.23f + col * .35f), yy + bh * .035f), Size(bw * .14f, bh * .09f), CornerRadius(2f, 2f))
            }
        }
        building(w * .1f, h * .28f, w * .29f, h * .65f, Color(0xFFC4854C))
        building(w * .4f, h * .08f, w * .31f, h * .87f, Color(0xFFB77C4E))
        building(w * .71f, h * .4f, w * .23f, h * .55f, Color(0xFFD49C60))
    }
}
