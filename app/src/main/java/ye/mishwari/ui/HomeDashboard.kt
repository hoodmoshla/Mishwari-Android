package ye.mishwari.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

@Composable
internal fun HomeDashboard(
    errands: List<Errand>,
    places: List<Place>,
    activeTripId: String?,
    onOpenRequests: () -> Unit,
    onOpenTrip: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenAssistant: () -> Unit,
    onAddRequest: () -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val active = remember(errands) { errands.filter(::active).sortedWith(errandDisplayOrder) }
    val urgentCount = remember(active) { active.count { it.priority == 2 } }
    val completedCount = remember(errands) { errands.count { it.status == "مكتمل" } }
    val recentPlaceIds = remember(errands) {
        errands.sortedByDescending { it.createdAt }.mapNotNull { it.placeId }.distinct().take(4)
    }
    val recentPlaces = remember(places, recentPlaceIds) {
        val byId = places.associateBy { it.id }
        recentPlaceIds.mapNotNull(byId::get).ifEmpty { places.filter { it.kind == "custom" }.take(4) }
    }
    val trip = places.firstOrNull { it.id == activeTripId }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("أهلاً بك في مشواري", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("خطط طلباتك، اربطها بالأماكن، وأنهِ ما أنجزته. قوائمك محفوظة على هذا الجهاز وتعمل دون إنترنت.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddRequest) { Text("+ إضافة طلب") }
                    OutlinedButton(onClick = onOpenRequests) { Text("طلباتي") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("غير منجزة", active.size.toString(), Modifier.weight(1f))
            SummaryCard("عاجلة", urgentCount.toString(), Modifier.weight(1f))
            SummaryCard("مكتملة", completedCount.toString(), Modifier.weight(1f))
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("مشوارك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (trip != null) {
                    Text("الوجهة الحالية: ${placeLabel(trip)}", style = MaterialTheme.typography.bodyMedium)
                    Text("راجع طلبات الوجهة وحالة تنبيهات الوصول قبل الانطلاق.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("لا توجد وجهة نشطة بعد. اختر مكانًا وراجع طلباته قبل تفعيل المشوار.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onOpenTrip) { Text(if (trip == null) "تجهيز مشوار" else "فتح المشوار") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("أولوية اليوم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenRequests) { Text("كل الطلبات") }
            }
            val top = active.take(3)
            if (top.isEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("لا توجد طلبات غير منجزة", style = MaterialTheme.typography.titleSmall)
                        Text("أضف طلبًا منفردًا أو ألصق قائمة مشتريات متعددة الأسطر.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else top.forEach { errand ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(errand.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(places.firstOrNull { it.id == errand.placeId }?.let(::placeLabel) ?: "دون وجهة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("اختصارات الأماكن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenPlaces) { Text("إدارة") }
            }
            if (recentPlaces.isEmpty()) {
                Text("أضف سوقًا أو محلًا، ثم اربط به الطلبات لتجميعها في مشوارك.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentPlaces.forEach { place ->
                        AssistChip(onClick = { onOpenPlace(place.id) }, label = { Text(place.name) })
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenPlaces, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text("الأماكن والدليل") }
            OutlinedButton(onClick = onOpenAssistant, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text("المساعد الذكي") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
