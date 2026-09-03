package com.shreddro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.review.ReviewItem

/**
 * NeedsReview screen: slips that passed detection but couldn't be read with
 * confidence. Each card offers Retry (fresh OCR pass), Enter manually
 * (contract-field form), or Dismiss. Originals stay in the gallery until
 * resolved — never guessed, never silently dropped.
 */
@Composable
fun ReviewScreen(
    items: List<ReviewItem>,
    onRetry: (ReviewItem) -> Unit,
    onManual: (ReviewItem, TransactionSlip) -> Unit,
    onDismiss: (ReviewItem) -> Unit,
) {
    var manualFor by remember { mutableStateOf<ReviewItem?>(null) }

    if (items.isEmpty()) {
        EmptyState("Nothing needs review", "Slips that can't be read automatically will appear here.")
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.padding(top = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Needs review",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "${items.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
        item {
            Text(
                "These slips passed detection but couldn't be read automatically. " +
                    "They stay in your gallery until resolved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items) { item ->
            ReviewCard(
                item = item,
                onRetry = { onRetry(item) },
                onManual = { manualFor = item },
                onDismiss = { onDismiss(item) },
            )
        }
    }

    manualFor?.let { item ->
        ManualEntryDialog(
            item = item,
            onSave = { slip ->
                manualFor = null
                onManual(item, slip)
            },
            onCancel = { manualFor = null },
        )
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                item.fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                item.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) { Text("Retry") }
                OutlinedButton(
                    onClick = onManual,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(44.dp),
                ) { Text("Enter manually") }
                TextButton(onClick = onDismiss, modifier = Modifier.height(44.dp)) { Text("✕") }
            }
        }
    }
}

/**
 * Bring-your-own-data manual entry, matching the slip contract exactly.
 */
@Composable
private fun ManualEntryDialog(
    item: ReviewItem,
    onSave: (TransactionSlip) -> Unit,
    onCancel: () -> Unit,
) {
    var bank by remember { mutableStateOf("") }
    var dateTime by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }

    val parsedAmount = amount.toDoubleOrNull()
    val valid = bank.isNotBlank() && dateTime.isNotBlank() &&
        parsedAmount != null && parsedAmount >= 0.0

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(item.fileName) },
        text = {
            Column {
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") })
                OutlinedTextField(dateTime, { dateTime = it }, label = { Text("Date/time") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (THB)") })
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender") })
                OutlinedTextField(receiver, { receiver = it }, label = { Text("Receiver") })
                OutlinedTextField(reference, { reference = it }, label = { Text("Reference id") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        TransactionSlip(
                            bankName = bank.trim(),
                            dateTime = dateTime.trim(),
                            amount = parsedAmount ?: 0.0,
                            sender = sender.trim(),
                            receiver = receiver.trim(),
                            referenceId = reference.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
