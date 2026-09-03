package com.shreddro.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shreddro.app.data.LedgerEntry
import java.util.Locale

// ── shared bits ──────────────────────────────────────────────────────────────

fun formatBaht(amount: Double): String = "฿%,.2f".format(Locale.US, amount)

private fun bankAbbrev(bank: String) = when {
    bank.contains("Bangkok", true) -> "BBL"
    bank.contains("Krungthai", true) -> "KTB"
    bank.contains("KBank", true) || bank.contains("Kasikorn", true) -> "KBK"
    bank.contains("SCB", true) -> "SCB"
    else -> bank.take(3).uppercase()
}

@Composable
fun BankAvatar(bank: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            bankAbbrev(bank),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun SlipRow(entry: LedgerEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BankAvatar(entry.bankName)
            Column(Modifier.weight(1f)) {
                Text(
                    entry.receiver.ifBlank { entry.bankName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.dateTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                formatBaht(entry.amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── navigation ───────────────────────────────────────────────────────────────

enum class Tab { HOME, LEDGER, REVIEW, ACCOUNT }

@Composable
fun ShreddroBottomNav(current: Tab, reviewCount: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        NavigationBarItem(
            selected = current == Tab.HOME, onClick = { onSelect(Tab.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = current == Tab.LEDGER, onClick = { onSelect(Tab.LEDGER) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Ledger") },
        )
        NavigationBarItem(
            selected = current == Tab.REVIEW, onClick = { onSelect(Tab.REVIEW) },
            icon = {
                BadgedBox(badge = {
                    if (reviewCount > 0) Badge { Text("$reviewCount") }
                }) { Icon(Icons.Filled.Info, contentDescription = null) }
            },
            label = { Text("Review") },
        )
        NavigationBarItem(
            selected = current == Tab.ACCOUNT, onClick = { onSelect(Tab.ACCOUNT) },
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            label = { Text("Account") },
        )
    }
}

// ── home ─────────────────────────────────────────────────────────────────────

data class ScanSummary(
    val scanned: Int,
    val archived: Int,
    val purged: Int,
    val skipped: Int,
    val needsReview: Int,
    val alreadyDone: Int,
)

data class HomeState(
    val pendingSweep: Int,
    val scanning: Boolean,
    val summary: ScanSummary?,
    val monthTotal: Double,
    val monthCount: Int,
    val googleLinked: Boolean,
    val microsoftLinked: Boolean,
    val recent: List<LedgerEntry>,
)

@Composable
fun HomeScreen(
    state: HomeState,
    onScan: () -> Unit,
    onSweep: () -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    "Shreddro",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        item { HeroCard(state, onScan, onSweep) }

        state.summary?.let { s ->
            item { ScanSummaryCard(s) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("This month", formatBaht(state.monthTotal), Modifier.weight(1f))
                StatTile("Slips logged", "${state.monthCount}", Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncChip("Google · Sheets + Drive", state.googleLinked)
                SyncChip("Microsoft · Excel", state.microsoftLinked)
            }
        }

        if (state.recent.isNotEmpty()) {
            item {
                Text(
                    "Recent slips",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(state.recent) { SlipRow(it) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HeroCard(state: HomeState, onScan: () -> Unit, onSweep: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                if (state.pendingSweep > 0) "READY TO SWEEP" else "GALLERY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    "${state.pendingSweep}",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 56.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    if (state.pendingSweep > 0) {
                        "slips archived, waiting to\nleave your gallery"
                    } else {
                        "slips waiting — scan your\ngallery to find new ones"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.pendingSweep > 0) {
                    Button(
                        onClick = onSweep,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sweep now", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onScan,
                        enabled = !state.scanning,
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (state.scanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = "Scan", modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = onScan,
                        enabled = !state.scanning,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        if (state.scanning) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan gallery", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanSummaryCard(s: ScanSummary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Last scan — ${s.scanned} image(s) checked",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    append("${s.archived} logged & archived · ${s.purged} swept")
                    if (s.needsReview > 0) append(" · ${s.needsReview} need review")
                    if (s.skipped > 0) append(" · ${s.skipped} not slips")
                    if (s.alreadyDone > 0) append(" · ${s.alreadyDone} already done")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SyncChip(label: String, linked: Boolean) {
    Surface(
        color = if (linked) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            (if (linked) "✓ " else "○ ") + label,
            style = MaterialTheme.typography.labelMedium,
            color = if (linked) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ── ledger ───────────────────────────────────────────────────────────────────

@Composable
fun LedgerScreen(entries: List<LedgerEntry>) {
    if (entries.isEmpty()) {
        EmptyState("No slips logged yet", "Scan your gallery from the Home tab.")
        return
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Ledger",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
        }
        items(entries) { SlipRow(it) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ── account ──────────────────────────────────────────────────────────────────

@Composable
fun AccountScreen(
    googleLinked: Boolean,
    microsoftLinked: Boolean,
    localMode: Boolean,
    onLinkGoogle: () -> Unit,
    onLinkMicrosoft: () -> Unit,
    onOpenSettings: () -> Unit,
    onLocalModeChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Account",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )

        OutlinedButton(onClick = onLinkGoogle, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (googleLinked) "Google linked ✓ — re-link" else "Continue with Google")
        }
        OutlinedButton(onClick = onLinkMicrosoft, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (microsoftLinked) "Microsoft linked ✓ — re-link" else "Continue with Microsoft")
        }
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Cloud sync settings")
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Local Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Keep everything on this phone — no cloud sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = localMode, onCheckedChange = onLocalModeChange)
        }

        Text(
            "Slips are read 100% on this device. Images only leave your phone " +
                "to your own Drive or OneDrive when a cloud account is linked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// ── shared empty state ───────────────────────────────────────────────────────

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
